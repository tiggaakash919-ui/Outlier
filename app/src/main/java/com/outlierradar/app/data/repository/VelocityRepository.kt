package com.outlierradar.app.data.repository

import android.content.Context
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.TrackedVideoEntity
import com.outlierradar.app.data.local.ViewSnapshotEntity
import com.outlierradar.app.data.local.WatchedChannelEntity
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.worker.VelocityWorkScheduler
import kotlinx.coroutines.flow.Flow

class VelocityRepository(
  private val context: Context,
  private val database: AppDatabase
) {
  private val snapshotDao = database.viewSnapshotDao()
  private val trackedDao = database.trackedVideoDao()
  private val watchedChannelDao = database.watchedChannelDao()

  fun snapshotsFlow(videoId: String): Flow<List<ViewSnapshotEntity>> =
    snapshotDao.getSnapshotsFlow(videoId)

  fun trackedVideoFlow(videoId: String): Flow<TrackedVideoEntity?> =
    trackedDao.getFlow(videoId)

  fun watchedChannelsFlow(): Flow<List<WatchedChannelEntity>> =
    watchedChannelDao.getAllFlow()

  fun isChannelWatchedFlow(channelId: String): Flow<Boolean> =
    watchedChannelDao.isWatchedFlow(channelId)

  suspend fun getTrackedVideoIds(): List<String> = trackedDao.getTrackedVideoIds()

  suspend fun getAllTracked(): List<TrackedVideoEntity> = trackedDao.getAllTracked()

  suspend fun seedSnapshotIfMissing(video: OutlierVideo) {
    val now = System.currentTimeMillis()
    val existing = snapshotDao.getLatestSnapshot(video.videoId)
    if (existing == null) {
      snapshotDao.insertSnapshot(
        ViewSnapshotEntity(
          videoId = video.videoId,
          timestamp = now,
          views = video.views,
          likes = video.likes,
          comments = video.comments
        )
      )
      trackedDao.updateLastSnapshotAt(video.videoId, now)
    }
  }

  /** Ensure the video is tracked for velocity (saved state updated). */
  suspend fun trackFromSave(video: OutlierVideo, isSaved: Boolean) {
    val now = System.currentTimeMillis()
    val current = trackedDao.get(video.videoId)
    val entity = (current ?: TrackedVideoEntity(
      videoId = video.videoId,
      title = video.title,
      channelId = video.channelId,
      channelTitle = video.channelTitle,
      thumbnailUrl = video.thumbnailUrl,
      addedAt = now
    )).copy(
      title = video.title.ifBlank { current?.title ?: "" },
      channelId = video.channelId.ifBlank { current?.channelId ?: "" },
      channelTitle = video.channelTitle.ifBlank { current?.channelTitle ?: "" },
      thumbnailUrl = video.thumbnailUrl.ifBlank { current?.thumbnailUrl ?: "" },
      isSaved = isSaved,
      // Saving implies tracking/watchlist for velocity snapshots.
      isWatchlisted = isSaved || (current?.isWatchlisted == true)
    )
    trackedDao.upsert(entity)
    if (isSaved) seedSnapshotIfMissing(video)
    if (!entity.isSaved && !entity.isWatchlisted) {
      trackedDao.delete(video.videoId)
    }
  }

  suspend fun setWatchlisted(video: OutlierVideo, watchlisted: Boolean) {
    val now = System.currentTimeMillis()
    val current = trackedDao.get(video.videoId)
    val entity = (current ?: TrackedVideoEntity(
      videoId = video.videoId,
      title = video.title,
      channelId = video.channelId,
      channelTitle = video.channelTitle,
      thumbnailUrl = video.thumbnailUrl,
      addedAt = now
    )).copy(
      title = video.title.ifBlank { current?.title ?: "" },
      channelId = video.channelId.ifBlank { current?.channelId ?: "" },
      channelTitle = video.channelTitle.ifBlank { current?.channelTitle ?: "" },
      thumbnailUrl = video.thumbnailUrl.ifBlank { current?.thumbnailUrl ?: "" },
      isWatchlisted = watchlisted
    )
    trackedDao.upsert(entity)
    if (watchlisted) {
      seedSnapshotIfMissing(video)
      VelocityWorkScheduler.ensureScheduled(context)
    }
    if (!entity.isSaved && !entity.isWatchlisted) {
      trackedDao.delete(video.videoId)
    }
  }

  suspend fun toggleWatchlist(video: OutlierVideo): Boolean {
    val current = trackedDao.get(video.videoId)
    val next = !(current?.isWatchlisted ?: false)
    setWatchlisted(video, next)
    return next
  }

  fun ensureVelocityWorkScheduled() {
    VelocityWorkScheduler.ensureScheduled(context)
  }

  suspend fun upsertSnapshots(snapshots: List<ViewSnapshotEntity>) {
    if (snapshots.isEmpty()) return
    snapshotDao.insertSnapshots(snapshots)
    val now = System.currentTimeMillis()
    snapshots.map { it.videoId }.distinct().forEach { trackedDao.updateLastSnapshotAt(it, now) }
  }

  suspend fun getSnapshots(videoId: String): List<ViewSnapshotEntity> = snapshotDao.getSnapshots(videoId)

  suspend fun getWatchedChannels(): List<WatchedChannelEntity> = watchedChannelDao.getAll()

  suspend fun followChannel(
    channelId: String,
    channelTitle: String,
    uploadsPlaylistId: String?,
    subscriberCount: Long,
    hasHiddenSubscribers: Boolean,
    baselineMedian: Long
  ) {
    val existing = watchedChannelDao.get(channelId)
    watchedChannelDao.upsert(
      WatchedChannelEntity(
        channelId = channelId,
        channelTitle = channelTitle.ifBlank { existing?.channelTitle ?: channelId },
        uploadsPlaylistId = uploadsPlaylistId ?: existing?.uploadsPlaylistId,
        subscriberCount = subscriberCount,
        hasHiddenSubscribers = hasHiddenSubscribers,
        baselineMedian = if (baselineMedian > 0L) baselineMedian else (existing?.baselineMedian ?: 0L),
        addedAt = existing?.addedAt ?: System.currentTimeMillis(),
        lastCheckedAt = existing?.lastCheckedAt ?: 0L,
        lastNotifiedVideoId = existing?.lastNotifiedVideoId
      )
    )
  }

  suspend fun unfollowChannel(channelId: String) {
    watchedChannelDao.delete(channelId)
  }

  suspend fun updateWatchedChannelCheck(channelId: String, checkedAt: Long, notifiedVideoId: String?) {
    watchedChannelDao.updateCheckState(channelId, checkedAt, notifiedVideoId)
  }

  suspend fun updateWatchedChannelBaseline(channelId: String, median: Long, uploadsPlaylistId: String?) {
    watchedChannelDao.updateBaseline(channelId, median, uploadsPlaylistId)
  }

  suspend fun clearVelocityData() {
    snapshotDao.clearAll()
    trackedDao.clearAll()
    watchedChannelDao.clearAll()
  }
}
