package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    QuotaUsageEntity::class,
    SearchCacheEntity::class,
    ChannelCacheEntity::class,
    VideoStatsCacheEntity::class,
    ShortVerificationCacheEntity::class,
    SavedOutlierEntity::class,
    AiCacheEntity::class,
    NicheCacheEntity::class
  ],
  version = 6,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun quotaDao(): QuotaDao
  abstract fun searchCacheDao(): SearchCacheDao
  abstract fun channelCacheDao(): ChannelCacheDao
  abstract fun videoStatsDao(): VideoStatsDao
  abstract fun shortVerificationDao(): ShortVerificationDao
  abstract fun savedOutlierDao(): SavedOutlierDao
  abstract fun aiCacheDao(): AiCacheDao
  abstract fun nicheCacheDao(): NicheCacheDao

  companion object {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    /**
     * v1 -> v2: `shortStatus` on saved outliers and the AI analysis cache table.
     * (Previously handled by destructive fallback; now lossless.)
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          "ALTER TABLE `saved_outliers` ADD COLUMN `shortStatus` TEXT NOT NULL DEFAULT 'CONFIRMED_SHORT'"
        )
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `ai_cache` (
            `cacheKey` TEXT NOT NULL,
            `videoId` TEXT NOT NULL,
            `language` TEXT NOT NULL,
            `responseJson` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            PRIMARY KEY(`cacheKey`)
          )
          """.trimIndent()
        )
      }
    }

    /** v2 -> v3: near-miss payloads on the search cache. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `search_cache` ADD COLUMN `nearMissesJson` TEXT NOT NULL DEFAULT '[]'")
      }
    }

    /**
     * v3 -> v4: richer channel baseline (trimmed mean, p90, >5x / >10x counts)
     * and a Double multiplier. Saved outliers are copied into a rebuilt table so
     * `multiplier` changes affinity from INTEGER to REAL without losing rows.
     */
    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `channel_cache` ADD COLUMN `trimmedMeanViews` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channel_cache` ADD COLUMN `p90Views` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channel_cache` ADD COLUMN `othersAbove5xCount` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channel_cache` ADD COLUMN `othersAbove10xCount` INTEGER NOT NULL DEFAULT 0")

        // SQLite cannot change a column's type in place, and Room validates the
        // declared affinity, so saved_outliers has to be recreated and refilled.
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `saved_outliers_new` (
            `videoId` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `channelTitle` TEXT NOT NULL,
            `channelId` TEXT NOT NULL,
            `subscriberCount` INTEGER NOT NULL,
            `views` INTEGER NOT NULL,
            `medianViews` INTEGER NOT NULL,
            `multiplier` REAL NOT NULL,
            `viewsPerSub` REAL NOT NULL,
            `likes` INTEGER NOT NULL,
            `comments` INTEGER NOT NULL,
            `durationSeconds` INTEGER NOT NULL,
            `publishedAt` TEXT NOT NULL,
            `tags` TEXT NOT NULL,
            `thumbnailUrl` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `isShort` INTEGER NOT NULL,
            `shortStatus` TEXT NOT NULL,
            `savedAt` INTEGER NOT NULL,
            `trimmedMeanViews` INTEGER NOT NULL DEFAULT 0,
            `p90Views` INTEGER NOT NULL DEFAULT 0,
            `highMultiplierShortCount` INTEGER NOT NULL DEFAULT 0,
            `isOneHitWonder` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`videoId`)
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          INSERT INTO `saved_outliers_new` (
            `videoId`, `title`, `channelTitle`, `channelId`, `subscriberCount`, `views`,
            `medianViews`, `multiplier`, `viewsPerSub`, `likes`, `comments`, `durationSeconds`,
            `publishedAt`, `tags`, `thumbnailUrl`, `description`, `isShort`, `shortStatus`, `savedAt`,
            `trimmedMeanViews`, `p90Views`, `highMultiplierShortCount`, `isOneHitWonder`
          )
          SELECT
            `videoId`, `title`, `channelTitle`, `channelId`, `subscriberCount`, `views`,
            `medianViews`, CAST(`multiplier` AS REAL), `viewsPerSub`, `likes`, `comments`, `durationSeconds`,
            `publishedAt`, `tags`, `thumbnailUrl`, `description`, `isShort`, `shortStatus`, `savedAt`,
            0, 0, 0, 0
          FROM `saved_outliers`
          """.trimIndent()
        )
        db.execSQL("DROP TABLE `saved_outliers`")
        db.execSQL("ALTER TABLE `saved_outliers_new` RENAME TO `saved_outliers`")
      }
    }

    /**
     * v4 -> v5: quota usage becomes per API key, channel cache gets separate
     * subscriber/median timestamps, a negative-cache status and the persisted
     * uploads playlist id, and video statistics get a 6h cache.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        // --- quota_usage: add keyFingerprint to the primary key -----------------
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `quota_usage_new` (
            `dateString` TEXT NOT NULL,
            `keyFingerprint` TEXT NOT NULL,
            `searchCalls` INTEGER NOT NULL,
            `mainUnits` INTEGER NOT NULL,
            `isSearchExceeded` INTEGER NOT NULL,
            `isMainExceeded` INTEGER NOT NULL,
            PRIMARY KEY(`dateString`, `keyFingerprint`)
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          INSERT INTO `quota_usage_new` (
            `dateString`, `keyFingerprint`, `searchCalls`, `mainUnits`,
            `isSearchExceeded`, `isMainExceeded`
          )
          SELECT `dateString`, '', `searchCalls`, `mainUnits`,
                 `isSearchExceeded`, `isMainExceeded`
          FROM `quota_usage`
          """.trimIndent()
        )
        db.execSQL("DROP TABLE `quota_usage`")
        db.execSQL("ALTER TABLE `quota_usage_new` RENAME TO `quota_usage`")

        // --- channel_cache: TTL split + negative cache + uploads playlist --------
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `channel_cache_new` (
            `channelId` TEXT NOT NULL,
            `subscriberCount` INTEGER NOT NULL,
            `hasHiddenSubscribers` INTEGER NOT NULL,
            `medianViews` INTEGER NOT NULL,
            `shortCount` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `subsCachedAt` INTEGER NOT NULL,
            `medianCachedAt` INTEGER NOT NULL,
            `status` TEXT NOT NULL,
            `uploadsPlaylistId` TEXT,
            `trimmedMeanViews` INTEGER NOT NULL,
            `p90Views` INTEGER NOT NULL,
            `othersAbove5xCount` INTEGER NOT NULL,
            `othersAbove10xCount` INTEGER NOT NULL,
            PRIMARY KEY(`channelId`)
          )
          """.trimIndent()
        )
        db.execSQL(
          """
          INSERT INTO `channel_cache_new` (
            `channelId`, `subscriberCount`, `hasHiddenSubscribers`, `medianViews`,
            `shortCount`, `source`, `subsCachedAt`, `medianCachedAt`, `status`,
            `uploadsPlaylistId`, `trimmedMeanViews`, `p90Views`,
            `othersAbove5xCount`, `othersAbove10xCount`
          )
          SELECT
            `channelId`, `subscriberCount`, `hasHiddenSubscribers`, `medianViews`,
            `shortCount`, `source`, `timestamp`,
            CASE WHEN `medianViews` > 0 THEN `timestamp` ELSE 0 END,
            CASE
              WHEN `medianViews` <= 0 OR `shortCount` < 5 THEN 'TOO_FEW_SHORTS'
              ELSE 'OK'
            END,
            NULL, `trimmedMeanViews`, `p90Views`,
            `othersAbove5xCount`, `othersAbove10xCount`
          FROM `channel_cache`
          """.trimIndent()
        )
        db.execSQL("DROP TABLE `channel_cache`")
        db.execSQL("ALTER TABLE `channel_cache_new` RENAME TO `channel_cache`")

        // --- video stats 6h cache -----------------------------------------------
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `video_stats_cache` (
            `videoId` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `tagsJson` TEXT NOT NULL,
            `thumbnailUrl` TEXT NOT NULL,
            `channelId` TEXT NOT NULL,
            `channelTitle` TEXT NOT NULL,
            `views` INTEGER NOT NULL,
            `likes` INTEGER NOT NULL,
            `comments` INTEGER NOT NULL,
            `durationSeconds` INTEGER NOT NULL,
            `publishedAt` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            PRIMARY KEY(`videoId`)
          )
          """.trimIndent()
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_video_stats_cache_timestamp` " +
            "ON `video_stats_cache` (`timestamp`)"
        )
      }
    }

    /**
     * v5 -> v6: Gemini micro-niche keyword cache for AUTO_EXPAND.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `niche_cache` (
            `cacheKey` TEXT NOT NULL,
            `keyword` TEXT NOT NULL,
            `language` TEXT NOT NULL,
            `keywordsJson` TEXT NOT NULL,
            `timestamp` INTEGER NOT NULL,
            PRIMARY KEY(`cacheKey`)
          )
          """.trimIndent()
        )
      }
    }

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "outlier_radar_database"
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
