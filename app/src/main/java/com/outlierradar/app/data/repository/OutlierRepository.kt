package com.outlierradar.app.data.repository

import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.ChannelCacheEntity
import com.outlierradar.app.data.local.ChannelCacheStatus
import com.outlierradar.app.data.local.SearchCacheEntity
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.local.ShortVerificationCacheEntity
import com.outlierradar.app.data.local.VideoStatsCacheEntity
import com.outlierradar.app.data.remote.ChannelItem
import com.outlierradar.app.data.remote.ChannelListResponse
import com.outlierradar.app.data.remote.PlaylistItem
import com.outlierradar.app.data.remote.ShortsVerifier
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.domain.CacheTtl
import com.outlierradar.app.domain.ChannelInputParser
import com.outlierradar.app.domain.CostEstimate
import com.outlierradar.app.domain.CostEstimator
import com.outlierradar.app.domain.DebugStats
import com.outlierradar.app.domain.DiscoveryCacheKey
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.DiscoveryMode
import com.outlierradar.app.domain.DurationParser
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.MedianCalculator
import com.outlierradar.app.domain.NearMissVideo
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShortStatus
import com.outlierradar.app.domain.UushHelper
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.outlierradar.app.data.repository.usecase.ClassifyOutliersUseCase
import com.outlierradar.app.data.repository.usecase.ComputeBaselineUseCase
import com.outlierradar.app.data.repository.usecase.FetchChannelStatsUseCase
import com.outlierradar.app.data.repository.usecase.SearchCandidatesUseCase
import com.outlierradar.app.data.repository.usecase.VerifyShortsUseCase
import com.outlierradar.app.data.repository.usecase.AbortBaselineException
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
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

  /**
   * Lazily verifies a Short on demand (used for items beyond the initial 30).
   * Uses `short_verification_cache` and is safe to call repeatedly/concurrently.
   */
  suspend fun verifyShortOnDemand(videoId: String): ShortStatus {
    if (videoId.isBlank()) return ShortStatus.UNVERIFIED
    val cached = verificationCacheDao.getVerification(videoId)
    if (cached != null) {
      return when {
        cached.isUnverified -> ShortStatus.UNVERIFIED
        cached.isShort -> ShortStatus.CONFIRMED_SHORT
        else -> ShortStatus.NOT_A_SHORT
      }
    }
    val verified = shortsVerifier.verifyShort(videoId)
    try {
      verificationCacheDao.insertVerification(
        ShortVerificationCacheEntity(
          videoId = videoId,
          isShort = verified == ShortStatus.CONFIRMED_SHORT,
          isUnverified = verified == ShortStatus.UNVERIFIED,
          timestamp = System.currentTimeMillis()
        )
      )
    } catch (_: Exception) {
    }
    return verified
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

    // 1) Search candidates (search.list + videos.list/details + filtering)
    val searchCandidates = SearchCandidatesUseCase(youTubeApi)
    val candidatesOutcome = searchCandidates.execute(
      filters = cleanFilters,
      pageToken = pageToken,
      key = key,
      progressFlow = _progressFlow,
      executeWithRetry = { block -> executeWithRetry { block() } },
      handleResponse = { resp, k, bucket -> handleResponse(resp, k, bucket) },
      fetchVideoDetails = { ids, k, onUnits, onCacheHit -> fetchVideoDetails(ids, k, onUnits, onCacheHit) }
    )

    val candidates = when (candidatesOutcome) {
      is com.outlierradar.app.data.repository.usecase.SearchCandidatesUseCase.CandidatesOutcome.Error ->
        return candidatesOutcome.error
      is com.outlierradar.app.data.repository.usecase.SearchCandidatesUseCase.CandidatesOutcome.Empty -> {
        val emptyStats = DebugStats(
          searchReturned = 0,
          pages = candidatesOutcome.pages,
          searchCallsCost = candidatesOutcome.pages
        )
        return SearchResult.Success(emptyList(), emptyList(), emptyStats, null, false)
      }
      is com.outlierradar.app.data.repository.usecase.SearchCandidatesUseCase.CandidatesOutcome.Success ->
        candidatesOutcome.result
    }

    // 2) Fetch channel stats/subscribers
    val channelStatsUseCase = FetchChannelStatsUseCase(youTubeApi, channelCacheDao)
    val channelStatsOutcome = channelStatsUseCase.execute(
      filters = cleanFilters,
      uniqueChannels = candidates.uniqueChannels,
      key = key,
      progressFlow = _progressFlow,
      executeWithRetry = { block -> executeWithRetry { block() } },
      handleResponse = { resp, k, bucket -> handleResponse(resp, k, bucket) }
    )
    val channelStats = when (channelStatsOutcome) {
      is com.outlierradar.app.data.repository.usecase.FetchChannelStatsUseCase.Outcome.Error -> return channelStatsOutcome.error
      is com.outlierradar.app.data.repository.usecase.FetchChannelStatsUseCase.Outcome.Success -> channelStatsOutcome.result
    }

    // 3) Compute per-channel baselines (median)
    val baselineUseCase = ComputeBaselineUseCase(channelCacheDao, quotaTracker)
    val baselineOutcome = baselineUseCase.execute(
      filters = cleanFilters,
      ageFilteredVideos = candidates.ageFilteredVideos,
      channelDataMap = channelStats.channelDataMap,
      passingChannelIds = channelStats.passingChannelIds,
      key = key,
      progressFlow = _progressFlow,
      confirmCost = confirmCost,
      fetchMedian = { channelId, uploadsPlaylistId, viralVideoIds, onUnits, onCacheHit ->
        try {
          val fetched = fetchChannelMedian(
            channelId = channelId,
            uploadsPlaylistId = uploadsPlaylistId,
            viralVideoIds = viralVideoIds,
            maxDurationSeconds = cleanFilters.maxDurationSeconds,
            sampleSize = cleanFilters.samplePerChannel,
            minBaselineAgeHours = cleanFilters.minBaselineAgeHours,
            key = key,
            onUnitsUsed = onUnits,
            onVideoCacheHit = onCacheHit
          )
          when (fetched) {
            is MedianFetchResult.Success -> com.outlierradar.app.data.repository.usecase.MedianFetchResult.Success(
              com.outlierradar.app.data.repository.usecase.ChannelMedianResult(
                median = fetched.baseline.median,
                trimmedMean = fetched.baseline.trimmedMean,
                p90 = fetched.baseline.p90,
                shortCount = fetched.baseline.shortCount,
                othersAbove5xCount = fetched.baseline.othersAbove5xCount,
                othersAbove10xCount = fetched.baseline.othersAbove10xCount,
                source = fetched.baseline.source
              )
            )
            MedianFetchResult.TooFewShorts -> com.outlierradar.app.data.repository.usecase.MedianFetchResult.TooFewShorts
            MedianFetchResult.PlaylistUnavailable -> com.outlierradar.app.data.repository.usecase.MedianFetchResult.PlaylistUnavailable
          }
        } catch (e: AbortSearchException) {
          throw AbortBaselineException(e.error)
        }
      }
    )
    val baseline = when (baselineOutcome) {
      is com.outlierradar.app.data.repository.usecase.ComputeBaselineUseCase.Outcome.Abort -> throw AbortSearchException(baselineOutcome.error)
      is com.outlierradar.app.data.repository.usecase.ComputeBaselineUseCase.Outcome.Success -> baselineOutcome.result
    }

    // 4) Classify outliers vs near misses
    val classifyUseCase = ClassifyOutliersUseCase(savedDao)
    val classification = classifyUseCase.execute(
      ageFilteredVideos = candidates.ageFilteredVideos,
      channelDataMap = channelStats.channelDataMap,
      channelMedianResults = baseline.channelMedianResults,
      skippedByCostIds = baseline.skippedByCostIds,
      filters = cleanFilters
    )

    // 5) Verify Shorts (top candidates)
    val verifyUseCase = VerifyShortsUseCase(shortsVerifier, verificationCacheDao)
    val verification = verifyUseCase.execute(
      finalCandidates = classification.finalCandidates,
      progressFlow = _progressFlow
    )

    val videosListCost = candidates.videosListCost
    val perChannelCost = baseline.perChannelCost
    val channelsListCost = channelStats.channelsListCost
    val mainTotalCost = videosListCost + channelsListCost + perChannelCost

    val debugStats = DebugStats(
      searchReturned = candidates.searchReturnedCount,
      pages = candidates.currentSearchCalls,
      afterDurationFilter = candidates.afterDurationCount,
      afterMinViewsFilter = candidates.afterMinViewsCount,
      uniqueChannels = candidates.uniqueChannels.size,
      afterSubsFilter = channelStats.afterSubsFilterCount,
      channelsWithTooFewShorts = baseline.channelsWithTooFewShortsCount,
      channelsMedianTooHigh = baseline.channelsMedianTooHighCount,
      channelsPassingMedian = baseline.channelsPassingMedianCount,
      uushCount = baseline.uushCount,
      uploadsFallbackCount = baseline.uploadsFallbackCount,
      finalOutliers = verification.finalizedOutliers.size,
      verifiedShorts = verification.verifiedShortsCount,
      searchCallsCost = candidates.currentSearchCalls,
      videosListCost = videosListCost,
      channelsListCost = channelsListCost,
      perChannelCost = perChannelCost,
      mainTotalCost = mainTotalCost,
      cacheHits = baseline.channelCacheHits,
      negativeCacheHits = baseline.negativeCacheHits,
      videoStatsCacheHits = candidates.videoStatsCacheHits + baseline.videoStatsCacheHits,
      medianSkippedByCost = baseline.skippedByCostIds.size,
      afterExcludeFilter = candidates.afterExcludeCount,
      afterLikesRatioFilter = candidates.afterLikesCount,
      afterViralAgeFilter = candidates.afterAgeCount,
      reuploadFlagged = verification.reuploadCount
    )

    try {
      searchCacheDao.insertCache(
        SearchCacheEntity(
          cacheKey = cacheKey,
          timestamp = System.currentTimeMillis(),
          nextPageToken = candidates.nextPageToken,
          resultsJson = outlierListAdapter.toJson(verification.finalizedOutliers),
          debugStatsJson = debugStatsAdapter.toJson(debugStats),
          nearMissesJson = nearMissListAdapter.toJson(classification.topNearMisses)
        )
      )
    } catch (_: Exception) {
    }

    return SearchResult.Success(
      outliers = verification.finalizedOutliers,
      nearMisses = classification.topNearMisses,
      debugStats = debugStats,
      nextPageToken = candidates.nextPageToken,
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
