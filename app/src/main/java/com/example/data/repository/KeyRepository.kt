package com.example.data.repository

import com.example.data.local.SettingsStore
import com.example.data.remote.GoogleErrorEnvelope
import com.example.data.remote.YouTubeApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

sealed class KeyValidationResult {
  object Success : KeyValidationResult()
  object QuotaExceededSaved : KeyValidationResult()
  data class Error(val userMessage: String) : KeyValidationResult()
}

class KeyRepository(
  private val youTubeApi: YouTubeApi,
  private val settingsStore: SettingsStore,
  private val quotaTracker: QuotaTracker
) {
  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val errorAdapter = moshi.adapter(GoogleErrorEnvelope::class.java)

  fun validateFormat(key: String): String? {
    val trimmed = key.trim()
    if (!trimmed.startsWith("AIza") || trimmed.length != 39) {
      return "Ye valid YouTube API key nahi lag rahi"
    }
    return null
  }

  suspend fun testAndSaveKey(rawKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
    val trimmed = rawKey.trim()
    val formatError = validateFormat(trimmed)
    if (formatError != null) {
      return@withContext KeyValidationResult.Error(formatError)
    }

    try {
      // 1 unit test call
      val response = youTubeApi.getChannels(
        part = "id",
        ids = "UC_x5XG1OV2P6uZZ5FSM9Ttw",
        apiKey = trimmed
      )

      quotaTracker.recordMainUnits(1)

      if (response.isSuccessful) {
        settingsStore.saveApiKey(trimmed)
        return@withContext KeyValidationResult.Success
      }

      val errorBody = response.errorBody()?.string()
      val errorEnvelope = try {
        if (!errorBody.isNullOrBlank()) errorAdapter.fromJson(errorBody) else null
      } catch (_: Exception) {
        null
      }

      val reason = errorEnvelope?.error?.errors?.firstOrNull()?.reason ?: ""
      val message = errorEnvelope?.error?.message ?: ""

      when {
        response.code() == 400 || reason == "keyInvalid" || message.contains("API key not valid", ignoreCase = true) -> {
          KeyValidationResult.Error("API key galat hai, dobara check karo")
        }
        response.code() == 403 && (reason == "accessNotConfigured" || message.contains("SERVICE_DISABLED", ignoreCase = true)) -> {
          KeyValidationResult.Error("Google Cloud me 'YouTube Data API v3' enable karo")
        }
        response.code() == 403 && (reason == "API_KEY_SERVICE_BLOCKED" || reason.contains("restricted", ignoreCase = true) || message.contains("blocked", ignoreCase = true)) -> {
          KeyValidationResult.Error("Key restriction me YouTube Data API v3 allow karo")
        }
        response.code() == 403 && (reason == "quotaExceeded" || reason == "dailyLimitExceeded") -> {
          settingsStore.saveApiKey(trimmed)
          quotaTracker.markMainExceeded()
          KeyValidationResult.QuotaExceededSaved
        }
        else -> {
          val fallbackMsg = if (reason.isNotBlank()) "API error: $reason" else "HTTP ${response.code()}"
          KeyValidationResult.Error(fallbackMsg)
        }
      }
    } catch (e: UnknownHostException) {
      KeyValidationResult.Error("Internet check karo")
    } catch (e: IOException) {
      KeyValidationResult.Error("Internet check karo")
    } catch (e: Exception) {
      KeyValidationResult.Error("Verification failed: ${e.message ?: "Unknown error"}")
    }
  }

  suspend fun getMaskedKey(): String? {
    val key = settingsStore.getDecryptedApiKey() ?: return null
    if (key.length <= 8) return "••••••••"
    val prefix = key.take(4)
    val suffix = key.takeLast(4)
    return "$prefix••••••••••••$suffix"
  }

  suspend fun removeKey() {
    settingsStore.clearApiKey()
  }
}
