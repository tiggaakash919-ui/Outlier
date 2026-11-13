package com.outlierradar.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.outlierradar.app.data.remote.ChannelItem
import com.outlierradar.app.data.remote.PlaylistItem
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.data.repository.ActiveKey
import com.outlierradar.app.data.repository.ApiKeyManager
import com.outlierradar.app.data.repository.QuotaBucket
import com.outlierradar.app.data.repository.QuotaTracker
import com.outlierradar.app.data.repository.VelocityRepository
import com.outlierradar.app.domain.MedianCalculator
import kotlinx.coroutines.CancellationException

class WatchedChannelWorker(
  appContext: Context,
  params: WorkerParameters,
  private val youTubeApi: YouTubeApi,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager,
  private val velocityRepository: VelocityRepository
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    return try {
      val channels = velocityRepository.getWatchedChannels()
      if (channels.isEmpty()) return Result.success()

      var key = WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
        ?: return Result.success()

      for (channel in channels) {
        val remaining = quotaTracker.getRemainingMainUnits(key.fingerprint)
        if (remaining < WorkerQuota.MIN_MAIN_UNITS_REMAINING) {
          val rotated = WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
          if (rotated == null) return Result.success() else key = rotated
        }

        try {
          val result = checkChannel(channel, key)
          key = result.key
          if (result.stopAll) return Result.success()
        } catch (e: Exception) {
          Log.w(TAG, "Channel check failed for ${channel.channelId}", e)
        }
      }
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Watched channel worker failed", e)
      Result.retry()
    }
  }

  private data class CheckResult(val key: ActiveKey, val stopAll: Boolean)

  private suspend fun checkChannel(
    channel: com.outlierradar.app.data.local.WatchedChannelEntity,
    startKey: ActiveKey
  ): CheckResult {
    var key = startKey
    var uploadsPlaylistId = channel.uploadsPlaylistId
    var baselineMedian = channel.baselineMedian

    // Resolve channel details / uploads playlist if missing.
    if (uploadsPlaylistId.isNullOrBlank() || baselineMedian <= 0L) {
      val chRes = callWithQuota(key) { k ->
        youTubeApi.getChannels(ids = channel.channelId, apiKey = k.key)
      }
      key = chRes.key
      if (chRes.stopAll) return CheckResult(key, true)
      val item = chRes.body?.items?.firstOrNull()
      if (item != null) {
        uploadsPlaylistId = item.contentDetails?.relatedPlaylists?.uploads ?: uploadsPlaylistId
        if (uploadsPlaylistId.isNullOrBlank() && channel.channelId.startsWith("UC")) {
          uploadsPlaylistId = "UU" + channel.channelId.drop(2)
        }
        if (baselineMedian <= 0L) {
          // best-effort: keep existing; median may be computed from fetched items below
        }
        velocityRepository.followChannel(
          channelId = channel.channelId,
          channelTitle = item.snippet?.title ?: channel.channelTitle,
          uploadsPlaylistId = uploadsPlaylistId,
          subscriberCount = item.statistics?.subscriberCount?.toLongOrNull() ?: channel.subscriberCount,
          hasHiddenSubscribers = item.statistics?.hiddenSubscriberCount ?: channel.hasHiddenSubscribers,
          baselineMedian = baselineMedian
        )
      }
    }

    val playlistId = uploadsPlaylistId
      ?: if (channel.channelId.startsWith("UC")) "UU" + channel.channelId.drop(2) else null
      ?: return CheckResult(key, false)

    val plRes = callWithQuota(key) { k ->
      youTubeApi.getPlaylistItems(playlistId = playlistId, maxResults = 30, apiKey = k.key)
    }
    key = plRes.key
    if (plRes.stopAll) return CheckResult(key, true)
    val items = plRes.body?.items.orEmpty()
    if (items.isEmpty()) {
      velocityRepository.updateWatchedChannelCheck(channel.channelId, System.currentTimeMillis(), channel.lastNotifiedVideoId)
      return CheckResult(key, false)
    }

    val videoIds = items.mapNotNull { it.contentDetails?.videoId }.distinct().take(50)
    if (videoIds.isEmpty()) return CheckResult(key, false)

    val vRes = callWithQuota(key) { k ->
      youTubeApi.getVideos(ids = videoIds.joinToString(","), apiKey = k.key)
    }
    key = vRes.key
    if (vRes.stopAll) return CheckResult(key, true)
    val videos = vRes.body?.items.orEmpty()
    if (videos.isEmpty()) return CheckResult(key, false)

    val views = videos.mapNotNull { it.statistics?.viewCount?.toLongOrNull() }
    val computedMedian = MedianCalculator.calculateMedian(views) ?: baselineMedian
    if (computedMedian > 0L && computedMedian != baselineMedian) {
      velocityRepository.updateWatchedChannelBaseline(channel.channelId, computedMedian, uploadsPlaylistId)
      baselineMedian = computedMedian
    }
    if (baselineMedian <= 0L) {
      velocityRepository.updateWatchedChannelCheck(channel.channelId, System.currentTimeMillis(), channel.lastNotifiedVideoId)
      return CheckResult(key, false)
    }

    val threshold = baselineMedian * 10.0
    var lastNotified = channel.lastNotifiedVideoId
    // items are in playlist order (usually newest first); scan and notify on first/new breakout not yet notified.
    for (video in videos) {
      val vViews = video.statistics?.viewCount?.toLongOrNull() ?: 0L
      if (vViews >= threshold && video.id != channel.lastNotifiedVideoId) {
        val title = video.snippet?.title ?: "New Short"
        ChannelAlertNotifier.notifyNewOutlier(
          context = applicationContext,
          channelId = channel.channelId,
          channelTitle = channel.channelTitle,
          videoId = video.id,
          videoTitle = title,
          multiplier = if (baselineMedian > 0L) vViews.toDouble() / baselineMedian.toDouble() else 0.0
        )
        lastNotified = video.id
        // notify once per check to avoid spam
        break
      }
    }

    velocityRepository.updateWatchedChannelCheck(
      channel.channelId,
      System.currentTimeMillis(),
      lastNotified
    )
    return CheckResult(key, false)
  }

  private data class ApiCallResult<T>(
    val key: ActiveKey,
    val body: T?,
    val stopAll: Boolean
  )

  private suspend fun <T> callWithQuota(
    startKey: ActiveKey,
    call: suspend (ActiveKey) -> retrofit2.Response<T>
  ): ApiCallResult<T> {
    var key = startKey
    repeat(3) {
      val remaining = quotaTracker.getRemainingMainUnits(key.fingerprint)
      if (remaining < WorkerQuota.MIN_MAIN_UNITS_REMAINING) {
        val rotated = WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
        if (rotated == null) return ApiCallResult(key, null, true) else key = rotated
      }
      val response = try {
        call(key)
      } catch (e: Exception) {
        return ApiCallResult(key, null, false)
      }
      if (response.isSuccessful) {
        quotaTracker.record(key.fingerprint, QuotaBucket.MAIN, 1)
        return ApiCallResult(key, response.body(), false)
      }
      val code = response.code()
      val body = response.errorBody()?.string().orEmpty()
      val quota = code == 403 && body.contains("quotaExceeded", ignoreCase = true)
      val keyInvalid = code == 401 || (code == 403 && (body.contains("API key", true) || body.contains("keyInvalid", true)))
      when {
        quota -> {
          quotaTracker.markExceeded(key.fingerprint, QuotaBucket.MAIN)
          val next = apiKeyManager.rotateAway(key, QuotaBucket.MAIN)
            ?: WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
          if (next == null) return ApiCallResult(key, null, true) else key = next
        }
        keyInvalid -> {
          apiKeyManager.discardKey(key.key)
          val next = apiKeyManager.acquireKey(QuotaBucket.MAIN)
            ?: return ApiCallResult(key, null, true)
          key = next
        }
        else -> return ApiCallResult(key, null, false)
      }
    }
    return ApiCallResult(key, null, false)
  }

  companion object {
    private const val TAG = "WatchedChannelWorker"
  }
}
