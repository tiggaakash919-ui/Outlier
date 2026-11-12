package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.SavedOutlierEntity
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SavedRepository(
  private val database: AppDatabase
) {
  private val savedDao = database.savedOutlierDao()

  val savedOutliersFlow: Flow<List<OutlierVideo>> = savedDao.getAllSaved().map { list ->
    list.map { entity ->
      OutlierVideo(
        videoId = entity.videoId,
        title = entity.title,
        description = entity.description,
        tags = if (entity.tags.isBlank()) emptyList() else entity.tags.split(","),
        thumbnailUrl = entity.thumbnailUrl,
        channelId = entity.channelId,
        channelTitle = entity.channelTitle,
        subscriberCount = entity.subscriberCount,
        hiddenSubscriberCount = false,
        views = entity.views,
        channelMedianViews = entity.medianViews,
        outlierMultiplier = entity.multiplier,
        viewsPerSub = entity.viewsPerSub,
        likes = entity.likes,
        comments = entity.comments,
        durationSeconds = entity.durationSeconds,
        publishedAt = entity.publishedAt,
        shortStatus = if (entity.isShort) ShortStatus.CONFIRMED_SHORT else ShortStatus.NOT_A_SHORT,
        isSaved = true
      )
    }
  }

  suspend fun toggleSave(video: OutlierVideo): Boolean {
    val exists = savedDao.isSaved(video.videoId)
    if (exists) {
      savedDao.deleteSaved(video.videoId)
      return false
    } else {
      savedDao.saveOutlier(
        SavedOutlierEntity(
          videoId = video.videoId,
          title = video.title,
          channelTitle = video.channelTitle,
          channelId = video.channelId,
          subscriberCount = video.subscriberCount,
          views = video.views,
          medianViews = video.channelMedianViews,
          multiplier = video.outlierMultiplier,
          viewsPerSub = video.viewsPerSub,
          likes = video.likes,
          comments = video.comments,
          durationSeconds = video.durationSeconds,
          publishedAt = video.publishedAt,
          tags = video.tags.joinToString(","),
          thumbnailUrl = video.thumbnailUrl,
          description = video.description,
          isShort = video.shortStatus == ShortStatus.CONFIRMED_SHORT,
          savedAt = System.currentTimeMillis()
        )
      )
      return true
    }
  }

  suspend fun remove(videoId: String) {
    savedDao.deleteSaved(videoId)
  }

  suspend fun clearAll() {
    savedDao.clearAll()
  }
}
