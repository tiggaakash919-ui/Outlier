package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.QuotaUsageEntity
import com.example.domain.QuotaStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

sealed class QuotaCheckResult {
  object Allowed : QuotaCheckResult()
  data class Blocked(val reason: String) : QuotaCheckResult()
}

class QuotaTracker(
  private val database: AppDatabase
) {
  private val quotaDao = database.quotaDao()
  private val pacificZone = ZoneId.of("America/Los_Angeles")

  fun getTodayDateKey(): String {
    return LocalDate.now(pacificZone).toString()
  }

  fun getQuotaStatusFlow(): Flow<QuotaStatus> {
    val today = getTodayDateKey()
    return quotaDao.getQuotaFlow(today).map { entity ->
      val resetTime = getLocalResetTimeString()
      QuotaStatus(
        searchCalls = entity?.searchCalls ?: 0,
        searchLimit = 100,
        mainUnits = entity?.mainUnits ?: 0,
        mainLimit = 10_000,
        isSearchExceeded = entity?.isSearchExceeded ?: false,
        isMainExceeded = entity?.isMainExceeded ?: false,
        resetTimeLocal = resetTime
      )
    }
  }

  suspend fun getCurrentQuota(): QuotaUsageEntity {
    val today = getTodayDateKey()
    return quotaDao.getQuota(today) ?: QuotaUsageEntity(dateString = today)
  }

  fun getLocalResetTimeString(): String {
    val nowPacific = ZonedDateTime.now(pacificZone)
    val nextMidnightPacific = nowPacific.toLocalDate().plusDays(1).atStartOfDay(pacificZone)
    val localReset = nextMidnightPacific.withZoneSameInstant(ZoneId.systemDefault())
    val formatter = DateTimeFormatter.ofPattern("h:mm a (z)")
    return localReset.format(formatter)
  }

  /**
   * Checks if a search with given pages and estimated units can proceed.
   * Blocks if searchCalls + pages > 95 or mainUnits + estimate > 9,500.
   */
  suspend fun canAffordSearch(pages: Int, estimatedUnits: Int): QuotaCheckResult {
    val current = getCurrentQuota()
    if (current.isSearchExceeded) {
      return QuotaCheckResult.Blocked("Search quota exhausted today. Resets at ${getLocalResetTimeString()}.")
    }
    if (current.isMainExceeded) {
      return QuotaCheckResult.Blocked("Main units quota exhausted today. Resets at ${getLocalResetTimeString()}.")
    }

    if (current.searchCalls + pages > 95) {
      return QuotaCheckResult.Blocked("Search quota limit reached (safety limit 95/100). Resets at ${getLocalResetTimeString()}.")
    }

    if (current.mainUnits + estimatedUnits > 9_500) {
      return QuotaCheckResult.Blocked("Main API units limit reached (safety limit 9,500/10,000). Resets at ${getLocalResetTimeString()}.")
    }

    return QuotaCheckResult.Allowed
  }

  suspend fun recordSearchCall(pagesCount: Int = 1) {
    val today = getTodayDateKey()
    val current = quotaDao.getQuota(today) ?: QuotaUsageEntity(dateString = today)
    val updated = current.copy(searchCalls = current.searchCalls + pagesCount)
    quotaDao.upsertQuota(updated)
  }

  suspend fun recordMainUnits(units: Int) {
    if (units <= 0) return
    val today = getTodayDateKey()
    val current = quotaDao.getQuota(today) ?: QuotaUsageEntity(dateString = today)
    val updated = current.copy(mainUnits = current.mainUnits + units)
    quotaDao.upsertQuota(updated)
  }

  suspend fun markSearchExceeded() {
    val today = getTodayDateKey()
    val current = quotaDao.getQuota(today) ?: QuotaUsageEntity(dateString = today)
    val updated = current.copy(isSearchExceeded = true)
    quotaDao.upsertQuota(updated)
  }

  suspend fun markMainExceeded() {
    val today = getTodayDateKey()
    val current = quotaDao.getQuota(today) ?: QuotaUsageEntity(dateString = today)
    val updated = current.copy(isMainExceeded = true)
    quotaDao.upsertQuota(updated)
  }
}
