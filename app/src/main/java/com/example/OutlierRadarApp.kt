package com.example

import android.app.Application
import android.util.Log
import androidx.work.Configuration

class OutlierRadarApp : Application(), Configuration.Provider {

  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    // WorkManager is initialized on-demand (default initializer removed in manifest)
    // so it picks up this Configuration.Provider.
    runCatching {
      com.example.worker.VelocityWorkScheduler.ensureScheduled(this)
    }.onFailure { Log.w("OutlierRadarApp", "Failed to schedule velocity workers", it) }
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      .setWorkerFactory(com.example.worker.AppWorkerFactory(container))
      .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
      .build()
}
