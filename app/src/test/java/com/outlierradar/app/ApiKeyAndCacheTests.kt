package com.outlierradar.app

import com.outlierradar.app.domain.ApiKeyFingerprint
import com.outlierradar.app.domain.CacheTtl
import com.outlierradar.app.domain.CostEstimator
import com.outlierradar.app.domain.KeyQuotaUsage
import com.outlierradar.app.domain.KeyRotation
import com.outlierradar.app.domain.QuotaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyAndCacheTests {

  @Test
  fun testApiKeyFingerprintIsStableAndShort() {
    val key = "AIzaSyA1234567890abcdefghijklmnopqrstu"
    val first = ApiKeyFingerprint.of(key)
    val second = ApiKeyFingerprint.of(key)

    assertEquals(first, second)
    assertEquals(12, first.length)
    assertTrue(first.all { it in "0123456789abcdef" })
    assertNotEquals(first, ApiKeyFingerprint.of("AIzaSyB1234567890abcdefghijklmnopqrstu"))
    assertEquals("", ApiKeyFingerprint.of(""))
  }

  @Test
  fun testCacheTtlFreshness() {
    val now = 1_000_000_000L

    assertTrue(CacheTtl.isFresh(now, now - 60_000L, CacheTtl.VIDEO_STATS_MS))
    assertFalse(CacheTtl.isFresh(now, now - CacheTtl.VIDEO_STATS_MS, CacheTtl.VIDEO_STATS_MS))
    assertFalse(CacheTtl.isFresh(now, 0L, CacheTtl.SUBS_MS))
    // A clock that moved backwards must not treat the row as fresh.
    assertFalse(CacheTtl.isFresh(now, now + 60_000L, CacheTtl.SUBS_MS))

    assertTrue(CacheTtl.CHANNEL_MEDIAN_MS > CacheTtl.SUBS_MS)
    assertTrue(CacheTtl.SUBS_MS > CacheTtl.VIDEO_STATS_MS)
  }

  @Test
  fun testCostEstimatorThreshold() {
    val cheap = CostEstimator.estimate(uncachedChannels = 100, cachedChannels = 20, remainingUnits = 9000)
    assertEquals(300, cheap.estimatedUnits)
    assertFalse(cheap.needsConfirmation)
    assertFalse(cheap.worstCaseExceedsRemaining)

    val expensive = CostEstimator.estimate(uncachedChannels = 200, cachedChannels = 20, remainingUnits = 400)
    assertEquals(600, expensive.estimatedUnits)
    assertTrue(expensive.needsConfirmation)
    assertTrue(expensive.worstCaseExceedsRemaining)

    val none = CostEstimator.estimate(0, 0, 10_000)
    assertEquals(0, none.estimatedUnits)
    assertFalse(none.needsConfirmation)
  }

  @Test
  fun testKeyRotationPicksNextAvailableSlot() {
    // 0 -> 1, 1 -> 2, 2 wraps to 0
    assertEquals(1, KeyRotation.nextSlot(listOf(0, 1, 2), 0) { true })
    assertEquals(2, KeyRotation.nextSlot(listOf(0, 1, 2), 1) { true })
    assertEquals(0, KeyRotation.nextSlot(listOf(0, 1, 2), 2) { true })

    // Slot 1 is exhausted, so 0 jumps to 2.
    assertEquals(2, KeyRotation.nextSlot(listOf(0, 1, 2), 0) { it != 1 })

    // Every other slot is unavailable.
    assertNull(KeyRotation.nextSlot(listOf(0, 1, 2), 1) { it == 1 })

    // No keys at all.
    assertNull(KeyRotation.nextSlot(emptyList(), 0) { true })
  }

  @Test
  fun testQuotaBadgeShowsActiveKey() {
    val status = QuotaStatus(
      mainUnits = 1234,
      searchCalls = 7,
      activeKeyIndex = 1,
      keyCount = 3,
      keyUsages = listOf(
        KeyQuotaUsage(index = 0, mainUnits = 100, isMainExceeded = true),
        KeyQuotaUsage(index = 1, mainUnits = 1234, searchCalls = 7),
        KeyQuotaUsage(index = 2)
      )
    )

    assertEquals("1234 / 10000 units · Search 7/100 · Key 2/3", status.badgeText)
    assertTrue(status.hasUsableKey)
    assertFalse(status.isActiveKeyExhausted)

    val exhausted = status.copy(
      isMainExceeded = true,
      activeKeyIndex = 0,
      hasUsableKey = false,
      keyUsages = status.keyUsages.map { it.copy(isMainExceeded = true) }
    )
    assertFalse(exhausted.hasUsableKey)
    assertTrue(exhausted.isActiveKeyExhausted)
  }

  @Test
  fun testQuotaStatusWithNoKeysDoesNotBlockOnboarding() {
    val status = QuotaStatus(keyCount = 0)
    assertTrue(status.hasUsableKey)
  }
}
