package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    QuotaUsageEntity::class,
    SearchCacheEntity::class,
    ChannelCacheEntity::class,
    ShortVerificationCacheEntity::class,
    SavedOutlierEntity::class,
    AiCacheEntity::class
  ],
  version = 3,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun quotaDao(): QuotaDao
  abstract fun searchCacheDao(): SearchCacheDao
  abstract fun channelCacheDao(): ChannelCacheDao
  abstract fun shortVerificationDao(): ShortVerificationDao
  abstract fun savedOutlierDao(): SavedOutlierDao
  abstract fun aiCacheDao(): AiCacheDao

  companion object {
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "outlier_radar_database"
        )
          .fallbackToDestructiveMigration(true)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
