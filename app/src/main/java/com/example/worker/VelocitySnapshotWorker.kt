package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.local.ViewSnapshotEntity
import com.example.data.remote.YouTubeApi
import com.example.data.repository.ActiveKey
import com.example.data.repository.ApiKeyManager
import com.example.data.repository.QuotaBucket
import com.example.data.repository.QuotaTracker
import com.example.data.repository.VelocityRepository
import kotlinx.coroutines.CancellationException

class VelocitySnapshotWorker(
  appContext: Context,
  params: WorkerParameters,
  private val youTubeApi: YouTubeApi,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager,
  private val velocityRepository: VelocityRepository
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    return try {
      val trackedIds = velocityRepository.getTrackedVideoIds().distinct()
      if (trackedIds.isEmpty()) return Result.success()

      // Respect quota: skip if under 1000 MAIN units remain on any usable key.
      var key = WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
        ?: return Result.success()

      val now = System.currentTimeMillis()
      for (chunk in trackedIds.chunked(50)) {
        // Re-check headroom per batch in case we are close to the floor.
        val remaining = quotaTracker.getRemainingMainUnits(key.fingerprint)
        if (remaining < WorkerQuota.MIN_MAIN_UNITS_REMAINING) {
          val rotated = WorkerQuota.acquireKeyWithHeadroom(apiKeyManager, quotaTracker)
          if (rotated == null) return Result.success() else key = rotated
        }

        val outcome = fetchChunk(chunk, key)
        when (outcome) {
          is ChunkOutcome.Success -> {
            quotaTracker.record(key.fingerprint, QuotaBucket.MAIN, 1)
            if (outcome.snapshots.isNotEmpty()) {
              val stamped = outcome.snapshots.map { it.copy(timestamp = now) }
              velocityRepository.upsertSnapshots(stamped)
            }
          }
          is ChunkOutcome.QuotaOrKey -> {
            when {
              outcome.quotaExceeded -> {
                quotaTracker.markExceeded(
                  key.fingerprint,
                  outcome.bucket ?: QuotaBucket.MAIN
                )
                val next = apiKeyManager.rotateAway(key, outcome.bucket ?: QuotaBucket.MAIN)
                if (next == null) return Result.success() else key = next
              }
              outcome.keyInvalid -> {
                apiKeyManager.discardKey(key.key)
                val next = apiKeyManager.acquireKey(QuotaBucket.MAIN)
                if (next == null) return Result.success() else key = next
              }
              else -> return Result.success()
            }
          }
          is ChunkOutcome.Failure -> {
            Log.w(TAG, "Snapshot chunk failed: ${outcome.reason}")
            // Transient failures: keep going with next chunks/runs.
          }
        }
      }

      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Velocity snapshot worker failed", e)
      Result.retry()
    }
  }

  private suspend fun fetchChunk(ids: List<String>, key: ActiveKey): ChunkOutcome {
    return try {
      val response = youTubeApi.getVideos(
        ids = ids.joinToString(","),
        apiKey = key.key
      )
      if (!response.isSuccessful) {
        val code = response.code()
        val body = response.errorBody()?.string().orEmpty()
        val quota = code == 403 && body.contains("quotaExceeded", ignoreCase = true)
        val keyInvalid = code == 401 || code == 403 &&
          (body.contains("API key", ignoreCase = true) || body.contains("keyInvalid", ignoreCase = true))
        if (quota || keyInvalid) {
          return ChunkOutcome.QuotaOrKey(
            quotaExceeded = quota,
            keyInvalid = keyInvalid,
            bucket = QuotaBucket.MAIN
          )
        }
        return ChunkOutcome.Failure("HTTP $code")
      }
      val items = response.body()?.items.orEmpty()
      val snapshots = items.map { item ->
        ViewSnapshotEntity(
          videoId = item.id,
          timestamp = 0L, // filled by caller
          views = item.statistics?.viewCount?.toLongOrNull() ?: 0L,
          likes = item.statistics?.likeCount?.toLongOrNull() ?: 0L,
          comments = item.statistics?.commentCount?.toLongOrNull() ?: 0L
        )
      }
      ChunkOutcome.Success(snapshots)
    } catch (e: Exception) {
      ChunkOutcome.Failure(e.message ?: "unknown error")
    }
  }

  private sealed interface ChunkOutcome {
    data class Success(val snapshots: List<ViewSnapshotEntity>) : ChunkOutcome
    data class QuotaOrKey(
      val quotaExceeded: Boolean,
      val keyInvalid: Boolean,
      val bucket: QuotaBucket?
    ) : ChunkOutcome
    data class Failure(val reason: String) : ChunkOutcome
  }

  companion object {
    private const val TAG = "VelocitySnapshotWorker"
  }
}
