package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quota_usage")
data class QuotaUsageEntity(
  @PrimaryKey val dateString: String, // YYYY-MM-DD in America/Los_Angeles
  val searchCalls: Int = 0,
  val mainUnits: Int = 0,
  val isSearchExceeded: Boolean = false,
  val isMainExceeded: Boolean = false
)

@Entity(tableName = "search_cache")
data class SearchCacheEntity(
  @PrimaryKey val cacheKey: String,
  val timestamp: Long,
  val nextPageToken: String? = null,
  val resultsJson: String,
  val debugStatsJson: String
)

@Entity(tableName = "channel_cache")
data class ChannelCacheEntity(
  @PrimaryKey val channelId: String,
  val subscriberCount: Long,
  val hasHiddenSubscribers: Boolean,
  val medianViews: Long,
  val shortCount: Int,
  val source: String, // UUSH or UPLOADS
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
  val multiplier: Int,
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
  val savedAt: Long
)

@Entity(tableName = "ai_cache")
data class AiCacheEntity(
  @PrimaryKey val cacheKey: String, // "{videoId}_{language}"
  val videoId: String,
  val language: String,
  val responseJson: String,
  val timestamp: Long
)
