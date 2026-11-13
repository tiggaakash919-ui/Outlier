package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.ChannelCacheEntity
import com.outlierradar.app.data.local.ChannelCacheStatus
import com.outlierradar.app.data.local.VideoStatsCacheEntity
import com.outlierradar.app.data.remote.PlaylistItem
import com.outlierradar.app.domain.DebugStats
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.NearMissVideo
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShortStatus

/** Shared mutable/accumulated context passed through search pipeline stages. */
data class SearchPipelineState(
  var searchReturnedCount: Int = 0,
  var currentSearchCalls: Int = 0,
  var videosListCost: Int = 0,
  var channelsListCost: Int = 0,
  var perChannelCost: Int = 0,
  var videoStatsCacheHits: Int = 0,
  var finalNextPageToken: String? = null
)

data class RawVideoCandidate(
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

data class ChannelStatsHolder(
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

data class ChannelMedianResult(
  val median: Long,
  val trimmedMean: Long,
  val p90: Long,
  val shortCount: Int,
  val othersAbove5xCount: Int,
  val othersAbove10xCount: Int,
  val source: String
)

sealed interface MedianFetchResult {
  data class Success(val baseline: ChannelMedianResult) : MedianFetchResult
  data object TooFewShorts : MedianFetchResult
  data object PlaylistUnavailable : MedianFetchResult
}

fun ChannelCacheEntity.toHolder(): ChannelStatsHolder = ChannelStatsHolder(
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

fun ChannelCacheStatus.isNegative(): Boolean =
  this == ChannelCacheStatus.TOO_FEW_SHORTS ||
    this == ChannelCacheStatus.PLAYLIST_404 ||
    this == ChannelCacheStatus.NO_CHANNEL

data class CandidateSearchResult(
  val collectedVideoIds: List<String>,
  val videoDetails: Map<String, VideoStatsCacheEntity>,
  val allFetchedVideos: List<RawVideoCandidate>,
  val ageFilteredVideos: List<RawVideoCandidate>,
  val uniqueChannels: List<String>,
  val nextPageToken: String?,
  val searchReturnedCount: Int,
  val afterDurationCount: Int,
  val afterMinViewsCount: Int,
  val afterExcludeCount: Int,
  val afterLikesCount: Int,
  val afterAgeCount: Int,
  val currentSearchCalls: Int,
  val videosListCost: Int,
  val videoStatsCacheHits: Int
)

data class ChannelStatsResult(
  val channelDataMap: Map<String, ChannelStatsHolder>,
  val passingChannelIds: Set<String>,
  val afterSubsFilterCount: Int,
  val channelsListCost: Int
)

data class BaselineComputationResult(
  val channelMedianResults: Map<String, ChannelMedianResult>,
  val skippedByCostIds: Set<String>,
  val uushCount: Int,
  val uploadsFallbackCount: Int,
  val channelsWithTooFewShortsCount: Int,
  val channelsMedianTooHighCount: Int,
  val channelsPassingMedianCount: Int,
  val channelCacheHits: Int,
  val negativeCacheHits: Int,
  val perChannelCost: Int,
  val videoStatsCacheHits: Int
)

data class ClassificationResult(
  val finalCandidates: List<OutlierVideo>,
  val topNearMisses: List<NearMissVideo>
)

data class VerificationResult(
  val finalizedOutliers: List<OutlierVideo>,
  val verifiedShortsCount: Int,
  val reuploadCount: Int
)
