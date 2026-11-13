package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.QuotaUsageEntity
import com.example.domain.KeyQuotaUsage
import com.example.domain.QuotaStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class QuotaCheckResult {
  data object Allowed : QuotaCheckResult()
  data class Blocked(val reason: String, val bucket: QuotaBucket) : QuotaCheckResult()
}

/**
 * The two buckets the YouTube Data API uses since the June 2026 quota change:
 *  - [SEARCH]: `search.list`, 100 calls/day, 1 unit per call.
 *  - [MAIN]: everything else (videos/channels/playlistItems), 10,000 units/day.
 * Both reset at midnight Pacific. Each API key is a separate Google Cloud
 * project, so counters are stored per key fingerprint.
 */
enum class QuotaBucket { SEARCH, MAIN }

class QuotaTracker(
  private val database: AppDatabase
) {
  private val quotaDao = database.quotaDao()
  private val pacificZone = ZoneId.of("America/Los_Angeles")

  companion object {
    const val SEARCH_DAILY_LIMIT = 100
    const val MAIN_DAILY_LIMIT = 10_000

    /** Safety margins so a burst of requests cannot overshoot the hard limits. */
    private const val SEARCH_SAFETY_LIMIT = 95
    private const val MAIN_SAFETY_LIMIT = 9_500
    private const val DATE_TICKER_MS = 60_000L
  }

  fun getTodayDateKey(): String = LocalDate.now(pacificZone).toString()

  /**
   * Quota badge state for the active key, plus per-key rows for Settings.
   * A minute ticker keeps the "today" key correct across the midnight PT reset.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun getQuotaStatusFlow(
    fingerprintsFlow: Flow<List<String>>,
    activeIndexFlow: Flow<Int>
  ): Flow<QuotaStatus> {
    val dateTicker = flow {
      while (true) {
        emit(getTodayDateKey())
        delay(DATE_TICKER_MS)
      }
    }
    return combine(fingerprintsFlow, activeIndexFlow, dateTicker) { fingerprints, activeIndex, date ->
      Triple(fingerprints, activeIndex, date)
    }.flatMapLatest { (fingerprints, activeIndex, date) ->
      quotaDao.getQuotaRowsFlow(date).map { rows ->
        buildStatus(fingerprints, activeIndex, rows)
      }
    }
  }

  private fun buildStatus(
    fingerprints: List<String>,
    activeIndex: Int,
    rows: List<QuotaUsageEntity>
  ): QuotaStatus {
    val byFingerprint = rows.associateBy { it.keyFingerprint }
    val keyCount = fingerprints.size
    val safeActiveIndex = if (keyCount == 0) 0 else activeIndex.coerceIn(0, keyCount - 1)
    val activeRow = fingerprints.getOrNull(safeActiveIndex)?.let { byFingerprint[it] }
    val keyUsages = fingerprints.mapIndexed { index, fingerprint ->
      val row = byFingerprint[fingerprint]
      KeyQuotaUsage(
        index = index,
        searchCalls = row?.searchCalls ?: 0,
        mainUnits = row?.mainUnits ?: 0,
        isSearchExceeded = row?.isSearchExceeded ?: false,
        isMainExceeded = row?.isMainExceeded ?: false
      )
    }
    val usableKeys = fingerprints.count { fingerprint ->
      val row = byFingerprint[fingerprint]
      row == null || (!row.isSearchExceeded && !row.isMainExceeded)
    }
    return QuotaStatus(
      searchCalls = activeRow?.searchCalls ?: 0,
      searchLimit = SEARCH_DAILY_LIMIT,
      mainUnits = activeRow?.mainUnits ?: 0,
      mainLimit = MAIN_DAILY_LIMIT,
      isSearchExceeded = activeRow?.isSearchExceeded ?: false,
      isMainExceeded = activeRow?.isMainExceeded ?: false,
      resetTimeLocal = getLocalResetTimeString(),
      activeKeyIndex = safeActiveIndex,
      keyCount = keyCount,
      keyUsages = keyUsages,
      // No keys means onboarding, not an exhausted quota.
      hasUsableKey = keyCount == 0 || usableKeys > 0
    )
  }

  suspend fun getCurrentQuota(fingerprint: String): QuotaUsageEntity {
    val today = getTodayDateKey()
    return quotaDao.getQuota(today, fingerprint)
      ?: QuotaUsageEntity(dateString = today, keyFingerprint = fingerprint)
  }

  suspend fun getRemainingMainUnits(fingerprint: String): Int {
    val current = getCurrentQuota(fingerprint)
    return (MAIN_DAILY_LIMIT - current.mainUnits).coerceAtLeast(0)
  }

  suspend fun getRemainingSearchCalls(fingerprint: String): Int {
    val current = getCurrentQuota(fingerprint)
    return (SEARCH_DAILY_LIMIT - current.searchCalls).coerceAtLeast(0)
  }

  fun getLocalResetTimeString(): String {
    val nowPacific = ZonedDateTime.now(pacificZone)
    val nextMidnightPacific = nowPacific.toLocalDate().plusDays(1).atStartOfDay(pacificZone)
    val localReset = nextMidnightPacific.withZoneSameInstant(ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("h:mm a (z)")
    return localReset.format(formatter)
  }

  /**
   * Checks the active key's buckets before a search starts.
   * Sizes are [pages] search calls and [estimatedUnits] main units.
   */
  suspend fun canAffordSearch(
    fingerprint: String,
    pages: Int,
    estimatedUnits: Int
  ): QuotaCheckResult {
    val current = getCurrentQuota(fingerprint)
    val reset = getLocalResetTimeString()

    if (current.isSearchExceeded) {
      return QuotaCheckResult.Blocked(
        "Search quota (100 calls) exhausted for this key. Resets at $reset.",
        QuotaBucket.SEARCH
      )
    }
    if (current.isMainExceeded) {
      return QuotaCheckResult.Blocked(
        "Other API units (10,000) exhausted for this key. Resets at $reset.",
        QuotaBucket.MAIN
      )
    }
    if (current.searchCalls + pages > SEARCH_SAFETY_LIMIT) {
      return QuotaCheckResult.Blocked(
        "Search quota limit reached (safety limit $SEARCH_SAFETY_LIMIT/$SEARCH_DAILY_LIMIT calls). " +
          "Resets at $reset.",
        QuotaBucket.SEARCH
      )
    }
    if (current.mainUnits + estimatedUnits > MAIN_SAFETY_LIMIT) {
      return QuotaCheckResult.Blocked(
        "API units limit reached (safety limit $MAIN_SAFETY_LIMIT/$MAIN_DAILY_LIMIT units). " +
          "Resets at $reset.",
        QuotaBucket.MAIN
      )
    }
    return QuotaCheckResult.Allowed
  }

  suspend fun isBucketAvailable(fingerprint: String, bucket: QuotaBucket): Boolean {
    val current = getCurrentQuota(fingerprint)
    return when (bucket) {
      QuotaBucket.SEARCH -> !current.isSearchExceeded
      QuotaBucket.MAIN -> !current.isMainExceeded
    }
  }

  suspend fun record(fingerprint: String, bucket: QuotaBucket, units: Int = 1) {
    when (bucket) {
      QuotaBucket.SEARCH -> recordSearchCalls(fingerprint, units)
      QuotaBucket.MAIN -> recordMainUnits(fingerprint, units)
    }
  }

  /** search.list is billed per call in its own 100/day bucket. */
  suspend fun recordSearchCalls(fingerprint: String, count: Int = 1) {
    if (count <= 0) return
    val today = getTodayDateKey()
    ensureRow(today, fingerprint)
    quotaDao.addSearchCalls(today, fingerprint, count)
  }

  /** videos.list / channels.list / playlistItems.list share the 10,000 unit pool. */
  suspend fun recordMainUnits(fingerprint: String, units: Int = 1) {
    if (units <= 0) return
    val today = getTodayDateKey()
    ensureRow(today, fingerprint)
    quotaDao.addMainUnits(today, fingerprint, units)
  }

  suspend fun markExceeded(fingerprint: String, bucket: QuotaBucket) {
    val today = getTodayDateKey()
    ensureRow(today, fingerprint)
    when (bucket) {
      QuotaBucket.SEARCH -> quotaDao.markSearchExceeded(today, fingerprint)
      QuotaBucket.MAIN -> quotaDao.markMainExceeded(today, fingerprint)
    }
  }

  /** Reassigns the pre-v5 global row to the key that is active right now. */
  suspend fun migrateLegacyQuotaRow(fingerprint: String) {
    if (fingerprint.isBlank()) return
    val today = getTodayDateKey()
    val legacy = quotaDao.getQuota(today, "") ?: return
    ensureRow(today, fingerprint)
    quotaDao.addSearchCalls(today, fingerprint, legacy.searchCalls)
    quotaDao.addMainUnits(today, fingerprint, legacy.mainUnits)
    if (legacy.isSearchExceeded) quotaDao.markSearchExceeded(today, fingerprint)
    if (legacy.isMainExceeded) quotaDao.markMainExceeded(today, fingerprint)
    quotaDao.deleteLegacyRow(today)
  }

  suspend fun cleanupOldRows(keepDays: Long = 7) {
    val cutoff = LocalDate.now(pacificZone).minusDays(keepDays).toString()
    quotaDao.deleteQuotaBefore(cutoff)
  }

  private suspend fun ensureRow(dateString: String, fingerprint: String) {
    quotaDao.insertIfAbsent(
      QuotaUsageEntity(dateString = dateString, keyFingerprint = fingerprint)
    )
  }
}
