package com.example.data.repository

import com.example.data.local.AddApiKeyResult
import com.example.data.local.SettingsStore
import com.example.data.remote.GoogleErrorEnvelope
import com.example.data.remote.YouTubeApi
import com.example.domain.ApiKeyFingerprint
import com.example.domain.ApiKeyUi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

sealed class KeyValidationResult {
  object Success : KeyValidationResult()
  object QuotaExceededSaved : KeyValidationResult()
  object LimitReached : KeyValidationResult()
  data class Error(val userMessage: String) : KeyValidationResult()
}

class KeyRepository(
  private val youTubeApi: YouTubeApi,
  private val settingsStore: SettingsStore,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager
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

  private fun stripKey(message: String, key: String): String {
    if (key.isBlank()) return message
    return message.replace(key, "[REDACTED]")
  }

  /** Tests the active key without changing the key list. */
  suspend fun testActiveKey(): KeyValidationResult = withContext(Dispatchers.IO) {
    val savedKey = settingsStore.getActiveApiKey()
    if (savedKey.isNullOrBlank()) {
      return@withContext KeyValidationResult.Error("Koi API key save nahi hai")
    }
    return@withContext testKey(savedKey, saveMode = SaveMode.NONE)
  }

  /** Legacy name kept for the onboarding flow. */
  suspend fun testSavedKey(): KeyValidationResult = testActiveKey()

  /** Validates a new key and appends it to the key list. */
  suspend fun addKey(rawKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
    val trimmed = rawKey.trim()
    val formatError = validateFormat(trimmed)
    if (formatError != null) return@withContext KeyValidationResult.Error(formatError)

    val existing = settingsStore.getApiKeys()
    val alreadyStored = existing.any { it.value == trimmed }
    if (!alreadyStored && existing.size >= SettingsStore.MAX_API_KEYS) {
      return@withContext KeyValidationResult.LimitReached
    }
    return@withContext testKey(trimmed, SaveMode.ADD)
  }

  /** Legacy entry point used by onboarding: validates and adds the key. */
  suspend fun testAndSaveKey(rawKey: String): KeyValidationResult = addKey(rawKey)

  /** Validates a key and overwrites the currently active slot. */
  suspend fun replaceActiveKey(rawKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
    val trimmed = rawKey.trim()
    val formatError = validateFormat(trimmed)
    if (formatError != null) return@withContext KeyValidationResult.Error(formatError)
    return@withContext testKey(trimmed, SaveMode.REPLACE_ACTIVE)
  }

  /** Legacy entry point used by the old settings dialog. */
  suspend fun testAndSaveKeyReplacingActive(rawKey: String): KeyValidationResult =
    replaceActiveKey(rawKey)

  private suspend fun testKey(rawKey: String, saveMode: SaveMode): KeyValidationResult {
    return try {
      // 1 unit test call: channels.list?part=id&id=UC_x5XG1OV2P6uZZ5FSM9Ttw
      val response = youTubeApi.getChannels(
        part = "id",
        ids = "UC_x5XG1OV2P6uZZ5FSM9Ttw",
        apiKey = rawKey
      )

      val fingerprint = ApiKeyFingerprint.of(rawKey)
      if (response.isSuccessful) {
        quotaTracker.recordMainUnits(fingerprint, 1)
        save(rawKey, saveMode)
        return KeyValidationResult.Success
      }

      val errorBody = response.errorBody()?.string()
      val errorEnvelope = try {
        if (!errorBody.isNullOrBlank()) errorAdapter.fromJson(errorBody) else null
      } catch (_: Exception) {
        null
      }

      val reason = errorEnvelope?.error?.errors?.firstOrNull()?.reason ?: ""
      val message = stripKey(errorEnvelope?.error?.message ?: "", rawKey)

      return when {
        response.code() == 400 || reason == "keyInvalid" ||
          message.contains("API key not valid", ignoreCase = true) -> {
          KeyValidationResult.Error("API key galat hai, dobara check karo")
        }
        response.code() == 403 &&
          (reason == "accessNotConfigured" ||
            message.contains("SERVICE_DISABLED", ignoreCase = true)) -> {
          KeyValidationResult.Error("Google Cloud me 'YouTube Data API v3' enable karo")
        }
        response.code() == 403 &&
          (reason == "API_KEY_SERVICE_BLOCKED" ||
            reason.contains("restricted", ignoreCase = true) ||
            message.contains("blocked", ignoreCase = true)) -> {
          KeyValidationResult.Error("Key restriction me YouTube Data API v3 allow karo")
        }
        response.code() == 403 && (reason == "quotaExceeded" || reason == "dailyLimitExceeded") -> {
          save(rawKey, saveMode)
          quotaTracker.markExceeded(fingerprint, QuotaBucket.MAIN)
          KeyValidationResult.QuotaExceededSaved
        }
        else -> {
          val fallbackMsg = if (reason.isNotBlank()) "API error: $reason" else "HTTP ${response.code()}"
          KeyValidationResult.Error(stripKey(fallbackMsg, rawKey))
        }
      }
    } catch (e: UnknownHostException) {
      KeyValidationResult.Error("Internet check karo")
    } catch (e: IOException) {
      KeyValidationResult.Error("Internet check karo")
    } catch (e: Exception) {
      val safeMsg = stripKey(e.message ?: "Unknown error", rawKey)
      KeyValidationResult.Error("Verification failed: $safeMsg")
    }
  }

  private suspend fun save(rawKey: String, saveMode: SaveMode) {
    when (saveMode) {
      SaveMode.NONE -> Unit
      SaveMode.ADD -> {
        when (settingsStore.addApiKey(rawKey)) {
          is AddApiKeyResult.Added,
          is AddApiKeyResult.AlreadyExists -> Unit
          AddApiKeyResult.LimitReached -> Unit
        }
      }
      SaveMode.REPLACE_ACTIVE -> settingsStore.replaceActiveApiKey(rawKey)
    }
  }

  suspend fun getMaskedKey(): String? {
    val keys = apiKeyManager.maskedKeys()
    return keys.firstOrNull { it.isActive }?.masked ?: keys.firstOrNull()?.masked
  }

  suspend fun getMaskedKeys(): List<ApiKeyUi> = apiKeyManager.maskedKeys()

  suspend fun setActiveKey(index: Int) = apiKeyManager.setActiveKey(index)

  suspend fun removeKeyAt(index: Int) {
    settingsStore.removeApiKeyAt(index)
  }

  suspend fun removeKey() {
    settingsStore.removeActiveApiKey()
  }

  private enum class SaveMode { NONE, ADD, REPLACE_ACTIVE }
}
