package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.ChannelCacheDao
import com.outlierradar.app.data.repository.ActiveKey
import com.outlierradar.app.data.repository.QuotaTracker
import com.outlierradar.app.data.repository.SearchResult
import com.outlierradar.app.domain.CacheTtl
import com.outlierradar.app.domain.CostEstimator
import com.outlierradar.app.domain.FilterOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ComputeBaselineUseCase(
  private val channelCacheDao: ChannelCacheDao,
  private val quotaTracker: QuotaTracker
) {
  suspend fun execute(
    filters: FilterOptions,
    ageFilteredVideos: List<RawVideoCandidate>,
    channelDataMap: Map<String, ChannelStatsHolder>,
    passingChannelIds: Set<String>,
    key: ActiveKey,
    progressFlow: MutableStateFlow<String?>,
    confirmCost: suspend (com.outlierradar.app.domain.CostEstimate) -> Boolean,
    fetchMedian: suspend (
      channelId: String,
      uploadsPlaylistId: String?,
      viralVideoIds: Set<String>,
      onUnits: (Int) -> Unit,
      onCacheHit: (Int) -> Unit
    ) -> MedianFetchResult?
  ): Outcome {
    val now = System.currentTimeMillis()
    val uushCount = AtomicInteger(0)
    val uploadsFallbackCount = AtomicInteger(0)
    val channelsWithTooFewShortsCount = AtomicInteger(0)
    val channelsMedianTooHighCount = AtomicInteger(0)
    val channelsPassingMedianCount = AtomicInteger(0)
    var channelCacheHits = 0
    var negativeCacheHits = 0

    val channelMedianResults = ConcurrentHashMap<String, ChannelMedianResult>()
    val baselineMutex = Mutex()
    val skippedByCostIds = mutableSetOf<String>()

    suspend fun applyBaseline(chId: String, baseline: ChannelMedianResult) {
      channelMedianResults[chId] = baseline
      baselineMutex.withLock {
        if (baseline.source == "UPLOADS") uploadsFallbackCount.incrementAndGet() else uushCount.incrementAndGet()
        if (baseline.median <= filters.maxMedianViews) {
          channelsPassingMedianCount.incrementAndGet()
        } else {
          channelsMedianTooHighCount.incrementAndGet()
        }
      }
    }

    val pendingChannels = mutableListOf<String>()
    for (chId in passingChannelIds) {
      val info = channelDataMap[chId] ?: continue
      val medianFresh = CacheTtl.isFresh(now, info.cachedMedianAt, CacheTtl.CHANNEL_MEDIAN_MS)
      val negativeFresh = CacheTtl.isFresh(now, info.cachedMedianAt, CacheTtl.NEGATIVE_STATUS_MS)
      when {
        info.cachedStatus == com.outlierradar.app.data.local.ChannelCacheStatus.OK &&
          info.cachedMedian > 0 &&
          medianFresh -> {
          channelCacheHits++
          if (info.cachedShortCount < com.outlierradar.app.domain.MedianCalculator.MIN_SAMPLES) {
            channelsWithTooFewShortsCount.incrementAndGet()
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
          if (info.cachedStatus == com.outlierradar.app.data.local.ChannelCacheStatus.TOO_FEW_SHORTS) {
            channelsWithTooFewShortsCount.incrementAndGet()
          }
        }
        else -> pendingChannels.add(chId)
      }
    }

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

    progressFlow.value = "Channels check ho rahe hain 0/${channelsToProcess.size}..."
    val channelSemaphore = Semaphore(3)
    val processedChannelsCount = AtomicInteger(0)
    val abortRef = AtomicReference<SearchResult.Error?>(null)
    val perChannelCost = AtomicInteger(0)
    val videoStatsCacheHits = AtomicInteger(0)

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
              fetchMedian(
                chId,
                chInfo?.uploadsPlaylistId,
                viralVideoIdsFromThisChannel,
                { perChannelCost.addAndGet(it) },
                { videoStatsCacheHits.addAndGet(it) }
              )
            } catch (e: AbortBaselineException) {
              abortRef.compareAndSet(null, e.error)
              return@withPermit
            } catch (e: kotlinx.coroutines.CancellationException) {
              throw e
            } catch (_: Exception) {
              null
            }

            if (fetched != null) {
              when (fetched) {
                is MedianFetchResult.Success -> {
                  channelCacheDao.updateBaseline(
                    channelId = chId,
                    status = com.outlierradar.app.data.local.ChannelCacheStatus.OK,
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
                    status = com.outlierradar.app.data.local.ChannelCacheStatus.TOO_FEW_SHORTS,
                    medianViews = 0L, shortCount = 0, source = "",
                    trimmedMeanViews = 0L, p90Views = 0L, othersAbove5xCount = 0, othersAbove10xCount = 0,
                    now = System.currentTimeMillis()
                  )
                  channelsWithTooFewShortsCount.incrementAndGet()
                }
                MedianFetchResult.PlaylistUnavailable -> {
                  channelCacheDao.updateBaseline(
                    channelId = chId,
                    status = com.outlierradar.app.data.local.ChannelCacheStatus.PLAYLIST_404,
                    medianViews = 0L, shortCount = 0, source = "",
                    trimmedMeanViews = 0L, p90Views = 0L, othersAbove5xCount = 0, othersAbove10xCount = 0,
                    now = System.currentTimeMillis()
                  )
                }
              }
            }

            val processed = processedChannelsCount.incrementAndGet()
            progressFlow.value = "Channels check ho rahe hain $processed/${channelsToProcess.size}..."
          }
        }
      }
      jobs.awaitAll()
    }

    abortRef.get()?.let { return Outcome.Abort(it) }

    return Outcome.Success(
      BaselineComputationResult(
        channelMedianResults = channelMedianResults.toMap(),
        skippedByCostIds = skippedByCostIds.toSet(),
        uushCount = uushCount.get(),
        uploadsFallbackCount = uploadsFallbackCount.get(),
        channelsWithTooFewShortsCount = channelsWithTooFewShortsCount.get(),
        channelsMedianTooHighCount = channelsMedianTooHighCount.get(),
        channelsPassingMedianCount = channelsPassingMedianCount.get(),
        channelCacheHits = channelCacheHits,
        negativeCacheHits = negativeCacheHits,
        perChannelCost = perChannelCost.get(),
        videoStatsCacheHits = videoStatsCacheHits.get()
      )
    )
  }

  sealed interface Outcome {
    data class Success(val result: BaselineComputationResult) : Outcome
    data class Abort(val error: SearchResult.Error) : Outcome
  }
}

class AbortBaselineException(val error: SearchResult.Error) : Exception()
