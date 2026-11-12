package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "outlier_settings")

class SettingsStore(
  private val context: Context,
  private val keyVault: KeyVault
) {
  private val KEY_ENCRYPTED_API_KEY = stringPreferencesKey("encrypted_api_key")
  private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
  private val KEY_AI_LANGUAGE = stringPreferencesKey("ai_language")
  private val KEY_UI_LANGUAGE = stringPreferencesKey("ui_language")
  private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

  val hasApiKey: Flow<Boolean> = context.dataStore.data.map { prefs ->
    val cipherText = prefs[KEY_ENCRYPTED_API_KEY]
    !cipherText.isNullOrBlank()
  }

  val geminiModel: Flow<String> = context.dataStore.data.map { prefs ->
    val saved = prefs[KEY_GEMINI_MODEL]
    if (saved.isNullOrBlank() || saved == "gemini-2.5-flash") "gemini-3.8-flash" else saved
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

  suspend fun getDecryptedApiKey(): String? {
    val prefs = context.dataStore.data.firstOrNull() ?: return null
    val cipherText = prefs[KEY_ENCRYPTED_API_KEY] ?: return null
    return try {
      keyVault.decrypt(cipherText)
    } catch (_: Exception) {
      null
    }
  }

  suspend fun saveApiKey(rawKey: String) {
    val trimmed = rawKey.trim()
    val cipherText = keyVault.encrypt(trimmed)
    context.dataStore.edit { prefs ->
      prefs[KEY_ENCRYPTED_API_KEY] = cipherText
    }
  }

  suspend fun clearApiKey() {
    context.dataStore.edit { prefs ->
      prefs.remove(KEY_ENCRYPTED_API_KEY)
    }
  }

  suspend fun setGeminiModel(model: String) {
    context.dataStore.edit { prefs ->
      prefs[KEY_GEMINI_MODEL] = model.trim()
    }
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
