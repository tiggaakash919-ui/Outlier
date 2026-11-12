package com.example

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.KeyVault
import com.example.data.local.SettingsStore
import com.example.data.remote.ShortsVerifier
import com.example.data.remote.YouTubeApi
import com.example.data.repository.KeyRepository
import com.example.data.repository.OutlierRepository
import com.example.data.repository.QuotaTracker
import com.example.data.repository.SavedRepository
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
    val logging = HttpLoggingInterceptor().apply {
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

  val keyRepository: KeyRepository by lazy {
    KeyRepository(youTubeApi, settingsStore, quotaTracker)
  }

  val outlierRepository: OutlierRepository by lazy {
    OutlierRepository(
      youTubeApi = youTubeApi,
      database = database,
      settingsStore = settingsStore,
      quotaTracker = quotaTracker,
      shortsVerifier = shortsVerifier
    )
  }

  val savedRepository: SavedRepository by lazy {
    SavedRepository(database)
  }
}
