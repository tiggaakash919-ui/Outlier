package com.example.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.AppContainer

class AppWorkerFactory(
  private val container: AppContainer
) : WorkerFactory() {

  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters
  ): ListenableWorker? {
    return when (workerClassName) {
      VelocitySnapshotWorker::class.java.name ->
        VelocitySnapshotWorker(
          appContext = appContext,
          params = workerParameters,
          youTubeApi = container.youTubeApi,
          quotaTracker = container.quotaTracker,
          apiKeyManager = container.apiKeyManager,
          velocityRepository = container.velocityRepository
        )

      WatchedChannelWorker::class.java.name ->
        WatchedChannelWorker(
          appContext = appContext,
          params = workerParameters,
          youTubeApi = container.youTubeApi,
          quotaTracker = container.quotaTracker,
          apiKeyManager = container.apiKeyManager,
          velocityRepository = container.velocityRepository
        )

      else -> null
    }
  }
}
