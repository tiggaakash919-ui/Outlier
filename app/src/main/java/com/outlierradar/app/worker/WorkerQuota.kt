package com.outlierradar.app.worker

import com.outlierradar.app.data.repository.ActiveKey
import com.outlierradar.app.data.repository.ApiKeyManager
import com.outlierradar.app.data.repository.QuotaBucket
import com.outlierradar.app.data.repository.QuotaTracker

object WorkerQuota {
  const val MIN_MAIN_UNITS_REMAINING = 1000

  /**
   * Picks a key that can be used for MAIN-bucket calls and has at least
   * [MIN_MAIN_UNITS_REMAINING] units left. Returns null when quota should be
   * conserved (worker must skip).
   */
  suspend fun acquireKeyWithHeadroom(
    apiKeyManager: ApiKeyManager,
    quotaTracker: QuotaTracker
  ): ActiveKey? {
    val start = apiKeyManager.acquireKey(QuotaBucket.MAIN) ?: return null
    var key: ActiveKey = start
    repeat(5) {
      val remaining = quotaTracker.getRemainingMainUnits(key.fingerprint)
      if (remaining >= MIN_MAIN_UNITS_REMAINING) return key
      val next = apiKeyManager.rotateAway(key, QuotaBucket.MAIN) ?: return null
      key = next
    }
    return null
  }
}
