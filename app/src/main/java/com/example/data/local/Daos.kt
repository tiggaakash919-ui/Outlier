package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuotaDao {
  @Query("SELECT * FROM quota_usage WHERE dateString = :dateString AND keyFingerprint = :fingerprint")
  fun getQuotaFlow(dateString: String, fingerprint: String): Flow<QuotaUsageEntity?>

  @Query("SELECT * FROM quota_usage WHERE dateString = :dateString")
  fun getQuotaRowsFlow(dateString: String): Flow<List<QuotaUsageEntity>>

  @Query("SELECT * FROM quota_usage WHERE dateString = :dateString AND keyFingerprint = :fingerprint")
  suspend fun getQuota(dateString: String, fingerprint: String): QuotaUsageEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertIfAbsent(entity: QuotaUsageEntity)

  @Query(
    "UPDATE quota_usage SET searchCalls = searchCalls + :count " +
      "WHERE dateString = :dateString AND keyFingerprint = :fingerprint"
  )
  suspend fun addSearchCalls(dateString: String, fingerprint: String, count: Int)

  @Query(
    "UPDATE quota_usage SET mainUnits = mainUnits + :units " +
      "WHERE dateString = :dateString AND keyFingerprint = :fingerprint"
  )
  suspend fun addMainUnits(dateString: String, fingerprint: String, units: Int)

  @Query(
    "UPDATE quota_usage SET isSearchExceeded = 1 " +
      "WHERE dateString = :dateString AND keyFingerprint = :fingerprint"
  )
  suspend fun markSearchExceeded(dateString: String, fingerprint: String)

  @Query(
    "UPDATE quota_usage SET isMainExceeded = 1 " +
      "WHERE dateString = :dateString AND keyFingerprint = :fingerprint"
  )
  suspend fun markMainExceeded(dateString: String, fingerprint: String)

  @Query("DELETE FROM quota_usage WHERE dateString < :before")
  suspend fun deleteQuotaBefore(before: String)

  /** Removes the pre-v5 global row after it has been reassigned to a key. */
  @Query("DELETE FROM quota_usage WHERE dateString = :dateString AND keyFingerprint = ''")
  suspend fun deleteLegacyRow(dateString: String)

  @Query("DELETE FROM quota_usage")
  suspend fun clearQuota()
}

@Dao
interface SearchCacheDao {
  @Query("SELECT * FROM search_cache WHERE cacheKey = :key")
  suspend fun getCache(key: String): SearchCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCache(cache: SearchCacheEntity)

  @Query("DELETE FROM search_cache")
  suspend fun clearSearchCache()
}

@Dao
interface ChannelCacheDao {
  @Query("SELECT * FROM channel_cache WHERE channelId = :channelId")
  suspend fun getChannel(channelId: String): ChannelCacheEntity?

  @Query("SELECT * FROM channel_cache WHERE channelId IN (:channelIds)")
  suspend fun getChannels(channelIds: List<String>): List<ChannelCacheEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChannel(channel: ChannelCacheEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChannels(channels: List<ChannelCacheEntity>)

  /** Creates a skeleton row when the channel is seen for the first time. */
  @Query(
    "INSERT OR IGNORE INTO channel_cache (" +
      "channelId, subscriberCount, hasHiddenSubscribers, medianViews, shortCount, source, " +
      "subsCachedAt, medianCachedAt, status, uploadsPlaylistId, trimmedMeanViews, p90Views, " +
      "othersAbove5xCount, othersAbove10xCount" +
      ") VALUES (" +
      ":channelId, :subscriberCount, :hasHiddenSubscribers, 0, 0, '', " +
      ":now, 0, 'PENDING', :uploadsPlaylistId, 0, 0, 0, 0" +
      ")"
  )
  suspend fun insertIfAbsent(
    channelId: String,
    subscriberCount: Long,
    hasHiddenSubscribers: Boolean,
    uploadsPlaylistId: String?,
    now: Long
  )

  @Query(
    "UPDATE channel_cache SET subscriberCount = :subscriberCount, " +
      "hasHiddenSubscribers = :hasHiddenSubscribers, uploadsPlaylistId = :uploadsPlaylistId, " +
      "subsCachedAt = :now WHERE channelId = :channelId"
  )
  suspend fun updateSubscriberInfo(
    channelId: String,
    subscriberCount: Long,
    hasHiddenSubscribers: Boolean,
    uploadsPlaylistId: String?,
    now: Long
  )

  @Query(
    "UPDATE channel_cache SET status = :status, medianViews = :medianViews, " +
      "shortCount = :shortCount, source = :source, trimmedMeanViews = :trimmedMeanViews, " +
      "p90Views = :p90Views, othersAbove5xCount = :othersAbove5xCount, " +
      "othersAbove10xCount = :othersAbove10xCount, medianCachedAt = :now " +
      "WHERE channelId = :channelId"
  )
  suspend fun updateBaseline(
    channelId: String,
    status: ChannelCacheStatus,
    medianViews: Long,
    shortCount: Int,
    source: String,
    trimmedMeanViews: Long,
    p90Views: Long,
    othersAbove5xCount: Int,
    othersAbove10xCount: Int,
    now: Long
  )

  /** Never downgrades a row that already has a usable baseline. */
  @Query(
    "UPDATE channel_cache SET status = :status, medianCachedAt = :now " +
      "WHERE channelId = :channelId AND status != 'OK'"
  )
  suspend fun updateNegativeStatus(channelId: String, status: ChannelCacheStatus, now: Long)

  @Query("DELETE FROM channel_cache")
  suspend fun clearChannelCache()
}

@Dao
interface VideoStatsDao {
  @Query("SELECT * FROM video_stats_cache WHERE videoId IN (:videoIds)")
  suspend fun getVideos(videoIds: List<String>): List<VideoStatsCacheEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertVideos(videos: List<VideoStatsCacheEntity>)

  @Query("DELETE FROM video_stats_cache WHERE timestamp < :before")
  suspend fun deleteOlderThan(before: Long)

  @Query("DELETE FROM video_stats_cache")
  suspend fun clearVideoStats()
}

@Dao
interface ShortVerificationDao {
  @Query("SELECT * FROM short_verification_cache WHERE videoId = :videoId")
  suspend fun getVerification(videoId: String): ShortVerificationCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertVerification(verification: ShortVerificationCacheEntity)

  @Query("DELETE FROM short_verification_cache")
  suspend fun clearShortVerificationCache()
}

@Dao
interface SavedOutlierDao {
  @Query("SELECT * FROM saved_outliers ORDER BY savedAt DESC")
  fun getAllSaved(): Flow<List<SavedOutlierEntity>>

  @Query("SELECT EXISTS(SELECT 1 FROM saved_outliers WHERE videoId = :videoId)")
  fun isSavedFlow(videoId: String): Flow<Boolean>

  @Query("SELECT EXISTS(SELECT 1 FROM saved_outliers WHERE videoId = :videoId)")
  suspend fun isSaved(videoId: String): Boolean

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun saveOutlier(entity: SavedOutlierEntity)

  @Query("DELETE FROM saved_outliers WHERE videoId = :videoId")
  suspend fun deleteSaved(videoId: String)

  @Query("DELETE FROM saved_outliers")
  suspend fun clearAll()
}

@Dao
interface AiCacheDao {
  @Query("SELECT * FROM ai_cache WHERE cacheKey = :cacheKey")
  suspend fun getAiCache(cacheKey: String): AiCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAiCache(entity: AiCacheEntity)

  @Query("DELETE FROM ai_cache")
  suspend fun clearAiCache()
}
