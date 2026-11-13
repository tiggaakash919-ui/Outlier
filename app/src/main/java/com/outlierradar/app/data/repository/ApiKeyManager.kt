package com.outlierradar.app.data.repository

import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.domain.ApiKeyFingerprint
import com.outlierradar.app.domain.ApiKeyUi
import com.outlierradar.app.domain.KeyRotation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The API key currently in use, together with its compacted slot index. */
data class ActiveKey(
  val key: String,
  val index: Int
) {
  val fingerprint: String get() = ApiKeyFingerprint.of(key)
}

/**
 * Owns API key selection and rotation.
 *
 * Every key belongs to a different Google Cloud project, so each one has its
 * own daily quota. When a key runs out (or is invalid) the next usable key is
 * selected automatically and a short notice is emitted for the UI.
 */
class ApiKeyManager(
  private val settingsStore: SettingsStore,
  private val quotaTracker: QuotaTracker
) {
  private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val notices: SharedFlow<String> = _notices.asSharedFlow()

  /** Migrates a pre-v5 single key and reassigns its quota row to the new slot. */
  suspend fun ensureMigrated() {
    settingsStore.migrateLegacyKeyIfNeeded()
    val active = settingsStore.getActiveApiKey() ?: return
    quotaTracker.migrateLegacyQuotaRow(ApiKeyFingerprint.of(active))
    quotaTracker.cleanupOldRows()
  }

  suspend fun activeKey(): ActiveKey? {
    val keys = settingsStore.getApiKeys()
    if (keys.isEmpty()) return null
    val activeIndex = settingsStore.getActiveKeyIndexNow()
    val stored = keys.firstOrNull { it.slot == activeIndex } ?: keys.first()
    return ActiveKey(key = stored.value, index = stored.slot)
  }

  /**
   * Returns the active key when its [bucket] is still usable, otherwise rotates
   * to the next key that is.
   */
  suspend fun acquireKey(bucket: QuotaBucket): ActiveKey? {
    val active = activeKey() ?: return null
    if (quotaTracker.isBucketAvailable(active.fingerprint, bucket)) return active
    return rotateAway(active, bucket)
  }

  /** Marks [failed] unusable and selects the next key that can use [bucket]. */
  suspend fun rotateAway(failed: ActiveKey, bucket: QuotaBucket): ActiveKey? {
    val keys = settingsStore.getApiKeys()
    if (keys.isEmpty()) return null

    // The rotation helper takes a plain predicate, so resolve availability first.
    val availableSlots = mutableSetOf<Int>()
    for (stored in keys) {
      if (stored.slot == failed.index) continue
      if (quotaTracker.isBucketAvailable(ApiKeyFingerprint.of(stored.value), bucket)) {
        availableSlots.add(stored.slot)
      }
    }

    val nextSlot = KeyRotation.nextSlot(
      slots = keys.map { it.slot },
      activeSlot = failed.index,
      isAvailable = { it in availableSlots }
    ) ?: return null

    settingsStore.setActiveKeyIndex(nextSlot)
    _notices.tryEmit(
      "Key ${failed.index + 1} ka quota khatam — Key ${nextSlot + 1} par switch kar diya."
    )
    val next = keys.first { it.slot == nextSlot }
    return ActiveKey(key = next.value, index = next.slot)
  }

  /** Removes a key that Google rejected as invalid. */
  suspend fun discardKey(key: String) {
    val removed = settingsStore.removeApiKeyByValue(key)
    if (!removed) settingsStore.removeActiveApiKey()
  }

  suspend fun maskedKeys(): List<ApiKeyUi> {
    val keys = settingsStore.getApiKeys()
    val active = settingsStore.getActiveKeyIndexNow()
    return keys.map { stored ->
      ApiKeyUi(
        index = stored.slot,
        masked = mask(stored.value),
        isActive = stored.slot == active
      )
    }
  }

  suspend fun setActiveKey(index: Int) {
    settingsStore.setActiveKeyIndex(index)
  }

  private fun mask(key: String): String {
    if (key.length <= 8) return "••••••••"
    return "${key.take(4)}••••••••••••${key.takeLast(4)}"
  }
}
