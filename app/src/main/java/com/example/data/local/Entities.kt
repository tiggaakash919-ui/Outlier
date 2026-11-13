package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Negative/positive cache state for a channel. */
enum class ChannelCacheStatus {
  /** A usable baseline exists. */
  OK,

  /** Subscriber data is cached but the baseline has not been fetched yet. */
  PENDING,

  /** Fewer than [com.example.domain.MedianCalculator.MIN_SAMPLES] mature Shorts. */
  TOO_FEW_SHORTS,

  /** Hidden subscriber count; filtered out locally, so never spend units on it. */
  HIDDEN_SUBS,

  /** Neither the UUSH nor the uploads playlist could be read. */
  PLAYLIST_404,

  /** channels.list did not return this id (deleted/private channel). */
  NO_CHANNEL
}

@Entity(tableName = "quota_usage", primaryKeys = ["dateString", "keyFingerprint"])
data class QuotaUsageEntity(
  val dateString: String, // YYYY-MM-DD in America/Los_Angeles
  val keyFingerprint: String = "", // ApiKeyFingerprint.of(key)
  val searchCalls: Int = 0, // search.list bucket (100/day per key)
  val mainUnits: Int = 0, // everything else (10,000/day per key)
  val isSearchExceeded: Boolean = false,
  val isMainExceeded: Boolean = false
)

@Entity(tableName = "search_cache")
data class SearchCacheEntity(
  @PrimaryKey val cacheKey: String,
  val timestamp: Long,
  val nextPageToken: String? = null,
  val resultsJson: String,
  val debugStatsJson: String,
  val nearMissesJson: String = "[]"
)

@Entity(tableName = "channel_cache")
data class ChannelCacheEntity(
  @PrimaryKey val channelId: String,
  val subscriberCount: Long,
  val hasHiddenSubscribers: Boolean,
  // Baseline (0 for negative rows)
  val medianViews: Long,
  val shortCount: Int,
  val source: String, // UUSH or UPLOADS
  // Separate freshness timestamps (see CacheTtl)
  val subsCachedAt: Long = 0L,
  val medianCachedAt: Long = 0L,
  val status: ChannelCacheStatus = ChannelCacheStatus.PENDING,
  val uploadsPlaylistId: String? = null,
  val trimmedMeanViews: Long = 0L,
  val p90Views: Long = 0L,
  val othersAbove5xCount: Int = 0,
  val othersAbove10xCount: Int = 0
)

@Entity(tableName = "video_stats_cache", indices = [Index("timestamp")])
data class VideoStatsCacheEntity(
  @PrimaryKey val videoId: String,
  val title: String,
  val description: String,
  val tagsJson: String,
  val thumbnailUrl: String,
  val channelId: String,
  val channelTitle: String,
  val views: Long,
  val likes: Long,
  val comments: Long,
  val durationSeconds: Long,
  val publishedAt: String,
  val timestamp: Long
)

@Entity(tableName = "short_verification_cache")
data class ShortVerificationCacheEntity(
  @PrimaryKey val videoId: String,
  val isShort: Boolean,
  val isUnverified: Boolean,
  val timestamp: Long
)

@Entity(tableName = "saved_outliers")
data class SavedOutlierEntity(
  @PrimaryKey val videoId: String,
  val title: String,
  val channelTitle: String,
  val channelId: String,
  val subscriberCount: Long,
  val views: Long,
  val medianViews: Long,
  val multiplier: Double,
  val viewsPerSub: Double,
  val likes: Long,
  val comments: Long,
  val durationSeconds: Long,
  val publishedAt: String,
  val tags: String,
  val thumbnailUrl: String,
  val description: String,
  val isShort: Boolean,
  val shortStatus: String = "CONFIRMED_SHORT",
  val savedAt: Long,
  val trimmedMeanViews: Long = 0L,
  val p90Views: Long = 0L,
  val highMultiplierShortCount: Int = 0,
  val isOneHitWonder: Boolean = false
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
  @PrimaryKey val cacheKey: String, // "{videoId}_{language}"
  val videoId: String,
  val language: String,
  val responseJson: String,
  val timestamp: Long
)

/** Gemini-generated micro-niche keywords, cached for faster AUTO_EXPAND runs. */
@Entity(tableName = "niche_cache")
data class NicheCacheEntity(
  @PrimaryKey val cacheKey: String, // "{normalizedKeyword}_{language}"
  val keyword: String,
  val language: String,
  val keywordsJson: String,
  val timestamp: Long
)

/** Periodic view/engagement snapshots for velocity tracking. */
@Entity(
  tableName = "view_snapshots",
  primaryKeys = ["videoId", "timestamp"],
  indices = [Index("videoId"), Index("timestamp")]
)
data class ViewSnapshotEntity(
  val videoId: String,
  val timestamp: Long,
  val views: Long,
  val likes: Long,
  val comments: Long
)

/** Videos tracked for velocity snapshots (saved and/or explicitly watchlisted). */
@Entity(tableName = "tracked_videos")
data class TrackedVideoEntity(
  @PrimaryKey val videoId: String,
  val title: String,
  val channelId: String,
  val channelTitle: String,
  val thumbnailUrl: String,
  val isSaved: Boolean = false,
  val isWatchlisted: Boolean = false,
  val addedAt: Long,
  val lastSnapshotAt: Long = 0L
)

/** Channels followed for daily outlier checks. */
@Entity(tableName = "watched_channels")
data class WatchedChannelEntity(
  @PrimaryKey val channelId: String,
  val channelTitle: String,
  val uploadsPlaylistId: String? = null,
  val subscriberCount: Long = 0L,
  val hasHiddenSubscribers: Boolean = false,
  val baselineMedian: Long = 0L,
  val addedAt: Long,
  val lastCheckedAt: Long = 0L,
  val lastNotifiedVideoId: String? = null
)
