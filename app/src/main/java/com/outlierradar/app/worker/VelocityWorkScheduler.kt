package com.outlierradar.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object VelocityWorkScheduler {
  const val SNAPSHOT_WORK_NAME = "velocity_snapshot_worker"
  const val CHANNEL_WATCH_WORK_NAME = "channel_watch_worker"

  fun ensureScheduled(context: Context) {
    val wm = WorkManager.getInstance(context)
    val network = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build()

    val snapshotRequest = PeriodicWorkRequestBuilder<VelocitySnapshotWorker>(6, TimeUnit.HOURS)
      .setConstraints(network)
      .addTag(SNAPSHOT_WORK_NAME)
      .build()

    val channelRequest = PeriodicWorkRequestBuilder<WatchedChannelWorker>(1, TimeUnit.DAYS)
      .setConstraints(network)
      .addTag(CHANNEL_WATCH_WORK_NAME)
      .build()

    wm.enqueueUniquePeriodicWork(
      SNAPSHOT_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      snapshotRequest
    )
    wm.enqueueUniquePeriodicWork(
      CHANNEL_WATCH_WORK_NAME,
      ExistingPeriodicWorkPolicy.UPDATE,
      channelRequest
    )
  }
}
