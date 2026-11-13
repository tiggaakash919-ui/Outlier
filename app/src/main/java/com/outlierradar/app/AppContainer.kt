package com.outlierradar.app

import android.content.Context
import android.util.Log
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.KeyVault
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.remote.ShortsVerifier
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.data.repository.AiRepository
import com.outlierradar.app.data.repository.ApiKeyManager
import com.outlierradar.app.data.repository.KeyRepository
import com.outlierradar.app.data.repository.OutlierRepository
import com.outlierradar.app.data.repository.QuotaTracker
import com.outlierradar.app.data.repository.SavedRepository
import com.outlierradar.app.data.repository.SearchHistoryRepository
import com.outlierradar.app.data.repository.VelocityRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class AppContainer(private val context: Context) {

  val keyVault: KeyVault by lazy {
    KeyVault()
  }

  val settingsStore: SettingsStore by lazy {
    SettingsStore(context, keyVault)
  }

  val database: AppDatabase by lazy {
    AppDatabase.getDatabase(context)
  }

  private val okHttpClient: OkHttpClient by lazy {
    val keyRedactRegex = Regex("key=[^&\\s]+")
    val bearerRedactRegex = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+")
    val safeLogger = HttpLoggingInterceptor.Logger { message ->
      if (BuildConfig.DEBUG) {
        val redacted = bearerRedactRegex.replace(
          keyRedactRegex.replace(message, "key=REDACTED"),
          "Bearer [REDACTED]"
        )
        Log.d("OutlierHttp", redacted)
      }
    }
    val logging = HttpLoggingInterceptor(safeLogger).apply {
      level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .addInterceptor(logging)
      .build()
  }

  private val moshi: Moshi by lazy {
    Moshi.Builder()
      .add(KotlinJsonAdapterFactory())
      .build()
  }

  private val retrofit: Retrofit by lazy {
    Retrofit.Builder()
      .baseUrl("https://www.googleapis.com/youtube/v3/")
      .client(okHttpClient)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
  }

  val youTubeApi: YouTubeApi by lazy {
    retrofit.create(YouTubeApi::class.java)
  }

  val shortsVerifier: ShortsVerifier by lazy {
    ShortsVerifier()
  }

  val quotaTracker: QuotaTracker by lazy {
    QuotaTracker(database)
  }

  val apiKeyManager: ApiKeyManager by lazy {
    ApiKeyManager(settingsStore, quotaTracker)
  }

  val keyRepository: KeyRepository by lazy {
    KeyRepository(youTubeApi, settingsStore, quotaTracker, apiKeyManager)
  }

  val outlierRepository: OutlierRepository by lazy {
    OutlierRepository(
      youTubeApi = youTubeApi,
      database = database,
      settingsStore = settingsStore,
      quotaTracker = quotaTracker,
      apiKeyManager = apiKeyManager,
      shortsVerifier = shortsVerifier,
      aiRepository = aiRepository
    )
  }

  val savedRepository: SavedRepository by lazy {
    SavedRepository(context, database, velocityRepository)
  }

  val aiRepository: AiRepository by lazy {
    AiRepository(
      database = database,
      settingsStore = settingsStore
    )
  }

  val velocityRepository: VelocityRepository by lazy {
    VelocityRepository(context, database)
  }

  val searchHistoryRepository: SearchHistoryRepository by lazy {
    SearchHistoryRepository(database)
  }
}
