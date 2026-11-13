package com.outlierradar.app.data.remote

import com.outlierradar.app.domain.ShortStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ShortsVerifier(
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()
) {
  open suspend fun verifyShort(videoId: String): ShortStatus = withContext(Dispatchers.IO) {
    try {
      val request = Request.Builder()
        .url("https://www.youtube.com/shorts/$videoId")
        .head()
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K)")
        .build()

      okHttpClient.newCall(request).execute().use { response ->
        when (response.code) {
          200 -> ShortStatus.CONFIRMED_SHORT
          in 300..399 -> ShortStatus.NOT_A_SHORT
          else -> ShortStatus.UNVERIFIED // Fail open on 4xx/5xx/unexpected
        }
      }
    } catch (_: Exception) {
      // Fail open on network errors/timeout
      ShortStatus.UNVERIFIED
    }
  }
}
