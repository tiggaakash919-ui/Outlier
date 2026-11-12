package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QuotaDao {
  @Query("SELECT * FROM quota_usage WHERE dateString = :dateString")
  fun getQuotaFlow(dateString: String): Flow<QuotaUsageEntity?>

  @Query("SELECT * FROM quota_usage WHERE dateString = :dateString")
  suspend fun getQuota(dateString: String): QuotaUsageEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertQuota(entity: QuotaUsageEntity)

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

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChannel(channel: ChannelCacheEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChannels(channels: List<ChannelCacheEntity>)

  @Query("DELETE FROM channel_cache")
  suspend fun clearChannelCache()
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
