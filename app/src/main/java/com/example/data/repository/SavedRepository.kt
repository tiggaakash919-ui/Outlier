package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.SavedOutlierEntity
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SavedRepository(
  private val database: AppDatabase
) {
  private val savedDao = database.savedOutlierDao()

  val savedOutliersFlow: Flow<List<OutlierVideo>> = savedDao.getAllSaved().map { list ->
    list.map { entity ->
      val parsedStatus = try {
        ShortStatus.valueOf(entity.shortStatus)
      } catch (_: Exception) {
        if (entity.isShort) ShortStatus.CONFIRMED_SHORT else ShortStatus.NOT_A_SHORT
      }
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
        channelTrimmedMeanViews = entity.trimmedMeanViews,
        channelP90Views = entity.p90Views,
        outlierMultiplier = entity.multiplier,
        viewsPerSub = entity.viewsPerSub,
        likes = entity.likes,
        comments = entity.comments,
        durationSeconds = entity.durationSeconds,
        publishedAt = entity.publishedAt,
        othersAbove10xCount = entity.highMultiplierShortCount,
        shortStatus = parsedStatus,
        isSaved = true,
        savedAt = entity.savedAt,
        isOneHitWonder = entity.isOneHitWonder
      )
    }
  }

  suspend fun toggleSave(video: OutlierVideo): Boolean {
    val exists = savedDao.isSaved(video.videoId)
    if (exists) {
      savedDao.deleteSaved(video.videoId)
      return false
    } else {
      val now = System.currentTimeMillis()
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
          shortStatus = video.shortStatus.name,
          savedAt = now,
          trimmedMeanViews = video.channelTrimmedMeanViews,
          p90Views = video.channelP90Views,
          highMultiplierShortCount = video.othersAbove10xCount,
          isOneHitWonder = video.isOneHitWonder
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

  companion object {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())

    private fun escapeCsv(value: String): String {
      val escaped = value.replace("\"", "\"\"")
      return "\"$escaped\""
    }

    /**
     * Generates UTF-8 CSV with 3-byte UTF-8 BOM (EF BB BF) so Excel renders Hindi/Hinglish text intact.
     * Columns: Title, Channel, ChannelURL, Subs, Views, Median, Multiplier, ViewsPerSub,
     * Likes, Comments, Duration, UploadDate, Tags, ShortStatus, VideoURL, SavedAt.
     */
    fun generateCsvBytesWithBom(items: List<OutlierVideo>): ByteArray {
      val sb = StringBuilder()
      sb.append(
        "Title,Channel,ChannelURL,Subs,Views,Median,Multiplier,ViewsPerSub,Likes,Comments,Duration,UploadDate,Tags,ShortStatus,VideoURL,SavedAt\r\n"
      )

      for (item in items) {
        val channelUrl = "https://www.youtube.com/channel/${item.channelId}"
        val videoUrl = "https://www.youtube.com/shorts/${item.videoId}"
        val viewsPerSubStr = String.format(Locale.US, "%.2f", item.viewsPerSub)
        val uploadDate = if (item.publishedAt.length >= 10) item.publishedAt.substring(0, 10) else item.publishedAt
        val tagsJoined = item.tags.joinToString("; ")
        val shortStatusStr = when (item.shortStatus) {
          ShortStatus.CONFIRMED_SHORT -> "Short"
          ShortStatus.NOT_A_SHORT -> "Not a Short"
          ShortStatus.UNVERIFIED -> "Unverified"
        }
        val savedAtStr = if (item.savedAt > 0) {
          dateFormatter.format(Instant.ofEpochMilli(item.savedAt))
        } else {
          dateFormatter.format(Instant.now())
        }

        val row = listOf(
          escapeCsv(item.title),
          escapeCsv(item.channelTitle),
          escapeCsv(channelUrl),
          item.subscriberCount.toString(),
          item.views.toString(),
          item.channelMedianViews.toString(),
          "${OutlierCalculator.formatMultiplier(item.outlierMultiplier)}x",
          viewsPerSubStr,
          item.likes.toString(),
          item.comments.toString(),
          "${item.durationSeconds}s",
          escapeCsv(uploadDate),
          escapeCsv(tagsJoined),
          escapeCsv(shortStatusStr),
          escapeCsv(videoUrl),
          escapeCsv(savedAtStr)
        ).joinToString(",")

        sb.append(row).append("\r\n")
      }

      val csvBytes = sb.toString().toByteArray(Charsets.UTF_8)
      val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
      return bom + csvBytes
    }
  }
}
