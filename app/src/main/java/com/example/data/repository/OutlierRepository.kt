package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.ChannelCacheEntity
import com.example.data.local.ChannelCacheStatus
import com.example.data.local.SearchCacheEntity
import com.example.data.local.SettingsStore
import com.example.data.local.ShortVerificationCacheEntity
import com.example.data.local.VideoStatsCacheEntity
import com.example.data.remote.ChannelItem
import com.example.data.remote.ChannelListResponse
import com.example.data.remote.PlaylistItem
import com.example.data.remote.ShortsVerifier
import com.example.data.remote.YouTubeApi
import com.example.domain.CacheTtl
import com.example.domain.ChannelInputParser
import com.example.domain.CostEstimate
import com.example.domain.CostEstimator
import com.example.domain.DebugStats
import com.example.domain.DiscoveryCacheKey
import com.example.domain.DiscoveryHelpers
import com.example.domain.DiscoveryMode
import com.example.domain.DurationParser
import com.example.domain.FilterOptions
import com.example.domain.MedianCalculator
import com.example.domain.NearMissVideo
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import com.example.domain.UushHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

sealed class SearchResult {
  data class Success(
    val outliers: List<OutlierVideo>,
    val nearMisses: List<NearMissVideo> = emptyList(),
    val debugStats: DebugStats,
    val nextPageToken: String?,
    val isCacheHit: Boolean
  ) : SearchResult()

  data class Error(
    val message: String,
    val isKeyInvalid: Boolean = false,
    val isQuotaExceeded: Boolean = false,
    val failedBucket: QuotaBucket? = null
  ) : SearchResult()
}

/** Thrown inside the pipeline when the current key must be rotated. */
private class AbortSearchException(val error: SearchResult.Error) : Exception()

class OutlierRepository(
  private val youTubeApi: YouTubeApi,
  private val database: AppDatabase,
  private val settingsStore: SettingsStore,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager,
  private val shortsVerifier: ShortsVerifier,
  private val aiRepository: AiRepository
) {
  private val searchCacheDao = database.searchCacheDao()
  private val channelCacheDao = database.channelCacheDao()
  private val videoStatsDao = database.videoStatsDao()
  private val verificationCacheDao = database.shortVerificationDao()
  private val savedDao = database.savedOutlierDao()

  private val _progressFlow = MutableStateFlow<String?>(null)
  val progressFlow: StateFlow<String?> = _progressFlow.asStateFlow()

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val listType = Types.newParameterizedType(List::class.java, OutlierVideo::class.java)
  private val outlierListAdapter = moshi.adapter<List<OutlierVideo>>(listType)
  private val nearMissListType = Types.newParameterizedType(List::class.java, NearMissVideo::class.java)
  private val nearMissListAdapter = moshi.adapter<List<NearMissVideo>>(nearMissListType)
  private val debugStatsAdapter = moshi.adapter(DebugStats::class.java)
  private val stringListType = Types.newParameterizedType(List::class.java, String::class.java)
  private val stringListAdapter = moshi.adapter<List<String>>(stringListType)

  companion object {
    /** One attempt per key, plus a small buffer for late rotation. */
    private const val MAX_KEY_ATTEMPTS = SettingsStore.MAX_API_KEYS + 1
  }

  fun createCacheKey(filters: FilterOptions, pageToken: String? = null): String =
    DiscoveryCacheKey.build(filters, pageToken)

  suspend fun searchOutliers(
    filters: FilterOptions,
    pageToken: String? = null,
    forceRefresh: Boolean = false,
    /** Called before the (expensive) per-channel stage. Return false to use cached baselines only. */
    onConfirmCost: (suspend (CostEstimate) -> Boolean)? = null
  ): SearchResult = withContext(Dispatchers.IO) {
    try {
      val clean = filters.withTrimmedKeyword()
      when (clean.mode) {
        DiscoveryMode.KEYWORD -> {
          runSearch(clean, pageToken, forceRefresh, onConfirmCost)
        }
        DiscoveryMode.MULTI_KEYWORD -> {
          val keywords = DiscoveryHelpers.parseKeywordList(clean.multiKeywords)
          if (keywords.isEmpty()) {
            SearchResult.Error("Multi-keyword mode me kam se kam ek keyword daalo (comma se alag karein).")
          } else {
            runMerged(clean, keywords, forceRefresh, onConfirmCost)
          }
        }
        DiscoveryMode.AUTO_EXPAND -> {
          if (clean.keyword.isBlank()) {
            SearchResult.Error("Auto-expand ke liye ek seed keyword daalo.")
          } else {
            _progressFlow.value = "Gemini se 6 micro-niche keywords ban rahe hain..."
            val expansion = aiRepository.expandKeywords(clean.keyword, "Hinglish", forceRefresh)
            if (!expansion.fromAi) {
              _progressFlow.value = "Gemini available nahi hai, offline niche ideas use ho rahe hain..."
            }
            runMerged(clean, expansion.keywords, forceRefresh, onConfirmCost)
          }
        }
        DiscoveryMode.CHANNEL_SCAN -> {
          runChannelScan(clean, forceRefresh)
        }
        DiscoveryMode.SIMILAR_TO_OUTLIER -> {
          val query = DiscoveryHelpers.buildSimilarQuery(
            clean.similarSeedTags,
            clean.similarSeedTitle.ifBlank { clean.keyword }
          )
          if (query.isBlank()) {
            SearchResult.Error("Similar search ke liye pehle koi result card chuno.")
          } else {
            runSearch(clean.copy(keyword = query), pageToken, forceRefresh, onConfirmCost)
          }
        }
      }
    } finally {
      _progressFlow.value = null
    }
  }

  private suspend fun runSearch(
    filters: FilterOptions,
    pageToken: String?,
    forceRefresh: Boolean,
    onConfirmCost: (suspend (CostEstimate) -> Boolean)?
  ): SearchResult {
    val cleanFilters = filters.withTrimmedKeyword()
    val cacheKey = createCacheKey(cleanFilters, pageToken)

    if (!forceRefresh) {
      val cached = searchCacheDao.getCache(cacheKey)
      if (cached != null &&
        CacheTtl.isFresh(System.currentTimeMillis(), cached.timestamp, CacheTtl.SEARCH_RESULTS_MS)
      ) {
        try {
          val cachedResults = outlierListAdapter.fromJson(cached.resultsJson) ?: emptyList()
          val cachedNearMisses =
            runCatching { nearMissListAdapter.fromJson(cached.nearMissesJson) }.getOrNull()
              ?: emptyList()
          val cachedStats = debugStatsAdapter.fromJson(cached.debugStatsJson) ?: DebugStats()
          // Update isSaved state dynamically
          val updatedWithSaved = cachedResults.map { it.copy(isSaved = savedDao.isSaved(it.videoId)) }
          return SearchResult.Success(
            outliers = updatedWithSaved,
            nearMisses = cachedNearMisses,
            debugStats = cachedStats.copy(cacheHits = cachedStats.cacheHits + 1),
            nextPageToken = cached.nextPageToken,
            isCacheHit = true
          )
        } catch (_: Exception) {
          // If cache deserialization fails, continue with a fresh search.
        }
      }
    }

    apiKeyManager.ensureMigrated()

    // The user only sees the cost dialog once, even if the search rotates to another key.
    var costDecision: Boolean? = null
    val confirmCost: suspend (CostEstimate) -> Boolean = { estimate ->
      if (!estimate.needsConfirmation) {
        true
      } else {
        val decided = costDecision
        if (decided != null) {
          decided
        } else {
          val result = onConfirmCost?.invoke(estimate) ?: true
          costDecision = result
          result
        }
      }
    }

    val startKey = apiKeyManager.acquireKey(QuotaBucket.SEARCH)
    if (startKey == null) {
      val hasKeys = settingsStore.getApiKeys().isNotEmpty()
      return SearchResult.Error(
        message = if (hasKeys) {
          "Saari API keys ka quota khatam ho gaya hai. Kal midnight Pacific par reset hoga."
        } else {
          "API key nahi mili. Kripya pehle apni key daalein."
        },
        isKeyInvalid = !hasKeys,
        isQuotaExceeded = hasKeys,
        failedBucket = QuotaBucket.SEARCH
      )
    }
    var key: ActiveKey = startKey

    var lastError: SearchResult.Error? = null

    for (attempt in 0 until MAX_KEY_ATTEMPTS) {
      val quotaCheck = quotaTracker.canAffordSearch(
        fingerprint = key.fingerprint,
        pages = cleanFilters.pagesPerSearch,
        estimatedUnits = cleanFilters.pagesPerSearch + 1 + 20 // rough initial estimate
      )
      if (quotaCheck is QuotaCheckResult.Blocked) {
        lastError = SearchResult.Error(
          quotaCheck.reason,
          isQuotaExceeded = true,
          failedBucket = quotaCheck.bucket
        )
        val next = apiKeyManager.rotateAway(key, quotaCheck.bucket)
        if (next != null) {
          key = next
          continue
        }
        return lastError
      }

      val result = try {
        searchWithKey(cleanFilters, pageToken, cacheKey, key, confirmCost)
      } catch (e: AbortSearchException) {
        e.error
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        return SearchResult.Error("Internet check karo")
      } catch (e: Exception) {
        val safeMsg = (e.message ?: "Search me anjaan khamiyan aayi").replace(key.key, "[REDACTED]")
        return SearchResult.Error(safeMsg)
      }

      if (result is SearchResult.Success) return result

      lastError = result as SearchResult.Error
      when {
        lastError.isQuotaExceeded -> {
          val next = apiKeyManager.rotateAway(
            key,
            lastError.failedBucket ?: QuotaBucket.SEARCH
          )
          if (next != null) {
            key = next
            continue
          }
          return lastError
        }
        lastError.isKeyInvalid -> {
          apiKeyManager.discardKey(key.key)
          val next = apiKeyManager.acquireKey(QuotaBucket.SEARCH)
          if (next != null) {
            key = next
            continue
          }
          return SearchResult.Error(
            "Key kaam nahi kar rahi, nayi key daalo",
            isKeyInvalid = true
          )
        }
        else -> return lastError
      }
    }

    return lastError ?: SearchResult.Error("Search fail ho gayi")
  }

  /**
   * MULTI_KEYWORD / AUTO_EXPAND: runs the normal keyword pipeline once per
   * keyword (so per-keyword result caching still works) and merges the unique
   * videos. The first keyword wins for duplicates; later hits record their
   * matched keyword.
   */
  private suspend fun runMerged(
    filters: FilterOptions,
    keywords: List<String>,
    forceRefresh: Boolean,
    onConfirmCost: (suspend (CostEstimate) -> Boolean)?
  ): SearchResult {
    val merged = LinkedHashMap<String, OutlierVideo>()
    val mergedNearMisses = LinkedHashMap<String, NearMissVideo>()
    var aggregated = DebugStats()
    var successes = 0
    var lastError: SearchResult.Error? = null

    // Ask for cost confirmation at most once for the whole merged search.
    var mergedCostDecision: Boolean? = null
    val confirmCost: suspend (CostEstimate) -> Boolean = { estimate ->
      val decided = mergedCostDecision
      if (decided != null) {
        decided
      } else {
        val result = onConfirmCost?.invoke(estimate) ?: true
        mergedCostDecision = result
        result
      }
    }

    for ((index, kw) in keywords.withIndex()) {
      _progressFlow.value = "Keyword ${index + 1}/${keywords.size}: $kw"
      val subFilters = filters.copy(
        mode = DiscoveryMode.KEYWORD,
        keyword = kw,
        multiKeywords = ""
      )
      when (val result = runSearch(subFilters, null, forceRefresh, confirmCost)) {
        is SearchResult.Success -> {
          successes++
          result.outliers.forEach { video ->
            if (!merged.containsKey(video.videoId)) {
              merged[video.videoId] = video.copy(matchedKeyword = kw)
            }
          }
          result.nearMisses.forEach { nearMiss ->
            mergedNearMisses.putIfAbsent(nearMiss.videoId, nearMiss)
          }
          aggregated = sumStats(aggregated, result.debugStats)
        }
        is SearchResult.Error -> {
          lastError = result
          // Quota/key problems are global: keep whatever was already merged.
          if (result.isQuotaExceeded || result.isKeyInvalid) break
        }
      }
    }

    if (successes == 0) {
      return lastError ?: SearchResult.Error("Koi keyword results nahi de paya.")
    }

    val finalized = DiscoveryHelpers.flagReuploads(merged.values.toList())
    val nearMisses = mergedNearMisses.values
      .sortedWith(
        compareByDescending<NearMissVideo> { it.stagePriority }
          .thenByDescending { it.multiplier ?: 0.0 }
          .thenByDescending { it.views }
      )
      .take(10)

    val stats = aggregated.copy(
      finalOutliers = finalized.size,
      reuploadFlagged = finalized.count { it.isPossibleReupload },
      // A merged search has no single cursor; "Load more" is keyword-mode only.
      mainTotalCost = aggregated.mainTotalCost,
      pages = aggregated.pages
    )

    return SearchResult.Success(
      outliers = finalized,
      nearMisses = nearMisses,
      debugStats = stats,
      nextPageToken = null,
      isCacheHit = false
    )
  }

  /**
   * CHANNEL_SCAN: resolves one channel, reads its Shorts straight from the
   * UUSH playlist and compares every Short against that channel's own median.
   * Uses only the main unit bucket (no search.list calls).
   */
  private suspend fun runChannelScan(
    filters: FilterOptions,
    forceRefresh: Boolean
  ): SearchResult {
    val cacheKey = createCacheKey(filters)
    if (!forceRefresh) {
      readCachedResult(cacheKey)?.let { return it }
    }

    val target = ChannelInputParser.parse(filters.channelInput)
      ?: return SearchResult.Error("Channel URL, @handle ya UC id daalo.")

    apiKeyManager.ensureMigrated()
    val startKey = apiKeyManager.acquireKey(QuotaBucket.MAIN)
    if (startKey == null) {
      val hasKeys = settingsStore.getApiKeys().isNotEmpty()
      return SearchResult.Error(
        message = if (hasKeys) {
          "Saari API keys ka quota khatam ho gaya hai. Kal midnight Pacific par reset hoga."
        } else {
          "API key nahi mili. Kripya pehle apni key daalein."
        },
        isKeyInvalid = !hasKeys,
        isQuotaExceeded = hasKeys,
        failedBucket = QuotaBucket.MAIN
      )
    }
    var key: ActiveKey = startKey
    var lastError: SearchResult.Error? = null

    for (attempt in 0 until MAX_KEY_ATTEMPTS) {
      val quotaCheck = quotaTracker.canAffordSearch(
        fingerprint = key.fingerprint,
        pages = 0,
        estimatedUnits = 8
      )
      if (quotaCheck is QuotaCheckResult.Blocked) {
        lastError = SearchResult.Error(
          quotaCheck.reason,
          isQuotaExceeded = true,
          failedBucket = quotaCheck.bucket
        )
        val next = apiKeyManager.rotateAway(key, quotaCheck.bucket)
        if (next != null) {
          key = next
          continue
        }
        return lastError
      }

      val result = try {
        scanChannelWithKey(filters, target, cacheKey, key)
      } catch (e: AbortSearchException) {
        e.error
      } catch (e: CancellationException) {
        throw e
      } catch (e: IOException) {
        return SearchResult.Error("Internet check karo")
      } catch (e: Exception) {
        return SearchResult.Error(
          (e.message ?: "Channel scan me khamiyan aayi").replace(key.key, "[REDACTED]")
        )
      }

      if (result is SearchResult.Success) return result
      lastError = result as SearchResult.Error
      when {
        lastError.isQuotaExceeded -> {
          val next = apiKeyManager.rotateAway(
            key,
            lastError.failedBucket ?: QuotaBucket.MAIN
          )
          if (next != null) {
            key = next
            continue
          }
          return lastError
        }
        lastError.isKeyInvalid -> {
          apiKeyManager.discardKey(key.key)
          val next = apiKeyManager.acquireKey(QuotaBucket.MAIN)
          if (next != null) {
            key = next
            continue
          }
          return SearchResult.Error("Key kaam nahi kar rahi, nayi key daalo", isKeyInvalid = true)
        }
        else -> return lastError
      }
    }

    return lastError ?: SearchResult.Error("Channel scan fail ho gaya")
  }

  private suspend fun scanChannelWithKey(
    filters: FilterOptions,
    target: ChannelInputParser.ChannelTarget,
    cacheKey: String,
    key: ActiveKey
  ): SearchResult {
    _progressFlow.value = "Channel resolve ho raha hai (${target.display})..."
    val channelItem = resolveChannelItem(target, key)
      ?: return SearchResult.Error("Channel nahi mila. URL ya @handle check karo.")

    val channelId = channelItem.id
    val subs = channelItem.statistics?.subscriberCount?.toLongOrNull() ?: 0L
    val hiddenSubs = channelItem.statistics?.hiddenSubscriberCount ?: false
    val channelTitle = channelItem.snippet?.title ?: target.display
    val uploadsId = channelItem.contentDetails?.relatedPlaylists?.uploads
    val nowMs = System.currentTimeMillis()

    channelCacheDao.insertIfAbsent(channelId, subs, hiddenSubs, uploadsId, nowMs)
    channelCacheDao.updateSubscriberInfo(channelId, subs, hiddenSubs, uploadsId, nowMs)

    val sample = filters.samplePerChannel.coerceIn(5, 50)
    _progressFlow.value = "Shorts playlist padhi ja rahi hai..."
    var isUush = true
    var playlistCost = 0
    var items = fetchPlaylistItems(
      playlistId = UushHelper.getShortsPlaylistId(channelId),
      maxResults = sample,
      key = key,
      onUnitsUsed = { playlistCost += it }
    )

    if (items.isEmpty()) {
      isUush = false
      val fallbackId = uploadsId
        ?: (if (channelId.startsWith("UC")) "UU" + channelId.drop(2) else null)
        ?: return SearchResult.Error("Is channel ki Shorts/upload playlist nahi mili.")
      items = fetchPlaylistItems(
        playlistId = fallbackId,
        maxResults = sample,
        key = key,
        onUnitsUsed = { playlistCost += it }
      )
    }

    if (items.isEmpty()) {
      return SearchResult.Error("Is channel par koi public Short nahi mila.")
    }

    val playlistVideoIds = items.mapNotNull { it.contentDetails?.videoId }
      .distinct()
      .take(sample)

    if (playlistVideoIds.isEmpty()) {
      return SearchResult.Error("Playlist me video ids nahi mile. Dobara try karo.")
    }

    _progressFlow.value = "Shorts ki details fetch ho rahi hain (${playlistVideoIds.size})..."
    var videosCost = 0
    val details = fetchVideoDetails(
      videoIds = playlistVideoIds,
      key = key,
      onUnitsUsed = { videosCost += it },
      onCacheHit = {}
    )

    val now = Instant.now()
    val candidates = details.values.mapNotNull { v ->
      if (v.videoId.isBlank()) return@mapNotNull null
      RawVideoCandidate(
        videoId = v.videoId,
        title = v.title,
        description = v.description,
        tags = parseTags(v.tagsJson),
        thumbnailUrl = v.thumbnailUrl,
        channelId = v.channelId.ifBlank { channelId },
        channelTitle = v.channelTitle.ifBlank { channelTitle },
        views = v.views,
        likes = v.likes,
        comments = v.comments,
        durationSeconds = v.durationSeconds,
        publishedAt = v.publishedAt
      )
    }

    val durationFiltered = candidates.filter { it.durationSeconds <= filters.maxDurationSeconds }
    val excludes = DiscoveryHelpers.parseExcludeKeywords(filters.excludeKeywords)
    val excludeFiltered = durationFiltered.filter {
      !DiscoveryHelpers.matchesExcludeKeyword(it.title, it.tags, excludes)
    }
    val likesFiltered = excludeFiltered.filter {
      DiscoveryHelpers.passesLikesRatio(it.likes, it.views, filters.minLikesRatio)
    }
    val ageFiltered = likesFiltered.filter {
      DiscoveryHelpers.isWithinViralAge(it.publishedAt, filters.maxViralAgeDays, now)
    }

    val baselineViews = ageFiltered
      .filter { MedianCalculator.isMatureEnough(it.publishedAt, filters.minBaselineAgeHours, now) }
      .map { it.views }
    val baseline = MedianCalculator.calculateBaseline(baselineViews)
      ?: return SearchResult.Error(
        "Channel me baseline ke liye kaafi mature Shorts nahi hain " +
          "(${MedianCalculator.MIN_SAMPLES}+ chahiye)."
      )

    // The median already includes this channel's spikes, so a 10x cap on the
    // configured multiplier keeps the mode useful with the default 50x preset.
    val outlierThreshold = minOf(filters.minMultiplier.toDouble(), 10.0).coerceAtLeast(2.0)

    val outliers = mutableListOf<OutlierVideo>()
    val nearMisses = mutableListOf<NearMissVideo>()

    for (v in ageFiltered) {
      val multiplier = OutlierCalculator.calculateMultiplier(v.views, baseline.median)
      val isOutlier = multiplier >= outlierThreshold
      if (isOutlier) {
        outliers.add(
          OutlierVideo(
            videoId = v.videoId,
            title = v.title,
            description = v.description,
            tags = v.tags,
            thumbnailUrl = v.thumbnailUrl,
            channelId = channelId,
            channelTitle = channelTitle,
            subscriberCount = subs,
            hiddenSubscriberCount = hiddenSubs,
            views = v.views,
            channelMedianViews = baseline.median,
            channelTrimmedMeanViews = baseline.trimmedMean.roundToLong(),
            channelP90Views = baseline.p90,
            outlierMultiplier = multiplier,
            viewsPerSub = OutlierCalculator.calculateViewsPerSub(v.views, subs),
            likes = v.likes,
            comments = v.comments,
            durationSeconds = v.durationSeconds,
            publishedAt = v.publishedAt,
            othersAbove5xCount = MedianCalculator.countAboveMultiplier(
              baselineViews, baseline.median, 5.0
            ),
            othersAbove10xCount = MedianCalculator.countAboveMultiplier(
              baselineViews, baseline.median, 10.0
            ),
            shortStatus = if (isUush) ShortStatus.CONFIRMED_SHORT else ShortStatus.UNVERIFIED,
            isSaved = savedDao.isSaved(v.videoId),
            isOneHitWonder = OutlierCalculator.isOneHitWonder(
              multiplier,
              MedianCalculator.countAboveMultiplier(baselineViews, baseline.median, 5.0)
            )
          )
        )
      } else {
        nearMisses.add(
          NearMissVideo(
            videoId = v.videoId,
            title = v.title,
            thumbnailUrl = v.thumbnailUrl,
            channelId = channelId,
            channelTitle = channelTitle,
            views = v.views,
            subscriberCount = subs,
            hiddenSubscriberCount = hiddenSubs,
            medianViews = baseline.median,
            multiplier = multiplier,
            reasonChip = "Multiplier ${OutlierCalculator.formatMultiplier(multiplier)}x < " +
              "${OutlierCalculator.formatMultiplier(outlierThreshold)}x",
            stagePriority = 4
          )
        )
      }
    }

    val finalized = DiscoveryHelpers.flagReuploads(
      outliers.sortedByDescending { it.outlierMultiplier }
    )
    val topNearMisses = nearMisses
      .sortedWith(
        compareByDescending<NearMissVideo> { it.multiplier ?: 0.0 }
          .thenByDescending { it.views }
      )
      .take(10)

    val stats = DebugStats(
      searchReturned = playlistVideoIds.size,
      pages = 0,
      afterDurationFilter = durationFiltered.size,
      afterMinViewsFilter = durationFiltered.size,
      uniqueChannels = 1,
      afterSubsFilter = 1,
      channelsPassingMedian = 1,
      uushCount = if (isUush) 1 else 0,
      uploadsFallbackCount = if (isUush) 0 else 1,
      finalOutliers = finalized.size,
      verifiedShorts = if (isUush) finalized.size else 0,
      searchCallsCost = 0,
      videosListCost = videosCost,
      channelsListCost = 1,
      perChannelCost = playlistCost,
      mainTotalCost = 1 + playlistCost + videosCost,
      afterExcludeFilter = excludeFiltered.size,
      afterLikesRatioFilter = likesFiltered.size,
      afterViralAgeFilter = ageFiltered.size,
      reuploadFlagged = finalized.count { it.isPossibleReupload }
    )

    writeCachedResult(cacheKey, finalized, topNearMisses, stats, null)

    return SearchResult.Success(
      outliers = finalized,
      nearMisses = topNearMisses,
      debugStats = stats,
      nextPageToken = null,
      isCacheHit = false
    )
  }

  private suspend fun resolveChannelItem(
    target: ChannelInputParser.ChannelTarget,
    key: ActiveKey
  ): ChannelItem? {
    suspend fun request(kind: ChannelInputParser.Kind, value: String): Response<ChannelListResponse> {
      return executeWithRetry {
        when (kind) {
          ChannelInputParser.Kind.ID -> youTubeApi.getChannels(ids = value, apiKey = key.key)
          ChannelInputParser.Kind.HANDLE -> youTubeApi.getChannelByHandle(
            handle = "@$value",
            apiKey = key.key
          )
          ChannelInputParser.Kind.USERNAME -> youTubeApi.getChannelByUsername(
            username = value,
            apiKey = key.key
          )
        }
      }
    }

    val response = request(target.kind, target.value)
    val error = handleResponse(response, key, QuotaBucket.MAIN)
    if (error != null && (error.isQuotaExceeded || error.isKeyInvalid)) {
      throw AbortSearchException(error)
    }
    response.body()?.items?.firstOrNull()?.let { return it }

    // Legacy /c/ or /user/ names may only resolve through forUsername.
    if (target.kind == ChannelInputParser.Kind.HANDLE) {
      val fallback = executeWithRetry {
        youTubeApi.getChannelByUsername(username = target.value, apiKey = key.key)
      }
      val fallbackError = handleResponse(fallback, key, QuotaBucket.MAIN)
      if (fallbackError != null && (fallbackError.isQuotaExceeded || fallbackError.isKeyInvalid)) {
        throw AbortSearchException(fallbackError)
      }
      fallback.body()?.items?.firstOrNull()?.let { return it }
    }
    return null
  }

  private suspend fun fetchPlaylistItems(
    playlistId: String,
    maxResults: Int,
    key: ActiveKey,
    onUnitsUsed: (Int) -> Unit
  ): List<PlaylistItem> {
    val response = executeWithRetry {
      youTubeApi.getPlaylistItems(
        playlistId = playlistId,
        maxResults = maxResults,
        apiKey = key.key
      )
    }
    val error = handleResponse(response, key, QuotaBucket.MAIN)
    onUnitsUsed(1)
    if (error != null && (error.isQuotaExceeded || error.isKeyInvalid)) {
      throw AbortSearchException(error)
    }
    if (error != null) return emptyList()
    return response.body()?.items ?: emptyList()
  }

  private suspend fun readCachedResult(cacheKey: String): SearchResult.Success? {
    val cached = searchCacheDao.getCache(cacheKey) ?: return null
    if (!CacheTtl.isFresh(System.currentTimeMillis(), cached.timestamp, CacheTtl.SEARCH_RESULTS_MS)) {
      return null
    }
    return try {
      val cachedResults = outlierListAdapter.fromJson(cached.resultsJson) ?: emptyList()
      val cachedNearMisses =
        runCatching { nearMissListAdapter.fromJson(cached.nearMissesJson) }.getOrNull()
          ?: emptyList()
      val cachedStats = debugStatsAdapter.fromJson(cached.debugStatsJson) ?: DebugStats()
      SearchResult.Success(
        outliers = cachedResults.map { it.copy(isSaved = savedDao.isSaved(it.videoId)) },
        nearMisses = cachedNearMisses,
        debugStats = cachedStats.copy(cacheHits = cachedStats.cacheHits + 1),
        nextPageToken = cached.nextPageToken,
        isCacheHit = true
      )
    } catch (_: Exception) {
      null
    }
  }

  private suspend fun writeCachedResult(
    cacheKey: String,
    outliers: List<OutlierVideo>,
    nearMisses: List<NearMissVideo>,
    stats: DebugStats,
    nextPageToken: String?
  ) {
    try {
      searchCacheDao.insertCache(
        SearchCacheEntity(
          cacheKey = cacheKey,
          timestamp = System.currentTimeMillis(),
          nextPageToken = nextPageToken,
          resultsJson = outlierListAdapter.toJson(outliers),
          debugStatsJson = debugStatsAdapter.toJson(stats),
          nearMissesJson = nearMissListAdapter.toJson(nearMisses)
        )
      )
    } catch (_: Exception) {
    }
  }

  private fun sumStats(a: DebugStats, b: DebugStats): DebugStats = DebugStats(
    searchReturned = a.searchReturned + b.searchReturned,
    pages = a.pages + b.pages,
    afterDurationFilter = a.afterDurationFilter + b.afterDurationFilter,
    afterMinViewsFilter = a.afterMinViewsFilter + b.afterMinViewsFilter,
    uniqueChannels = a.uniqueChannels + b.uniqueChannels,
    afterSubsFilter = a.afterSubsFilter + b.afterSubsFilter,
    channelsWithTooFewShorts = a.channelsWithTooFewShorts + b.channelsWithTooFewShorts,
    channelsMedianTooHigh = a.channelsMedianTooHigh + b.channelsMedianTooHigh,
    channelsPassingMedian = a.channelsPassingMedian + b.channelsPassingMedian,
    uushCount = a.uushCount + b.uushCount,
    uploadsFallbackCount = a.uploadsFallbackCount + b.uploadsFallbackCount,
    finalOutliers = a.finalOutliers + b.finalOutliers,
    verifiedShorts = a.verifiedShorts + b.verifiedShorts,
    searchCallsCost = a.searchCallsCost + b.searchCallsCost,
    videosListCost = a.videosListCost + b.videosListCost,
    channelsListCost = a.channelsListCost + b.channelsListCost,
    perChannelCost = a.perChannelCost + b.perChannelCost,
    mainTotalCost = a.mainTotalCost + b.mainTotalCost,
    cacheHits = a.cacheHits + b.cacheHits,
    negativeCacheHits = a.negativeCacheHits + b.negativeCacheHits,
    videoStatsCacheHits = a.videoStatsCacheHits + b.videoStatsCacheHits,
    medianSkippedByCost = a.medianSkippedByCost + b.medianSkippedByCost,
    afterExcludeFilter = a.afterExcludeFilter + b.afterExcludeFilter,
    afterLikesRatioFilter = a.afterLikesRatioFilter + b.afterLikesRatioFilter,
    afterViralAgeFilter = a.afterViralAgeFilter + b.afterViralAgeFilter,
    reuploadFlagged = a.reuploadFlagged + b.reuploadFlagged
  )

  private suspend fun searchWithKey(
    cleanFilters: FilterOptions,
    pageToken: String?,
    cacheKey: String,
    key: ActiveKey,
    confirmCost: suspend (CostEstimate) -> Boolean
  ): SearchResult {
    _progressFlow.value = "YouTube search shuru ho rahi hai..."
    var currentSearchCalls = 0
    var videosListCost = 0
    var channelsListCost = 0
    var perChannelCost = 0
    var videoStatsCacheHits = 0

    // Step 1: search.list
    val collectedVideoIds = mutableListOf<String>()
    var curToken: String? = pageToken
    var finalNextPageToken: String? = null

    val publishedAfter = computePublishedAfterRfc(
      DiscoveryHelpers.effectiveRecencyDays(cleanFilters.recency, cleanFilters.maxViralAgeDays)
    )
    val region = if (cleanFilters.regionCode.equals("Any", ignoreCase = true)) null else cleanFilters.regionCode
    val relLang = when (cleanFilters.language.lowercase(Locale.ROOT)) {
      "hindi" -> "hi"
      "english" -> "en"
      else -> null
    }

    for (page in 1..cleanFilters.pagesPerSearch) {
      _progressFlow.value = "Search kar rahe hain (Page $page/${cleanFilters.pagesPerSearch})..."
      val response = executeWithRetry {
        youTubeApi.searchVideos(
          query = cleanFilters.keyword,
          pageToken = curToken,
          publishedAfter = publishedAfter,
          regionCode = region,
          relevanceLanguage = relLang,
          apiKey = key.key
        )
      }

      val error = handleResponse(response, key, QuotaBucket.SEARCH)
      if (error != null) return error
      currentSearchCalls++

      val searchBody = response.body()
      val items = searchBody?.items ?: emptyList()
      items.forEach { item ->
        item.id?.videoId?.let { vid ->
          if (!collectedVideoIds.contains(vid)) {
            collectedVideoIds.add(vid)
          }
        }
      }

      finalNextPageToken = searchBody?.nextPageToken
      curToken = finalNextPageToken
      if (curToken.isNullOrBlank()) break
    }

    val searchReturnedCount = collectedVideoIds.size

    if (collectedVideoIds.isEmpty()) {
      val emptyStats = DebugStats(
        searchReturned = 0,
        pages = currentSearchCalls,
        searchCallsCost = currentSearchCalls
      )
      return SearchResult.Success(emptyList(), emptyList(), emptyStats, null, false)
    }

    // Step 2: videos.list in chunks of 50, backed by the 6h video-stat cache.
    _progressFlow.value = "Videos ki details fetch ho rahi hain..."
    val videoDetails = fetchVideoDetails(
      videoIds = collectedVideoIds,
      key = key,
      onUnitsUsed = { videosListCost += it },
      onCacheHit = { videoStatsCacheHits += it }
    )

    val allFetchedVideos = videoDetails.values.map { v ->
      RawVideoCandidate(
        videoId = v.videoId,
        title = v.title,
        description = v.description,
        tags = parseTags(v.tagsJson),
        thumbnailUrl = v.thumbnailUrl,
        channelId = v.channelId,
        channelTitle = v.channelTitle,
        views = v.views,
        likes = v.likes,
        comments = v.comments,
        durationSeconds = v.durationSeconds,
        publishedAt = v.publishedAt
      )
    }

    // 2a: duration check first
    val durationFilteredVideos =
      allFetchedVideos.filter { it.durationSeconds <= cleanFilters.maxDurationSeconds }
    val afterDurationCount = durationFilteredVideos.size

    // 2b: minViews check second
    val validCandidateVideos = durationFilteredVideos.filter { it.views >= cleanFilters.minViralViews }
    val afterMinViewsCount = validCandidateVideos.size

    // 2c: exclude keywords (before any channel units are spent)
    val excludes = DiscoveryHelpers.parseExcludeKeywords(cleanFilters.excludeKeywords)
    val excludeFilteredVideos = validCandidateVideos.filter {
      !DiscoveryHelpers.matchesExcludeKeyword(it.title, it.tags, excludes)
    }
    val afterExcludeCount = excludeFilteredVideos.size

    // 2d: minimum likes/views ratio. Hidden like counts are rejected when requested.
    val likesFilteredVideos = excludeFilteredVideos.filter {
      DiscoveryHelpers.passesLikesRatio(it.likes, it.views, cleanFilters.minLikesRatio)
    }
    val afterLikesCount = likesFilteredVideos.size

    // 2e: the viral Short itself must be fresh (e.g. viral in the last 7/30 days)
    val ageFilteredVideos = likesFilteredVideos.filter {
      DiscoveryHelpers.isWithinViralAge(it.publishedAt, cleanFilters.maxViralAgeDays)
    }
    val afterAgeCount = ageFilteredVideos.size

    val uniqueChannels = ageFilteredVideos.map { it.channelId }.distinct()

    // Step 3: channels.list (subscriber data cached for 24h, negative results included)
    _progressFlow.value = "Channels ka subscriber check ho raha hai (${uniqueChannels.size} channels)..."
    val now = System.currentTimeMillis()
    val cachedChannels = if (uniqueChannels.isEmpty()) {
      emptyMap()
    } else {
      channelCacheDao.getChannels(uniqueChannels).associateBy { it.channelId }
    }
    val channelDataMap = mutableMapOf<String, ChannelStatsHolder>()
    val channelsToFetch = mutableListOf<String>()

    for (chId in uniqueChannels) {
      val cached = cachedChannels[chId]
      if (cached != null && CacheTtl.isFresh(now, cached.subsCachedAt, CacheTtl.SUBS_MS)) {
        channelDataMap[chId] = cached.toHolder()
      } else {
        channelsToFetch.add(chId)
      }
    }

    if (channelsToFetch.isNotEmpty()) {
      for (chunk in channelsToFetch.chunked(50)) {
        val response = executeWithRetry {
          youTubeApi.getChannels(
            ids = chunk.joinToString(","),
            apiKey = key.key
          )
        }
        val error = handleResponse(response, key, QuotaBucket.MAIN)
        if (error != null) return error
        channelsListCost++

        val returnedIds = mutableSetOf<String>()
        val chItems = response.body()?.items ?: emptyList()
        for (cItem in chItems) {
          val subs = cItem.statistics?.subscriberCount?.toLongOrNull() ?: 0L
          val hiddenSubs = cItem.statistics?.hiddenSubscriberCount ?: false
          val uploads = cItem.contentDetails?.relatedPlaylists?.uploads
          returnedIds.add(cItem.id)

          val previous = cachedChannels[cItem.id]
          channelDataMap[cItem.id] = ChannelStatsHolder(
            subscriberCount = subs,
            hasHiddenSubscribers = hiddenSubs,
            uploadsPlaylistId = uploads,
            cachedStatus = previous?.status ?: ChannelCacheStatus.PENDING,
            cachedMedianAt = previous?.medianCachedAt ?: 0L,
            cachedMedian = previous?.medianViews ?: 0L,
            cachedShortCount = previous?.shortCount ?: 0,
            cachedSource = previous?.source ?: "",
            cachedTrimmedMean = previous?.trimmedMeanViews ?: 0L,
            cachedP90 = previous?.p90Views ?: 0L,
            cachedAbove5xCount = previous?.othersAbove5xCount ?: 0,
            cachedAbove10xCount = previous?.othersAbove10xCount ?: 0
          )

          channelCacheDao.insertIfAbsent(cItem.id, subs, hiddenSubs, uploads, now)
          channelCacheDao.updateSubscriberInfo(cItem.id, subs, hiddenSubs, uploads, now)
          if (hiddenSubs) {
            channelCacheDao.updateNegativeStatus(cItem.id, ChannelCacheStatus.HIDDEN_SUBS, now)
          }
        }

        // Channels that channels.list did not return no longer exist (or are private).
        for (chId in chunk) {
          if (chId !in returnedIds) {
            channelCacheDao.insertIfAbsent(chId, 0L, false, null, now)
            channelCacheDao.updateSubscriberInfo(chId, 0L, false, null, now)
            channelCacheDao.updateNegativeStatus(chId, ChannelCacheStatus.NO_CHANNEL, now)
          }
        }
      }
    }

    // Filter channels by subs & hiddenSubs
    val passingChannelIds = mutableSetOf<String>()
    val isFullRangeSubs = cleanFilters.minSubs <= 0L && cleanFilters.maxSubs >= 100_000L

    for (chId in uniqueChannels) {
      val chInfo = channelDataMap[chId] ?: continue
      if (chInfo.hasHiddenSubscribers && !isFullRangeSubs) {
        continue // skip hidden subs when not full range
      }
      if (chInfo.subscriberCount in cleanFilters.minSubs..cleanFilters.maxSubs) {
        passingChannelIds.add(chId)
      }
    }

    val afterSubsFilterCount = passingChannelIds.size

    // Step 4: compute the baseline per channel, respecting the split TTLs.
    var uushCount = 0
    var uploadsFallbackCount = 0
    var channelsWithTooFewShortsCount = 0
    var channelsMedianTooHighCount = 0
    var channelsPassingMedianCount = 0
    var channelCacheHits = 0
    var negativeCacheHits = 0

    val channelMedianResults = mutableMapOf<String, ChannelMedianResult>()
    val skippedByCostIds = mutableSetOf<String>()

    fun applyBaseline(chId: String, baseline: ChannelMedianResult) {
      synchronized(channelMedianResults) {
        channelMedianResults[chId] = baseline
        if (baseline.source == "UPLOADS") uploadsFallbackCount++ else uushCount++
        if (baseline.median <= cleanFilters.maxMedianViews) {
          channelsPassingMedianCount++
        } else {
          channelsMedianTooHighCount++
        }
      }
    }

    val pendingChannels = mutableListOf<String>()
    for (chId in passingChannelIds) {
      val info = channelDataMap[chId] ?: continue
      val medianFresh = CacheTtl.isFresh(now, info.cachedMedianAt, CacheTtl.CHANNEL_MEDIAN_MS)
      val negativeFresh =
        CacheTtl.isFresh(now, info.cachedMedianAt, CacheTtl.NEGATIVE_STATUS_MS)

      when {
        info.cachedStatus == ChannelCacheStatus.OK &&
          info.cachedMedian > 0 &&
          medianFresh -> {
          channelCacheHits++
          if (info.cachedShortCount < MedianCalculator.MIN_SAMPLES) {
            channelsWithTooFewShortsCount++
          } else {
            applyBaseline(
              chId,
              ChannelMedianResult(
                median = info.cachedMedian,
                trimmedMean = info.cachedTrimmedMean,
                p90 = info.cachedP90,
                shortCount = info.cachedShortCount,
                othersAbove5xCount = info.cachedAbove5xCount,
                othersAbove10xCount = info.cachedAbove10xCount,
                source = info.cachedSource.ifBlank { "UUSH" }
              )
            )
          }
        }
        info.cachedStatus.isNegative() && negativeFresh -> {
          negativeCacheHits++
          if (info.cachedStatus == ChannelCacheStatus.TOO_FEW_SHORTS) {
            channelsWithTooFewShortsCount++
          }
        }
        else -> pendingChannels.add(chId)
      }
    }

    // Show the estimated cost before spending units on new channels.
    val remainingUnits = quotaTracker.getRemainingMainUnits(key.fingerprint)
    val estimate = CostEstimator.estimate(
      uncachedChannels = pendingChannels.size,
      cachedChannels = passingChannelIds.size - pendingChannels.size,
      remainingUnits = remainingUnits
    )
    val approved = confirmCost(estimate)
    val channelsToProcess = if (approved) pendingChannels else emptyList()
    if (!approved) {
      skippedByCostIds.addAll(pendingChannels)
    }

    _progressFlow.value = "Channels check ho rahe hain 0/${channelsToProcess.size}..."
    val channelSemaphore = Semaphore(3)
    var processedChannelsCount = 0
    val abortRef = AtomicReference<SearchResult.Error?>(null)

    coroutineScope {
      val jobs = channelsToProcess.map { chId ->
        async {
          channelSemaphore.withPermit {
            if (abortRef.get() != null) return@withPermit

            val viralVideoIdsFromThisChannel = ageFilteredVideos
              .filter { it.channelId == chId }
              .map { it.videoId }
              .toSet()

            val chInfo = channelDataMap[chId]
            val fetched = try {
              fetchChannelMedian(
                channelId = chId,
                uploadsPlaylistId = chInfo?.uploadsPlaylistId,
                viralVideoIds = viralVideoIdsFromThisChannel,
                maxDurationSeconds = cleanFilters.maxDurationSeconds,
                sampleSize = cleanFilters.samplePerChannel,
                minBaselineAgeHours = cleanFilters.minBaselineAgeHours,
                key = key,
                onUnitsUsed = { units ->
                  synchronized(this@OutlierRepository) { perChannelCost += units }
                },
                onVideoCacheHit = { count ->
                  synchronized(this@OutlierRepository) { videoStatsCacheHits += count }
                }
              )
            } catch (e: AbortSearchException) {
              abortRef.compareAndSet(null, e.error)
              return@withPermit
            } catch (e: CancellationException) {
              throw e
            } catch (_: Exception) {
              null
            }

            if (fetched != null) {
              when (fetched) {
                is MedianFetchResult.Success -> {
                  channelCacheDao.updateBaseline(
                    channelId = chId,
                    status = ChannelCacheStatus.OK,
                    medianViews = fetched.baseline.median,
                    shortCount = fetched.baseline.shortCount,
                    source = fetched.baseline.source,
                    trimmedMeanViews = fetched.baseline.trimmedMean,
                    p90Views = fetched.baseline.p90,
                    othersAbove5xCount = fetched.baseline.othersAbove5xCount,
                    othersAbove10xCount = fetched.baseline.othersAbove10xCount,
                    now = System.currentTimeMillis()
                  )
                  applyBaseline(chId, fetched.baseline)
                }
                MedianFetchResult.TooFewShorts -> {
                  channelCacheDao.updateBaseline(
                    channelId = chId,
                    status = ChannelCacheStatus.TOO_FEW_SHORTS,
                    medianViews = 0L,
                    shortCount = 0,
                    source = "",
                    trimmedMeanViews = 0L,
                    p90Views = 0L,
                    othersAbove5xCount = 0,
                    othersAbove10xCount = 0,
                    now = System.currentTimeMillis()
                  )
                  synchronized(this@OutlierRepository) { channelsWithTooFewShortsCount++ }
                }
                MedianFetchResult.PlaylistUnavailable -> {
                  channelCacheDao.updateBaseline(
                    channelId = chId,
                    status = ChannelCacheStatus.PLAYLIST_404,
                    medianViews = 0L,
                    shortCount = 0,
                    source = "",
                    trimmedMeanViews = 0L,
                    p90Views = 0L,
                    othersAbove5xCount = 0,
                    othersAbove10xCount = 0,
                    now = System.currentTimeMillis()
                  )
                }
              }
            }

            synchronized(this@OutlierRepository) {
              processedChannelsCount++
              _progressFlow.value =
                "Channels check ho rahe hain $processedChannelsCount/${channelsToProcess.size}..."
            }
          }
        }
      }
      jobs.awaitAll()
    }

    abortRef.get()?.let { throw AbortSearchException(it) }

    // Step 5: metrics, minMultiplier filter, near misses (0 extra API units)
    val finalCandidates = mutableListOf<OutlierVideo>()
    val rejectedCandidates = mutableListOf<NearMissVideo>()

    for (v in ageFilteredVideos) {
      val chInfo = channelDataMap[v.channelId]
      val subs = chInfo?.subscriberCount ?: 0L
      val hiddenSubs = chInfo?.hasHiddenSubscribers ?: false
      val medianInfo = channelMedianResults[v.channelId]
      val multiplier = if (medianInfo != null) {
        OutlierCalculator.calculateMultiplier(v.views, medianInfo.median)
      } else {
        null
      }

      val rejectionReason = if (medianInfo == null && v.channelId in skippedByCostIds) {
        "Median skipped (cost)"
      } else {
        OutlierCalculator.buildNearMissReason(
          subscriberCount = subs,
          hiddenSubscribers = hiddenSubs,
          shortCount = medianInfo?.shortCount,
          medianViews = medianInfo?.median,
          multiplier = multiplier,
          filters = cleanFilters
        )
      }

      if (rejectionReason != null) {
        val stagePriority = when {
          rejectionReason.startsWith("Multiplier") -> 4
          rejectionReason.startsWith("Median") -> 3
          rejectionReason == "<5 Shorts on channel" -> 2
          else -> 1 // Subs filter / hidden / cost skip
        }
        rejectedCandidates.add(
          NearMissVideo(
            videoId = v.videoId,
            title = v.title,
            thumbnailUrl = v.thumbnailUrl,
            channelId = v.channelId,
            channelTitle = v.channelTitle,
            views = v.views,
            subscriberCount = subs,
            hiddenSubscriberCount = hiddenSubs,
            medianViews = medianInfo?.median,
            multiplier = multiplier,
            reasonChip = rejectionReason,
            stagePriority = stagePriority
          )
        )
        continue
      }

      val validBaseline = medianInfo!!
      val validMedian = validBaseline.median
      val validMultiplier = multiplier!!
      val viewsPerSub = OutlierCalculator.calculateViewsPerSub(v.views, subs)
      val isSaved = savedDao.isSaved(v.videoId)
      val oneHitWonder = OutlierCalculator.isOneHitWonder(
        multiplier = validMultiplier,
        othersAbove5xCount = validBaseline.othersAbove5xCount
      )

      finalCandidates.add(
        OutlierVideo(
          videoId = v.videoId,
          title = v.title,
          description = v.description,
          tags = v.tags,
          thumbnailUrl = v.thumbnailUrl,
          channelId = v.channelId,
          channelTitle = v.channelTitle,
          subscriberCount = subs,
          hiddenSubscriberCount = hiddenSubs,
          views = v.views,
          channelMedianViews = validMedian,
          channelTrimmedMeanViews = validBaseline.trimmedMean,
          channelP90Views = validBaseline.p90,
          outlierMultiplier = validMultiplier,
          viewsPerSub = viewsPerSub,
          likes = v.likes,
          comments = v.comments,
          durationSeconds = v.durationSeconds,
          publishedAt = v.publishedAt,
          othersAbove5xCount = validBaseline.othersAbove5xCount,
          othersAbove10xCount = validBaseline.othersAbove10xCount,
          shortStatus = ShortStatus.UNVERIFIED,
          isSaved = isSaved,
          isOneHitWonder = oneHitWonder
        )
      )
    }

    // Sort near misses best first (furthest pipeline stage -> highest multiplier -> highest views), up to 10
    val topNearMisses = rejectedCandidates
      .sortedWith(
        compareByDescending<NearMissVideo> { it.stagePriority }
          .thenByDescending { it.multiplier ?: 0.0 }
          .thenByDescending { it.views }
      )
      .take(10)

    // Step 6: Short verification (final candidates only, max 30, Semaphore(5))
    _progressFlow.value = "Shorts verify ho rahe hain..."
    val verifyCandidates = finalCandidates.take(30)
    val verifiedOutliers = mutableListOf<OutlierVideo>()
    val verifySemaphore = Semaphore(5)

    var verifiedShortsCount = 0
    var verifiedIndex = 0

    coroutineScope {
      val verifyJobs = verifyCandidates.map { cand ->
        async {
          verifySemaphore.withPermit {
            val cachedVerification = verificationCacheDao.getVerification(cand.videoId)
            val status = when {
              cachedVerification != null -> {
                if (cachedVerification.isUnverified) ShortStatus.UNVERIFIED
                else if (cachedVerification.isShort) ShortStatus.CONFIRMED_SHORT
                else ShortStatus.NOT_A_SHORT
              }
              else -> {
                val verified = shortsVerifier.verifyShort(cand.videoId)
                verificationCacheDao.insertVerification(
                  ShortVerificationCacheEntity(
                    videoId = cand.videoId,
                    isShort = verified == ShortStatus.CONFIRMED_SHORT,
                    isUnverified = verified == ShortStatus.UNVERIFIED,
                    timestamp = System.currentTimeMillis()
                  )
                )
                verified
              }
            }

            synchronized(verifiedOutliers) {
              if (status == ShortStatus.CONFIRMED_SHORT) verifiedShortsCount++
              verifiedIndex++
              _progressFlow.value = "Shorts verify ho rahe hain $verifiedIndex/${verifyCandidates.size}..."
              verifiedOutliers.add(cand.copy(shortStatus = status))
            }
          }
        }
      }
      verifyJobs.awaitAll()
    }

    // Include any remaining candidates beyond 30 as UNVERIFIED
    if (finalCandidates.size > 30) {
      verifiedOutliers.addAll(finalCandidates.drop(30))
    }

    // Re-upload dedupe: later copies of the same Short keep a flag and point at the original.
    val finalizedOutliers = DiscoveryHelpers.flagReuploads(verifiedOutliers)
    val reuploadCount = finalizedOutliers.count { it.isPossibleReupload }

    val mainTotalCost = videosListCost + channelsListCost + perChannelCost

    val debugStats = DebugStats(
      searchReturned = searchReturnedCount,
      pages = currentSearchCalls,
      afterDurationFilter = afterDurationCount,
      afterMinViewsFilter = afterMinViewsCount,
      uniqueChannels = uniqueChannels.size,
      afterSubsFilter = afterSubsFilterCount,
      channelsWithTooFewShorts = channelsWithTooFewShortsCount,
      channelsMedianTooHigh = channelsMedianTooHighCount,
      channelsPassingMedian = channelsPassingMedianCount,
      uushCount = uushCount,
      uploadsFallbackCount = uploadsFallbackCount,
      finalOutliers = finalizedOutliers.size,
      verifiedShorts = verifiedShortsCount,
      searchCallsCost = currentSearchCalls,
      videosListCost = videosListCost,
      channelsListCost = channelsListCost,
      perChannelCost = perChannelCost,
      mainTotalCost = mainTotalCost,
      cacheHits = channelCacheHits,
      negativeCacheHits = negativeCacheHits,
      videoStatsCacheHits = videoStatsCacheHits,
      medianSkippedByCost = skippedByCostIds.size,
      afterExcludeFilter = afterExcludeCount,
      afterLikesRatioFilter = afterLikesCount,
      afterViralAgeFilter = afterAgeCount,
      reuploadFlagged = reuploadCount
    )

    // Step 7: save to Room search cache (including near misses)
    try {
      searchCacheDao.insertCache(
        SearchCacheEntity(
          cacheKey = cacheKey,
          timestamp = System.currentTimeMillis(),
          nextPageToken = finalNextPageToken,
          resultsJson = outlierListAdapter.toJson(finalizedOutliers),
          debugStatsJson = debugStatsAdapter.toJson(debugStats),
          nearMissesJson = nearMissListAdapter.toJson(topNearMisses)
        )
      )
    } catch (_: Exception) {
    }

    return SearchResult.Success(
      outliers = finalizedOutliers,
      nearMisses = topNearMisses,
      debugStats = debugStats,
      nextPageToken = finalNextPageToken,
      isCacheHit = false
    )
  }

  /**
   * Returns video details for [videoIds], using cached rows younger than 6h and
   * fetching only the stale/missing ones. Throws [AbortSearchException] on a
   * quota or key error so the caller can rotate the key.
   */
  private suspend fun fetchVideoDetails(
    videoIds: List<String>,
    key: ActiveKey,
    onUnitsUsed: (Int) -> Unit = {},
    onCacheHit: (Int) -> Unit = {}
  ): Map<String, VideoStatsCacheEntity> {
    if (videoIds.isEmpty()) return emptyMap()

    val now = System.currentTimeMillis()
    val cached = videoStatsDao.getVideos(videoIds).associateBy { it.videoId }
    val freshIds = cached.values
      .filter { CacheTtl.isFresh(now, it.timestamp, CacheTtl.VIDEO_STATS_MS) }
      .map { it.videoId }
      .toSet()

    val result = cached.filterKeys { it in freshIds }.toMutableMap()
    onCacheHit(freshIds.size)

    val missing = videoIds.filter { it !in freshIds }
    for (chunk in missing.chunked(50)) {
      val response = executeWithRetry {
        youTubeApi.getVideos(
          ids = chunk.joinToString(","),
          apiKey = key.key
        )
      }
      val error = handleResponse(response, key, QuotaBucket.MAIN)
      if (error != null) throw AbortSearchException(error)
      onUnitsUsed(1)

      val entities = (response.body()?.items ?: emptyList()).map { item ->
        VideoStatsCacheEntity(
          videoId = item.id,
          title = item.snippet?.title ?: "",
          description = item.snippet?.description ?: "",
          tagsJson = stringListAdapter.toJson(item.snippet?.tags ?: emptyList()),
          thumbnailUrl = item.snippet?.thumbnails?.bestUrl() ?: "",
          channelId = item.snippet?.channelId ?: "",
          channelTitle = item.snippet?.channelTitle ?: "",
          views = item.statistics?.viewCount?.toLongOrNull() ?: 0L,
          likes = item.statistics?.likeCount?.toLongOrNull() ?: 0L,
          comments = item.statistics?.commentCount?.toLongOrNull() ?: 0L,
          durationSeconds = DurationParser.parseToSeconds(item.contentDetails?.duration),
          publishedAt = item.snippet?.publishedAt ?: "",
          timestamp = now
        )
      }
      if (entities.isNotEmpty()) {
        videoStatsDao.insertVideos(entities)
      }
      entities.forEach { result[it.videoId] = it }
    }
    return result
  }

  private suspend fun fetchChannelMedian(
    channelId: String,
    uploadsPlaylistId: String?,
    viralVideoIds: Set<String>,
    maxDurationSeconds: Long,
    sampleSize: Int,
    minBaselineAgeHours: Int,
    key: ActiveKey,
    onUnitsUsed: (Int) -> Unit,
    onVideoCacheHit: (Int) -> Unit
  ): MedianFetchResult {
    val now = Instant.now()
    val uushId = UushHelper.getShortsPlaylistId(channelId)
    var isUush = true

    // Try UUSH first. part=contentDetails,snippet gives us publish dates (still 1 unit).
    var playlistResponse = executeWithRetry {
      youTubeApi.getPlaylistItems(
        playlistId = uushId,
        maxResults = sampleSize,
        apiKey = key.key
      )
    }
    val uushError = handleResponse(playlistResponse, key, QuotaBucket.MAIN)
    if (uushError != null && (uushError.isQuotaExceeded || uushError.isKeyInvalid)) {
      throw AbortSearchException(uushError)
    }
    // A non-fatal error still reached Google and was billed, so count it too.
    onUnitsUsed(1)

    var items = playlistResponse.body()?.items ?: emptyList()
    var videoIdsToFetch = pickMatureVideoIds(
      items = items,
      viralVideoIds = viralVideoIds,
      minBaselineAgeHours = minBaselineAgeHours,
      now = now,
      take = sampleSize
    )

    if (uushError != null || videoIdsToFetch.size < MedianCalculator.MIN_SAMPLES) {
      // Fallback to the uploads playlist when UUSH fails or has fewer than 5 mature videos.
      isUush = false
      val fallbackUploadsId = uploadsPlaylistId
        ?: (if (channelId.startsWith("UC")) "UU" + channelId.drop(2) else null)
        ?: return MedianFetchResult.PlaylistUnavailable

      playlistResponse = executeWithRetry {
        youTubeApi.getPlaylistItems(
          playlistId = fallbackUploadsId,
          maxResults = sampleSize,
          apiKey = key.key
        )
      }
      val fallbackError = handleResponse(playlistResponse, key, QuotaBucket.MAIN)
      if (fallbackError != null && (fallbackError.isQuotaExceeded || fallbackError.isKeyInvalid)) {
        throw AbortSearchException(fallbackError)
      }
      onUnitsUsed(1)
      if (fallbackError != null) return MedianFetchResult.PlaylistUnavailable

      items = playlistResponse.body()?.items ?: emptyList()
      if (items.isEmpty()) return MedianFetchResult.PlaylistUnavailable

      videoIdsToFetch = pickMatureVideoIds(
        items = items,
        viralVideoIds = viralVideoIds,
        minBaselineAgeHours = minBaselineAgeHours,
        now = now,
        take = sampleSize
      )
    }

    if (videoIdsToFetch.size < MedianCalculator.MIN_SAMPLES) {
      return MedianFetchResult.TooFewShorts
    }

    val details = fetchVideoDetails(
      videoIds = videoIdsToFetch,
      key = key,
      onUnitsUsed = onUnitsUsed,
      onCacheHit = onVideoCacheHit
    )

    val baselineViews = mutableListOf<Long>()
    for (v in details.values) {
      // If we fell back to uploads, filter by duration <= maxDurationSeconds
      if (!isUush && v.durationSeconds > maxDurationSeconds) continue
      // Re-check maturity against the video's own publish date (authoritative)
      if (!MedianCalculator.isMatureEnough(v.publishedAt, minBaselineAgeHours, now)) continue
      baselineViews.add(v.views)
    }

    if (baselineViews.size < MedianCalculator.MIN_SAMPLES) {
      return MedianFetchResult.TooFewShorts
    }

    val stats = MedianCalculator.calculateBaseline(baselineViews)
      ?: return MedianFetchResult.TooFewShorts

    return MedianFetchResult.Success(
      ChannelMedianResult(
        median = stats.median,
        trimmedMean = stats.trimmedMean.roundToLong(),
        p90 = stats.p90,
        shortCount = stats.sampleSize,
        othersAbove5xCount = MedianCalculator.countAboveMultiplier(baselineViews, stats.median, 5.0),
        othersAbove10xCount = MedianCalculator.countAboveMultiplier(baselineViews, stats.median, 10.0),
        source = if (isUush) "UUSH" else "UPLOADS"
      )
    )
  }

  /**
   * Records quota for [response] on the bucket it belongs to and returns a
   * user-facing error when the call failed.
   */
  private suspend fun handleResponse(
    response: Response<*>,
    key: ActiveKey,
    bucket: QuotaBucket
  ): SearchResult.Error? {
    if (response.isSuccessful) {
      quotaTracker.record(key.fingerprint, bucket, 1)
      return null
    }

    val errorBody = response.errorBody()?.string()
    val error = buildApiError(response.code(), errorBody, bucket)
    when {
      error.isKeyInvalid -> Unit // invalid keys do not consume quota
      error.isQuotaExceeded -> quotaTracker.markExceeded(key.fingerprint, bucket)
      else -> quotaTracker.record(key.fingerprint, bucket, 1)
    }
    return error
  }

  private fun buildApiError(
    statusCode: Int,
    errorBody: String?,
    bucket: QuotaBucket
  ): SearchResult.Error {
    val looksLikeBadKey = errorBody?.contains("keyInvalid", ignoreCase = true) == true ||
      errorBody?.contains("API key not valid", ignoreCase = true) == true ||
      errorBody?.contains("invalid API key", ignoreCase = true) == true
    if (looksLikeBadKey || (statusCode == 400 && errorBody.isNullOrBlank())) {
      return SearchResult.Error("Key kaam nahi kar rahi, nayi key daalo", isKeyInvalid = true)
    }
    if (statusCode == 400) {
      // e.g. an unknown @handle reaches the API as a bad request; do not discard the key.
      return SearchResult.Error("YouTube request valid nahi thi (HTTP 400). Input check karo.")
    }
    return when (statusCode) {
      403 -> {
        if (
          errorBody?.contains("quotaExceeded", ignoreCase = true) == true ||
          errorBody?.contains("dailyLimitExceeded", ignoreCase = true) == true
        ) {
          SearchResult.Error(
            "Aaj ka YouTube API quota khatam ho gaya hai.",
            isQuotaExceeded = true,
            failedBucket = bucket
          )
        } else if (errorBody?.contains("SERVICE_DISABLED", ignoreCase = true) == true ||
          errorBody?.contains("accessNotConfigured", ignoreCase = true) == true
        ) {
          SearchResult.Error("Google Cloud me 'YouTube Data API v3' enable karo.")
        } else if (errorBody?.contains("API_KEY_SERVICE_BLOCKED", ignoreCase = true) == true ||
          errorBody?.contains("restricted", ignoreCase = true) == true
        ) {
          SearchResult.Error("Key restriction me YouTube Data API v3 allow karo.")
        } else {
          SearchResult.Error("YouTube API 403 Forbidden: Kripya credentials aur restrictions check karein.")
        }
      }
      else -> SearchResult.Error("YouTube API error (HTTP $statusCode)")
    }
  }

  private fun parseTags(tagsJson: String): List<String> {
    return runCatching { stringListAdapter.fromJson(tagsJson) }.getOrNull() ?: emptyList()
  }

  /**
   * Keeps only videos old enough for their views to be a fair baseline sample.
   * Fresh Shorts (< minBaselineAgeHours) are dropped so a just-posted spike is not
   * counted as "usual" performance. Missing dates are treated as mature.
   */
  private fun pickMatureVideoIds(
    items: List<PlaylistItem>,
    viralVideoIds: Set<String>,
    minBaselineAgeHours: Int,
    now: Instant,
    take: Int
  ): List<String> {
    return items.mapNotNull { item ->
      val contentDetails = item.contentDetails ?: return@mapNotNull null
      val videoId = contentDetails.videoId ?: return@mapNotNull null
      if (viralVideoIds.contains(videoId)) return@mapNotNull null
      val publishedAt = contentDetails.bestPublishedAt(item.snippet?.publishedAt).orEmpty()
      if (!MedianCalculator.isMatureEnough(publishedAt, minBaselineAgeHours, now)) return@mapNotNull null
      videoId
    }.take(take)
  }

  private fun computePublishedAfterRfc(days: Int?): String? {
    if (days == null || days <= 0) return null
    val targetInstant = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
    return DateTimeFormatter.ISO_INSTANT.format(targetInstant)
  }

  private suspend fun <T> executeWithRetry(
    maxRetries: Int = 1,
    initialDelayMs: Long = 1000L,
    block: suspend () -> Response<T>
  ): Response<T> {
    var delayMs = initialDelayMs
    var attempts = 0
    while (true) {
      try {
        val res = block()
        if (res.isSuccessful || res.code() in 400..499 || attempts >= maxRetries) {
          return res
        }
        attempts++
        delay(delayMs)
        delayMs *= 2
      } catch (e: IOException) {
        if (attempts >= maxRetries) throw e
        attempts++
        delay(delayMs)
        delayMs *= 2
      }
    }
  }
}

private sealed interface MedianFetchResult {
  data class Success(val baseline: ChannelMedianResult) : MedianFetchResult
  data object TooFewShorts : MedianFetchResult
  data object PlaylistUnavailable : MedianFetchResult
}

private data class RawVideoCandidate(
  val videoId: String,
  val title: String,
  val description: String,
  val tags: List<String>,
  val thumbnailUrl: String,
  val channelId: String,
  val channelTitle: String,
  val views: Long,
  val likes: Long,
  val comments: Long,
  val durationSeconds: Long,
  val publishedAt: String
)

private data class ChannelStatsHolder(
  val subscriberCount: Long,
  val hasHiddenSubscribers: Boolean,
  val uploadsPlaylistId: String?,
  val cachedStatus: ChannelCacheStatus = ChannelCacheStatus.PENDING,
  val cachedMedianAt: Long = 0L,
  val cachedMedian: Long = 0L,
  val cachedShortCount: Int = 0,
  val cachedSource: String = "",
  val cachedTrimmedMean: Long = 0L,
  val cachedP90: Long = 0L,
  val cachedAbove5xCount: Int = 0,
  val cachedAbove10xCount: Int = 0
)

private data class ChannelMedianResult(
  val median: Long,
  val trimmedMean: Long,
  val p90: Long,
  val shortCount: Int,
  val othersAbove5xCount: Int,
  val othersAbove10xCount: Int,
  val source: String
)

private fun ChannelCacheEntity.toHolder(): ChannelStatsHolder = ChannelStatsHolder(
  subscriberCount = subscriberCount,
  hasHiddenSubscribers = hasHiddenSubscribers,
  uploadsPlaylistId = uploadsPlaylistId,
  cachedStatus = status,
  cachedMedianAt = medianCachedAt,
  cachedMedian = medianViews,
  cachedShortCount = shortCount,
  cachedSource = source,
  cachedTrimmedMean = trimmedMeanViews,
  cachedP90 = p90Views,
  cachedAbove5xCount = othersAbove5xCount,
  cachedAbove10xCount = othersAbove10xCount
)

private fun ChannelCacheStatus.isNegative(): Boolean =
  this == ChannelCacheStatus.TOO_FEW_SHORTS ||
    this == ChannelCacheStatus.PLAYLIST_404 ||
    this == ChannelCacheStatus.NO_CHANNEL
