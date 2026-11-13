package com.outlierradar.app.data.repository

import android.content.Context
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.SavedOutlierEntity
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShortStatus
import com.outlierradar.app.worker.VelocityWorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SavedRepository(
  private val context: Context,
  private val database: AppDatabase,
  private val velocityRepository: VelocityRepository
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
        tags = if (entity.tags.isBlank()) emptyList() else entity.tags.split(",").filter { it.isNotBlank() },
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
        isOneHitWonder = entity.isOneHitWonder,
        savedFolder = entity.folder.ifBlank { "Ideas to shoot" },
        savedUserTags = if (entity.savedTags.isBlank()) emptyList() else entity.savedTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
      )
    }
  }

  suspend fun toggleSave(video: OutlierVideo): Boolean {
    val exists = savedDao.isSaved(video.videoId)
    val now = System.currentTimeMillis()
    val nowSaved = if (exists) {
      savedDao.deleteSaved(video.videoId)
      false
    } else {
      val existingOrg = savedDao.getOrganization(video.videoId)
      val folder = existingOrg?.folder?.ifBlank { null } ?: video.savedFolder.ifBlank { "Ideas to shoot" }
      val savedTags = existingOrg?.savedTags?.takeIf { it.isNotBlank() }
        ?: video.savedUserTags.joinToString(",").ifBlank { "" }
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
          isOneHitWonder = video.isOneHitWonder,
          folder = folder,
          savedTags = savedTags
        )
      )
      true
    }
    // Saving/unsaving updates velocity tracking and seeds/schedules snapshots.
    velocityRepository.trackFromSave(video, nowSaved)
    if (nowSaved) {
      VelocityWorkScheduler.ensureScheduled(context)
    }
    return nowSaved
  }

  suspend fun updateFolder(videoId: String, folder: String) {
    savedDao.updateFolder(videoId, folder.ifBlank { "Ideas to shoot" })
  }

  suspend fun updateSavedTags(videoId: String, tags: List<String>) {
    val normalized = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    savedDao.updateSavedTags(videoId, normalized.joinToString(","))
  }

  suspend fun toggleWatchlist(video: OutlierVideo): Boolean {
    val nowWatchlisted = velocityRepository.toggleWatchlist(video)
    if (nowWatchlisted) {
      VelocityWorkScheduler.ensureScheduled(context)
    }
    return nowWatchlisted
  }

  suspend fun setWatchlisted(video: OutlierVideo, watchlisted: Boolean) {
    velocityRepository.setWatchlisted(video, watchlisted)
    if (watchlisted) VelocityWorkScheduler.ensureScheduled(context)
  }

  suspend fun remove(videoId: String) {
    savedDao.deleteSaved(videoId)
    // Keep tracking row only if explicitly watchlisted.
    val tracked = database.trackedVideoDao().get(videoId)
    if (tracked != null) {
      if (tracked.isWatchlisted) {
        database.trackedVideoDao().setSaved(videoId, false)
      } else {
        database.trackedVideoDao().delete(videoId)
      }
    }
  }

  suspend fun clearAll() {
    savedDao.clearAll()
    // Remove saved flag from tracked rows; keep watchlisted-only rows.
    velocityRepository.getAllTracked().forEach { tracked ->
      if (tracked.isWatchlisted) {
        database.trackedVideoDao().setSaved(tracked.videoId, false)
      } else {
        database.trackedVideoDao().delete(tracked.videoId)
      }
    }
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
     * Default behavior is backward-compatible "saved" export (includes SavedAt column).
     */
    fun generateCsvBytesWithBom(items: List<OutlierVideo>, includeSavedColumns: Boolean = true): ByteArray {
      val sb = StringBuilder()
      val header = mutableListOf(
        "Title", "Channel", "ChannelURL", "Subs", "Views", "Median", "Multiplier",
        "ViewsPerSub", "Likes", "Comments", "Duration", "UploadDate", "Tags",
        "ShortStatus", "VideoURL"
      )
      if (includeSavedColumns) {
        header.addAll(listOf("Folder", "SavedTags", "SavedAt"))
      } else {
        header.add("PublishedAge")
      }
      sb.append(header.joinToString(",")).append("\r\n")

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
        val row = mutableListOf(
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
          escapeCsv(videoUrl)
        )
        if (includeSavedColumns) {
          val savedAtStr = if (item.savedAt > 0) {
            dateFormatter.format(Instant.ofEpochMilli(item.savedAt))
          } else ""
          row.add(escapeCsv(item.savedFolder.ifBlank { "Ideas to shoot" }))
          row.add(escapeCsv(item.savedUserTags.joinToString("; ")))
          row.add(escapeCsv(savedAtStr))
        } else {
          row.add(escapeCsv(OutlierCalculator.formatVideoAge(item.publishedAt)))
        }
        sb.append(row.joinToString(",")).append("\r\n")
      }

      val csvBytes = sb.toString().toByteArray(Charsets.UTF_8)
      val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
      return bom + csvBytes
    }

    fun generateSavedCsvBytesWithBom(items: List<OutlierVideo>): ByteArray =
      generateCsvBytesWithBom(items, includeSavedColumns = true)
  }
}
