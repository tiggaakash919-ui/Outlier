package com.outlierradar.app.data.repository

import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.SearchHistoryEntity
import com.outlierradar.app.domain.DiscoveryMode
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.SearchHistoryEntry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SearchHistoryRepository(
  database: AppDatabase
) {
  private val dao = database.searchHistoryDao()
  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
  private val filtersAdapter = moshi.adapter(FilterOptions::class.java)

  fun recentFlow(limit: Int = 20): Flow<List<SearchHistoryEntry>> =
    dao.getRecentFlow(limit).map { list -> list.mapNotNull { it.toDomain() } }

  suspend fun record(filters: FilterOptions) {
    val clean = filters.withTrimmedKeyword()
    val displayLabel = when (clean.mode) {
      DiscoveryMode.MULTI_KEYWORD -> {
        val keys = com.outlierradar.app.domain.DiscoveryHelpers.parseKeywordList(clean.multiKeywords)
        if (keys.isEmpty()) "Multi-keyword" else "Multi: ${keys.take(3).joinToString(", ")}"
      }
      DiscoveryMode.CHANNEL_SCAN -> "Channel: ${clean.channelInput.take(40)}"
      DiscoveryMode.SIMILAR_TO_OUTLIER -> "Similar: ${clean.similarSeedTitle.take(40).ifBlank { "Short" }}"
      DiscoveryMode.AUTO_EXPAND -> "Auto: ${clean.keyword}"
      DiscoveryMode.KEYWORD -> clean.keyword.ifBlank { "Search" }
    }.ifBlank { "Search" }

    val dedupeKey = buildDedupeKey(clean)
    val json = runCatching { filtersAdapter.toJson(clean) }.getOrNull() ?: return
    val now = System.currentTimeMillis()

    // Dedupe by equivalent query/filter signature and keep most-recent.
    dao.deleteByDedupeKey(dedupeKey)
    dao.insert(
      SearchHistoryEntity(
        timestamp = now,
        displayLabel = displayLabel,
        mode = clean.mode.name,
        filtersJson = json,
        dedupeKey = dedupeKey
      )
    )
    dao.pruneTo(20)
  }

  private fun buildDedupeKey(filters: FilterOptions): String {
    return listOf(
      filters.mode.name,
      filters.keyword,
      filters.multiKeywords,
      filters.channelInput,
      filters.similarSeedVideoId,
      filters.similarSeedTitle,
      filters.minSubs.toString(),
      filters.maxSubs.toString(),
      filters.minViralViews.toString(),
      filters.maxMedianViews.toString(),
      filters.minMultiplier.toString(),
      filters.samplePerChannel.toString(),
      filters.minBaselineAgeHours.toString(),
      filters.recency,
      filters.regionCode,
      filters.language,
      filters.maxDurationSeconds.toString(),
      filters.pagesPerSearch.toString(),
      filters.excludeKeywords,
      filters.minLikesRatio.toString(),
      filters.maxViralAgeDays.toString()
    ).joinToString("|")
  }

  private fun SearchHistoryEntity.toDomain(): SearchHistoryEntry? {
    val parsedFilters = runCatching { filtersAdapter.fromJson(filtersJson) }.getOrNull() ?: return null
    val parsedMode = runCatching { DiscoveryMode.valueOf(mode) }.getOrDefault(parsedFilters.mode)
    return SearchHistoryEntry(
      id = id,
      timestamp = timestamp,
      displayLabel = displayLabel.ifBlank { parsedFilters.keyword.ifBlank { "Search" } },
      mode = parsedMode,
      filters = parsedFilters.copy(mode = parsedMode)
    )
  }

  suspend fun clear() {
    dao.clearAll()
  }
}
