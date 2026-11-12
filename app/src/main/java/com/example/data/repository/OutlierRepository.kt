package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.ChannelCacheEntity
import com.example.data.local.SearchCacheEntity
import com.example.data.local.SettingsStore
import com.example.data.local.ShortVerificationCacheEntity
import com.example.data.remote.ShortsVerifier
import com.example.data.remote.YouTubeApi
import com.example.domain.DebugStats
import com.example.domain.DurationParser
import com.example.domain.FilterOptions
import com.example.domain.MedianCalculator
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import com.example.domain.UushHelper
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

sealed class SearchResult {
  data class Success(
    val outliers: List<OutlierVideo>,
    val debugStats: DebugStats,
    val nextPageToken: String?,
    val isCacheHit: Boolean
  ) : SearchResult()

  data class Error(
    val message: String,
    val isKeyInvalid: Boolean = false,
    val isQuotaExceeded: Boolean = false
  ) : SearchResult()
}

class OutlierRepository(
  private val youTubeApi: YouTubeApi,
  private val database: AppDatabase,
  private val settingsStore: SettingsStore,
  private val quotaTracker: QuotaTracker,
  private val shortsVerifier: ShortsVerifier
) {
  private val searchCacheDao = database.searchCacheDao()
  private val channelCacheDao = database.channelCacheDao()
  private val verificationCacheDao = database.shortVerificationDao()
  private val savedDao = database.savedOutlierDao()

  private val _progressFlow = MutableStateFlow<String?>(null)
  val progressFlow: StateFlow<String?> = _progressFlow.asStateFlow()

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val listType = Types.newParameterizedType(List::class.java, OutlierVideo::class.java)
  private val outlierListAdapter = moshi.adapter<List<OutlierVideo>>(listType)
  private val debugStatsAdapter = moshi.adapter(DebugStats::class.java)

  companion object {
    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
  }

  fun createCacheKey(filters: FilterOptions, pageToken: String? = null): String {
    val normKw = filters.keyword.trim().lowercase(Locale.ROOT)
    return "kw=$normKw|p=${filters.pagesPerSearch}|subs=${filters.minSubs}-${filters.maxSubs}|views=${filters.minViralViews}|med=${filters.maxMedianViews}|mul=${filters.minMultiplier}|rec=${filters.recency}|reg=${filters.regionCode}|lang=${filters.language}|dur=${filters.maxDurationSeconds}|token=${pageToken ?: ""}"
  }

  suspend fun searchOutliers(
    filters: FilterOptions,
    pageToken: String? = null,
    forceRefresh: Boolean = false
  ): SearchResult = withContext(Dispatchers.IO) {
    val apiKey = settingsStore.getDecryptedApiKey()
    if (apiKey.isNullOrBlank()) {
      return@withContext SearchResult.Error("API key nahi mili. Kripya pehle apni key daalein.", isKeyInvalid = true)
    }

    val cacheKey = createCacheKey(filters, pageToken)

    if (!forceRefresh) {
      val cached = searchCacheDao.getCache(cacheKey)
      if (cached != null && (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS)) {
        try {
          val cachedResults = outlierListAdapter.fromJson(cached.resultsJson) ?: emptyList()
          val cachedStats = debugStatsAdapter.fromJson(cached.debugStatsJson) ?: DebugStats()
          // Update isSaved state dynamically
          val updatedWithSaved = cachedResults.map { it.copy(isSaved = savedDao.isSaved(it.videoId)) }
          return@withContext SearchResult.Success(
            outliers = updatedWithSaved,
            debugStats = cachedStats.copy(cacheHits = cachedStats.cacheHits + 1),
            nextPageToken = cached.nextPageToken,
            isCacheHit = true
          )
        } catch (_: Exception) {
          // If cache deserialization fails, continue with fresh search
        }
      }
    }

    // Estimate cost: pagesPerSearch search calls + estimated main units
    val quotaCheck = quotaTracker.canAffordSearch(
      pages = filters.pagesPerSearch,
      estimatedUnits = filters.pagesPerSearch + 1 + 20 // rough initial estimate
    )
    if (quotaCheck is QuotaCheckResult.Blocked) {
      return@withContext SearchResult.Error(quotaCheck.reason, isQuotaExceeded = true)
    }

    try {
      _progressFlow.value = "YouTube search shuru ho rahi hai..."
      var currentSearchCalls = 0
      var videosListCost = 0
      var channelsListCost = 0
      var perChannelCost = 0

      // Step 1: search.list
      val collectedVideoIds = mutableListOf<String>()
      var curToken: String? = pageToken
      var finalNextPageToken: String? = null

      val publishedAfter = computePublishedAfterRfc(filters.recency)
      val region = if (filters.regionCode.equals("Any", ignoreCase = true)) null else filters.regionCode
      val relLang = when (filters.language.lowercase(Locale.ROOT)) {
        "hindi" -> "hi"
        "english" -> "en"
        else -> null
      }

      for (page in 1..filters.pagesPerSearch) {
        _progressFlow.value = "Search kar rahe hain (Page $page/${filters.pagesPerSearch})..."
        val response = executeWithRetry {
          youTubeApi.searchVideos(
            query = filters.keyword,
            pageToken = curToken,
            publishedAfter = publishedAfter,
            regionCode = region,
            relevanceLanguage = relLang,
            apiKey = apiKey
          )
        }

        quotaTracker.recordSearchCall(1)
        currentSearchCalls++

        if (!response.isSuccessful) {
          return@withContext handleApiError(response.code(), response.errorBody()?.string())
        }

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
        val emptyStats = DebugStats(searchReturned = 0, pages = currentSearchCalls, searchCallsCost = currentSearchCalls)
        return@withContext SearchResult.Success(emptyList(), emptyStats, null, false)
      }

      // Step 2: videos.list in chunks of 50
      _progressFlow.value = "Videos ki details fetch ho rahi hain..."
      val validCandidateVideos = mutableListOf<RawVideoCandidate>()
      var afterDurationCount = 0

      val videoChunks = collectedVideoIds.chunked(50)
      for (chunk in videoChunks) {
        val vResponse = executeWithRetry {
          youTubeApi.getVideos(
            ids = chunk.joinToString(","),
            apiKey = apiKey
          )
        }
        quotaTracker.recordMainUnits(1)
        videosListCost++

        if (!vResponse.isSuccessful) {
          return@withContext handleApiError(vResponse.code(), vResponse.errorBody()?.string())
        }

        val vItems = vResponse.body()?.items ?: emptyList()
        for (item in vItems) {
          val durStr = item.contentDetails?.duration
          val durSec = DurationParser.parseToSeconds(durStr)
          val views = item.statistics?.viewCount?.toLongOrNull() ?: 0L
          val channelId = item.snippet?.channelId ?: continue

          if (durSec <= filters.maxDurationSeconds) {
            afterDurationCount++
            if (views >= filters.minViralViews) {
              validCandidateVideos.add(
                RawVideoCandidate(
                  videoId = item.id,
                  title = item.snippet.title ?: "",
                  description = item.snippet.description ?: "",
                  tags = item.snippet.tags ?: emptyList(),
                  thumbnailUrl = item.snippet.thumbnails?.bestUrl() ?: "",
                  channelId = channelId,
                  channelTitle = item.snippet.channelTitle ?: "",
                  views = views,
                  likes = item.statistics?.likeCount?.toLongOrNull() ?: 0L,
                  comments = item.statistics?.commentCount?.toLongOrNull() ?: 0L,
                  durationSeconds = durSec,
                  publishedAt = item.snippet.publishedAt ?: ""
                )
              )
            }
          }
        }
      }

      val afterMinViewsCount = validCandidateVideos.size
      val uniqueChannels = validCandidateVideos.map { it.channelId }.distinct()

      // Step 3: channels.list in chunks of 50
      _progressFlow.value = "Channels ka subscriber check ho raha hai (${uniqueChannels.size} channels)..."
      val channelDataMap = mutableMapOf<String, ChannelStatsHolder>()
      val channelsToFetch = mutableListOf<String>()

      // Check Room channel cache (24h) first
      for (chId in uniqueChannels) {
        val cachedCh = channelCacheDao.getChannel(chId)
        if (cachedCh != null && (System.currentTimeMillis() - cachedCh.timestamp < CACHE_TTL_MS)) {
          channelDataMap[chId] = ChannelStatsHolder(
            subscriberCount = cachedCh.subscriberCount,
            hasHiddenSubscribers = cachedCh.hasHiddenSubscribers,
            uploadsPlaylistId = null,
            cachedMedian = cachedCh.medianViews,
            cachedShortCount = cachedCh.shortCount,
            cachedSource = cachedCh.source
          )
        } else {
          channelsToFetch.add(chId)
        }
      }

      if (channelsToFetch.isNotEmpty()) {
        for (chunk in channelsToFetch.chunked(50)) {
          val chResponse = executeWithRetry {
            youTubeApi.getChannels(
              ids = chunk.joinToString(","),
              apiKey = apiKey
            )
          }
          quotaTracker.recordMainUnits(1)
          channelsListCost++

          if (!chResponse.isSuccessful) {
            return@withContext handleApiError(chResponse.code(), chResponse.errorBody()?.string())
          }

          val chItems = chResponse.body()?.items ?: emptyList()
          for (cItem in chItems) {
            val subs = cItem.statistics?.subscriberCount?.toLongOrNull() ?: 0L
            val hiddenSubs = cItem.statistics?.hiddenSubscriberCount ?: false
            val uploads = cItem.contentDetails?.relatedPlaylists?.uploads
            channelDataMap[cItem.id] = ChannelStatsHolder(
              subscriberCount = subs,
              hasHiddenSubscribers = hiddenSubs,
              uploadsPlaylistId = uploads
            )
          }
        }
      }

      // Filter channels by subs & hiddenSubs
      val passingChannelIds = mutableSetOf<String>()
      val isFullRangeSubs = filters.minSubs <= 0L && filters.maxSubs >= 100_000L

      for (chId in uniqueChannels) {
        val chInfo = channelDataMap[chId] ?: continue
        if (chInfo.hasHiddenSubscribers && !isFullRangeSubs) {
          continue // skip hidden subs when not full range
        }
        if (chInfo.subscriberCount in filters.minSubs..filters.maxSubs) {
          passingChannelIds.add(chId)
        }
      }

      val afterSubsFilterCount = passingChannelIds.size

      // Step 4: Compute median per channel with Semaphore(3)
      _progressFlow.value = "Channels check ho rahe hain 0/${passingChannelIds.size}..."
      val channelMedianResults = mutableMapOf<String, ChannelMedianResult>()
      var uushCount = 0
      var uploadsFallbackCount = 0
      var channelsPassingMedianCount = 0
      var channelCacheHits = 0

      val channelSemaphore = Semaphore(3)
      var processedChannelsCount = 0

      coroutineScope {
        val jobs = passingChannelIds.map { chId ->
          async {
            channelSemaphore.withPermit {
              val viralVideoIdsFromThisChannel = validCandidateVideos
                .filter { it.channelId == chId }
                .map { it.videoId }
                .toSet()

              val chInfo = channelDataMap[chId]

              // Check if we already have valid cached median
              if (chInfo?.cachedMedian != null && chInfo.cachedShortCount != null && chInfo.cachedMedian > 0) {
                synchronized(channelMedianResults) {
                  channelCacheHits++
                  channelMedianResults[chId] = ChannelMedianResult(
                    median = chInfo.cachedMedian,
                    shortCount = chInfo.cachedShortCount,
                    source = chInfo.cachedSource ?: "UUSH"
                  )
                  if (chInfo.cachedSource == "UPLOADS") uploadsFallbackCount++ else uushCount++
                  if (chInfo.cachedMedian <= filters.maxMedianViews) {
                    channelsPassingMedianCount++
                  }
                }
              } else {
                // S4 a, b, c: fetch median wrapped in try/catch so one channel failing skips it without crashing
                val medianResult = try {
                  fetchChannelMedian(
                    channelId = chId,
                    uploadsPlaylistId = chInfo?.uploadsPlaylistId,
                    viralVideoIds = viralVideoIdsFromThisChannel,
                    maxDurationSeconds = filters.maxDurationSeconds,
                    sampleSize = filters.samplePerChannel,
                    apiKey = apiKey,
                    onUnitsUsed = { units ->
                      synchronized(this@OutlierRepository) {
                        perChannelCost += units
                      }
                    }
                  )
                } catch (_: Exception) {
                  null
                }

                if (medianResult != null) {
                  // Cache per channel for 24h
                  channelCacheDao.insertChannel(
                    ChannelCacheEntity(
                      channelId = chId,
                      subscriberCount = chInfo?.subscriberCount ?: 0L,
                      hasHiddenSubscribers = chInfo?.hasHiddenSubscribers ?: false,
                      medianViews = medianResult.median,
                      shortCount = medianResult.shortCount,
                      source = medianResult.source,
                      timestamp = System.currentTimeMillis()
                    )
                  )

                  synchronized(channelMedianResults) {
                    channelMedianResults[chId] = medianResult
                    if (medianResult.source == "UPLOADS") uploadsFallbackCount++ else uushCount++
                    if (medianResult.median <= filters.maxMedianViews) {
                      channelsPassingMedianCount++
                    }
                  }
                }
              }

              synchronized(this@OutlierRepository) {
                processedChannelsCount++
                _progressFlow.value = "Channels check ho rahe hain $processedChannelsCount/${passingChannelIds.size}..."
              }
            }
          }
        }
        jobs.awaitAll()
      }

      // Step 5: Compute metrics and filter by minMultiplier
      val finalCandidates = mutableListOf<OutlierVideo>()
      for (v in validCandidateVideos) {
        val medianInfo = channelMedianResults[v.channelId] ?: continue
        if (medianInfo.median > filters.maxMedianViews) continue

        val multiplier = OutlierCalculator.calculateMultiplier(v.views, medianInfo.median)
        if (multiplier < filters.minMultiplier) continue

        val chInfo = channelDataMap[v.channelId]
        val subs = chInfo?.subscriberCount ?: 0L
        val viewsPerSub = OutlierCalculator.calculateViewsPerSub(v.views, subs)
        val isSaved = savedDao.isSaved(v.videoId)

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
            hiddenSubscriberCount = chInfo?.hasHiddenSubscribers ?: false,
            views = v.views,
            channelMedianViews = medianInfo.median,
            outlierMultiplier = multiplier,
            viewsPerSub = viewsPerSub,
            likes = v.likes,
            comments = v.comments,
            durationSeconds = v.durationSeconds,
            publishedAt = v.publishedAt,
            shortStatus = ShortStatus.UNVERIFIED,
            isSaved = isSaved
          )
        )
      }

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
              // Check verification cache first
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

      val mainTotalCost = videosListCost + channelsListCost + perChannelCost

      val debugStats = DebugStats(
        searchReturned = searchReturnedCount,
        pages = currentSearchCalls,
        afterDurationFilter = afterDurationCount,
        afterMinViewsFilter = afterMinViewsCount,
        uniqueChannels = uniqueChannels.size,
        afterSubsFilter = afterSubsFilterCount,
        channelsPassingMedian = channelsPassingMedianCount,
        uushCount = uushCount,
        uploadsFallbackCount = uploadsFallbackCount,
        finalOutliers = verifiedOutliers.size,
        verifiedShorts = verifiedShortsCount,
        searchCallsCost = currentSearchCalls,
        videosListCost = videosListCost,
        channelsListCost = channelsListCost,
        perChannelCost = perChannelCost,
        mainTotalCost = mainTotalCost,
        cacheHits = channelCacheHits
      )

      // Step 7: Save to Room search cache
      try {
        searchCacheDao.insertCache(
          SearchCacheEntity(
            cacheKey = cacheKey,
            timestamp = System.currentTimeMillis(),
            nextPageToken = finalNextPageToken,
            resultsJson = outlierListAdapter.toJson(verifiedOutliers),
            debugStatsJson = debugStatsAdapter.toJson(debugStats)
          )
        )
      } catch (_: Exception) {
      }

      _progressFlow.value = null
      return@withContext SearchResult.Success(
        outliers = verifiedOutliers,
        debugStats = debugStats,
        nextPageToken = finalNextPageToken,
        isCacheHit = false
      )
    } catch (e: IOException) {
      _progressFlow.value = null
      return@withContext SearchResult.Error("Internet check karo")
    } catch (e: Exception) {
      _progressFlow.value = null
      val safeMsg = (e.message ?: "Search me anjaan khamiyan aayi").replace(apiKey, "[REDACTED]")
      return@withContext SearchResult.Error(safeMsg)
    }
  }

  private suspend fun fetchChannelMedian(
    channelId: String,
    uploadsPlaylistId: String?,
    viralVideoIds: Set<String>,
    maxDurationSeconds: Long,
    sampleSize: Int,
    apiKey: String,
    onUnitsUsed: (Int) -> Unit
  ): ChannelMedianResult? {
    val uushId = UushHelper.getShortsPlaylistId(channelId)
    var isUush = true

    // Try UUSH first
    var playlistResponse = executeWithRetry {
      youTubeApi.getPlaylistItems(
        playlistId = uushId,
        maxResults = sampleSize,
        apiKey = apiKey
      )
    }
    quotaTracker.recordMainUnits(1)
    onUnitsUsed(1)

    var items = playlistResponse.body()?.items ?: emptyList()

    if (!playlistResponse.isSuccessful || items.isEmpty()) {
      // Fallback to uploads playlist
      isUush = false
      val fallbackUploadsId = uploadsPlaylistId
        ?: (if (channelId.startsWith("UC")) "UU" + channelId.drop(2) else null)

      if (fallbackUploadsId == null) return null

      playlistResponse = executeWithRetry {
        youTubeApi.getPlaylistItems(
          playlistId = fallbackUploadsId,
          maxResults = sampleSize,
          apiKey = apiKey
        )
      }
      quotaTracker.recordMainUnits(1)
      onUnitsUsed(1)

      items = playlistResponse.body()?.items ?: emptyList()
      if (!playlistResponse.isSuccessful || items.isEmpty()) return null
    }

    // Extract video IDs excluding viral video(s)
    val videoIdsToFetch = items.mapNotNull { it.contentDetails?.videoId }
      .filter { !viralVideoIds.contains(it) }
      .take(sampleSize)

    if (videoIdsToFetch.size < 5) return null

    // Call videos.list to get statistics & contentDetails (1 unit)
    val vResponse = executeWithRetry {
      youTubeApi.getVideos(
        ids = videoIdsToFetch.joinToString(","),
        apiKey = apiKey
      )
    }
    quotaTracker.recordMainUnits(1)
    onUnitsUsed(1)

    val vItems = vResponse.body()?.items ?: emptyList()
    val otherShortViews = mutableListOf<Long>()

    for (v in vItems) {
      val dur = DurationParser.parseToSeconds(v.contentDetails?.duration)
      // If we fell back to uploads, filter by duration <= maxDurationSeconds
      if (isUush || dur <= maxDurationSeconds) {
        val views = v.statistics?.viewCount?.toLongOrNull() ?: 0L
        otherShortViews.add(views)
      }
    }

    if (otherShortViews.size < 5) return null

    val median = MedianCalculator.calculateMedian(otherShortViews) ?: return null

    return ChannelMedianResult(
      median = median,
      shortCount = otherShortViews.size,
      source = if (isUush) "UUSH" else "UPLOADS"
    )
  }

  private fun computePublishedAfterRfc(recency: String): String? {
    if (recency.equals("any", ignoreCase = true)) return null
    val now = Instant.now()
    val targetInstant = when (recency.lowercase(Locale.ROOT)) {
      "7d" -> now.minus(7, ChronoUnit.DAYS)
      "30d" -> now.minus(30, ChronoUnit.DAYS)
      "90d" -> now.minus(90, ChronoUnit.DAYS)
      "6mo" -> now.minus(180, ChronoUnit.DAYS)
      "1y" -> now.minus(365, ChronoUnit.DAYS)
      else -> now.minus(90, ChronoUnit.DAYS)
    }
    return DateTimeFormatter.ISO_INSTANT.format(targetInstant)
  }

  private suspend fun handleApiError(statusCode: Int, errorBody: String?): SearchResult.Error {
    if (statusCode == 400 || errorBody?.contains("keyInvalid", ignoreCase = true) == true) {
      settingsStore.clearApiKey()
      return SearchResult.Error("Key kaam nahi kar rahi, nayi key daalo", isKeyInvalid = true)
    }
    return when (statusCode) {
      403 -> {
        if (errorBody?.contains("quotaExceeded", ignoreCase = true) == true) {
          quotaTracker.markSearchExceeded()
          quotaTracker.markMainExceeded()
          SearchResult.Error("Aaj ka YouTube API quota khatam ho gaya hai.", isQuotaExceeded = true)
        } else if (errorBody?.contains("SERVICE_DISABLED", ignoreCase = true) == true || errorBody?.contains("accessNotConfigured", ignoreCase = true) == true) {
          SearchResult.Error("Google Cloud me 'YouTube Data API v3' enable karo.")
        } else if (errorBody?.contains("API_KEY_SERVICE_BLOCKED", ignoreCase = true) == true || errorBody?.contains("restricted", ignoreCase = true) == true) {
          SearchResult.Error("Key restriction me YouTube Data API v3 allow karo.")
        } else {
          SearchResult.Error("YouTube API 403 Forbidden: Kripya credentials aur restrictions check karein.")
        }
      }
      else -> SearchResult.Error("YouTube API error (HTTP $statusCode)")
    }
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
  val cachedMedian: Long? = null,
  val cachedShortCount: Int? = null,
  val cachedSource: String? = null
)

private data class ChannelMedianResult(
  val median: Long,
  val shortCount: Int,
  val source: String
)
