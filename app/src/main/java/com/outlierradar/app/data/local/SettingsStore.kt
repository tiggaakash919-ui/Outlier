package com.outlierradar.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.outlierradar.app.domain.ApiKeyFingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "outlier_settings")

/** One decrypted key together with its compacted slot index. */
data class StoredApiKey(
  val slot: Int,
  val value: String
)

sealed class AddApiKeyResult {
  data class Added(val index: Int) : AddApiKeyResult()
  data class AlreadyExists(val index: Int) : AddApiKeyResult()
  data object LimitReached : AddApiKeyResult()
}

class SettingsStore(
  private val context: Context,
  private val keyVault: KeyVault
) {
  private val KEY_ENCRYPTED_API_KEY = stringPreferencesKey("encrypted_api_key") // legacy single key
  private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
  private val KEY_COARENA_MODEL = stringPreferencesKey("coarena_model")
  private val KEY_COARENA_API_KEY = stringPreferencesKey("coarena_api_key_encrypted")
  private val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
  private val KEY_AI_LANGUAGE = stringPreferencesKey("ai_language")
  private val KEY_UI_LANGUAGE = stringPreferencesKey("ui_language")
  private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
  private val KEY_ACTIVE_KEY_INDEX = intPreferencesKey("active_key_index")

  private fun slotCipherKey(index: Int) = stringPreferencesKey("encrypted_api_key_$index")
  private fun slotFingerprintKey(index: Int) = stringPreferencesKey("api_key_fingerprint_$index")

  companion object {
    const val MAX_API_KEYS = 5
  }

  val hasApiKey: Flow<Boolean> = context.dataStore.data.map { prefs ->
    !prefs[KEY_ENCRYPTED_API_KEY].isNullOrBlank() || countKeys(prefs) > 0
  }

  val apiKeyCount: Flow<Int> = context.dataStore.data.map { prefs -> countKeys(prefs) }

  /** Fingerprints of the stored keys, used as per-key quota row ids. */
  val apiKeyFingerprints: Flow<List<String>> = context.dataStore.data.map { prefs ->
    (0 until countKeys(prefs)).mapNotNull { prefs[slotFingerprintKey(it)]?.takeIf(String::isNotBlank) }
  }

  val activeKeyIndex: Flow<Int> = context.dataStore.data.map { prefs ->
    val count = countKeys(prefs)
    if (count == 0) 0 else (prefs[KEY_ACTIVE_KEY_INDEX] ?: 0).coerceIn(0, count - 1)
  }

  val geminiModel: Flow<String> = context.dataStore.data.map { prefs ->
    val saved = prefs[KEY_GEMINI_MODEL]
    if (saved.isNullOrBlank() || saved == "gemini-2.5-flash") "gemini-3.8-flash" else saved
  }

  /** AI backend: COARENA (default) or FIREBASE. */
  val aiProvider: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_AI_PROVIDER]?.takeIf { it.isNotBlank() } ?: "COARENA"
  }

  val coarenaModel: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_COARENA_MODEL]?.takeIf { it.isNotBlank() } ?: "gemini-3-8-flash"
  }

  /** Decrypted Coarena key stored from Settings; empty when not set. */
  val coarenaApiKey: Flow<String> = context.dataStore.data.map { prefs ->
    val cipher = prefs[KEY_COARENA_API_KEY]
    if (cipher.isNullOrBlank()) "" else runCatching { keyVault.decrypt(cipher) }.getOrDefault("")
  }

  val aiLanguage: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_AI_LANGUAGE] ?: "Hinglish"
  }

  val uiLanguage: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_UI_LANGUAGE] ?: "Hinglish"
  }

  val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_THEME_MODE] ?: "DARK" // Dark by default
  }

  private fun countKeys(prefs: Preferences): Int =
    (0 until MAX_API_KEYS).count { !prefs[slotCipherKey(it)].isNullOrBlank() }

  /**
   * Moves a key saved by the old single-key version into slot 0.
   * Safe to call repeatedly.
   */
  suspend fun migrateLegacyKeyIfNeeded() {
    val current = context.dataStore.data.firstOrNull() ?: return
    if (current[KEY_ENCRYPTED_API_KEY] == null) return
    context.dataStore.edit { prefs ->
      val legacy = prefs[KEY_ENCRYPTED_API_KEY]
      if (legacy.isNullOrBlank()) {
        prefs.remove(KEY_ENCRYPTED_API_KEY)
        return@edit
      }
      if (countKeys(prefs) == 0) {
        prefs[slotCipherKey(0)] = legacy
        val plain = runCatching { keyVault.decrypt(legacy) }.getOrNull()
        if (plain != null) prefs[slotFingerprintKey(0)] = ApiKeyFingerprint.of(plain)
        prefs[KEY_ACTIVE_KEY_INDEX] = 0
      }
      prefs.remove(KEY_ENCRYPTED_API_KEY)
    }
  }

  suspend fun getApiKeys(): List<StoredApiKey> {
    migrateLegacyKeyIfNeeded()
    val prefs = context.dataStore.data.firstOrNull() ?: return emptyList()
    val count = countKeys(prefs)
    return (0 until count).mapNotNull { index ->
      val cipher = prefs[slotCipherKey(index)] ?: return@mapNotNull null
      val plain = runCatching { keyVault.decrypt(cipher) }.getOrNull() ?: return@mapNotNull null
      StoredApiKey(slot = index, value = plain)
    }
  }

  suspend fun getActiveKeyIndexNow(): Int {
    val prefs = context.dataStore.data.firstOrNull() ?: return 0
    val count = countKeys(prefs)
    if (count == 0) return 0
    return (prefs[KEY_ACTIVE_KEY_INDEX] ?: 0).coerceIn(0, count - 1)
  }

  suspend fun getActiveApiKey(): String? {
    val keys = getApiKeys()
    if (keys.isEmpty()) return null
    val active = getActiveKeyIndexNow()
    return keys.firstOrNull { it.slot == active }?.value ?: keys.first().value
  }

  suspend fun setActiveKeyIndex(index: Int) {
    context.dataStore.edit { prefs ->
      val count = countKeys(prefs)
      if (count == 0) {
        prefs.remove(KEY_ACTIVE_KEY_INDEX)
      } else {
        prefs[KEY_ACTIVE_KEY_INDEX] = index.coerceIn(0, count - 1)
      }
    }
  }

  /**
   * Adds a key if it is new, otherwise points the active index at the existing
   * copy. Returns [AddApiKeyResult.LimitReached] at [MAX_API_KEYS].
   */
  suspend fun addApiKey(rawKey: String): AddApiKeyResult {
    val trimmed = rawKey.trim()
    if (trimmed.isBlank()) return AddApiKeyResult.LimitReached
    migrateLegacyKeyIfNeeded()
    var result: AddApiKeyResult = AddApiKeyResult.LimitReached
    context.dataStore.edit { prefs ->
      val count = countKeys(prefs)
      val plaintexts = (0 until count).mapNotNull { index ->
        prefs[slotCipherKey(index)]?.let { runCatching { keyVault.decrypt(it) }.getOrNull() }
      }
      val existingIndex = plaintexts.indexOfFirst { it == trimmed }
      if (existingIndex >= 0) {
        prefs[KEY_ACTIVE_KEY_INDEX] = existingIndex
        result = AddApiKeyResult.AlreadyExists(existingIndex)
        return@edit
      }
      if (count >= MAX_API_KEYS) {
        result = AddApiKeyResult.LimitReached
        return@edit
      }
      prefs[slotCipherKey(count)] = keyVault.encrypt(trimmed)
      prefs[slotFingerprintKey(count)] = ApiKeyFingerprint.of(trimmed)
      prefs[KEY_ACTIVE_KEY_INDEX] = count
      result = AddApiKeyResult.Added(count)
    }
    return result
  }

  /** Overwrites the currently active slot (used by "Change active key"). */
  suspend fun replaceActiveApiKey(rawKey: String) {
    val trimmed = rawKey.trim()
    migrateLegacyKeyIfNeeded()
    context.dataStore.edit { prefs ->
      val count = countKeys(prefs)
      val index = if (count == 0) 0 else (prefs[KEY_ACTIVE_KEY_INDEX] ?: 0).coerceIn(0, count - 1)
      prefs[slotCipherKey(index)] = keyVault.encrypt(trimmed)
      prefs[slotFingerprintKey(index)] = ApiKeyFingerprint.of(trimmed)
      prefs[KEY_ACTIVE_KEY_INDEX] = index
    }
  }

  /** Removes the slot at [index] and compacts the remaining slots. */
  suspend fun removeApiKeyAt(index: Int) {
    context.dataStore.edit { prefs ->
      val count = countKeys(prefs)
      if (index !in 0 until count) return@edit
      val remaining = (0 until count)
        .filter { it != index }
        .map { slot -> prefs[slotCipherKey(slot)] to prefs[slotFingerprintKey(slot)] }

      for (slot in 0 until MAX_API_KEYS) {
        prefs.remove(slotCipherKey(slot))
        prefs.remove(slotFingerprintKey(slot))
      }
      remaining.forEachIndexed { newSlot, (cipher, fingerprint) ->
        if (!cipher.isNullOrBlank()) prefs[slotCipherKey(newSlot)] = cipher
        if (!fingerprint.isNullOrBlank()) prefs[slotFingerprintKey(newSlot)] = fingerprint
      }

      if (remaining.isEmpty()) {
        prefs.remove(KEY_ACTIVE_KEY_INDEX)
      } else {
        val currentActive = prefs[KEY_ACTIVE_KEY_INDEX] ?: 0
        val nextActive = when {
          index < currentActive -> currentActive - 1
          else -> currentActive.coerceAtMost(remaining.size - 1)
        }
        prefs[KEY_ACTIVE_KEY_INDEX] = nextActive.coerceIn(0, remaining.size - 1)
      }
    }
  }

  suspend fun removeApiKeyByValue(rawKey: String): Boolean {
    val target = getApiKeys().firstOrNull { it.value == rawKey } ?: return false
    removeApiKeyAt(target.slot)
    return true
  }

  suspend fun removeActiveApiKey() {
    val keys = getApiKeys()
    if (keys.isEmpty()) return
    val active = getActiveKeyIndexNow()
    val target = keys.firstOrNull { it.slot == active } ?: keys.first()
    removeApiKeyAt(target.slot)
  }

  suspend fun removeAllApiKeys() {
    context.dataStore.edit { prefs ->
      prefs.remove(KEY_ENCRYPTED_API_KEY)
      prefs.remove(KEY_ACTIVE_KEY_INDEX)
      for (slot in 0 until MAX_API_KEYS) {
        prefs.remove(slotCipherKey(slot))
        prefs.remove(slotFingerprintKey(slot))
      }
    }
  }

  /** Legacy alias kept for call sites that only remove the active key. */
  suspend fun clearApiKey() = removeActiveApiKey()

  suspend fun setGeminiModel(model: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_GEMINI_MODEL] = model.trim()
    }
  }

  suspend fun setAiProvider(provider: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_AI_PROVIDER] = if (provider.trim().equals("FIREBASE", true)) "FIREBASE" else "COARENA"
    }
  }

  suspend fun setCoarenaModel(model: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_COARENA_MODEL] = model.trim()
    }
  }

  /** Stores the Coarena key encrypted at rest. Blank input removes it. */
  suspend fun setCoarenaApiKey(rawKey: String) {
    val trimmed = rawKey.trim()
    context.dataStore.edit { prefs ->
      if (trimmed.isBlank()) {
        prefs.remove(KEY_COARENA_API_KEY)
      } else {
        prefs[KEY_COARENA_API_KEY] = keyVault.encrypt(trimmed)
      }
    }
  }

  suspend fun getCoarenaApiKeyNow(): String {
    val cipher = context.dataStore.data.firstOrNull()?.get(KEY_COARENA_API_KEY)
    if (cipher.isNullOrBlank()) return ""
    return runCatching { keyVault.decrypt(cipher) }.getOrDefault("")
  }

  suspend fun setAiLanguage(lang: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_AI_LANGUAGE] = lang
    }
  }

  suspend fun setUiLanguage(lang: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_UI_LANGUAGE] = lang
    }
  }

  suspend fun setThemeMode(mode: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_THEME_MODE] = mode
    }
  }

  suspend fun clearAll() {
    context.dataStore.edit { prefs ->
      prefs.clear()
    }
  }
}
