package com.example.domain

import java.util.Locale

/**
 * Stable cache-key builder for every discovery mode. Bumping the `v=` prefix
 * invalidates old rows when the result shape changes.
 */
object DiscoveryCacheKey {
  fun build(filters: FilterOptions, pageToken: String? = null): String {
    val kw = OutlierCalculator.trimKeyword(filters.keyword).lowercase(Locale.ROOT)
    val multi = DiscoveryHelpers.parseKeywordList(filters.multiKeywords)
      .joinToString(",") { it.lowercase(Locale.ROOT) }
    val channel = filters.channelInput.trim().lowercase(Locale.ROOT)
    val excludes = DiscoveryHelpers.parseExcludeKeywords(filters.excludeKeywords).joinToString(",")
    val seedTags = filters.similarSeedTags
      .joinToString(",") { it.trim().lowercase(Locale.ROOT) }
    return "v=3|mode=${filters.mode.name}|kw=$kw|mkw=$multi|ch=$channel" +
      "|seed=${filters.similarSeedVideoId}|seedtags=$seedTags" +
      "|excl=$excludes|lkr=${filters.minLikesRatio}|vage=${filters.maxViralAgeDays}" +
      "|p=${filters.pagesPerSearch}|subs=${filters.minSubs}-${filters.maxSubs}" +
      "|views=${filters.minViralViews}|med=${filters.maxMedianViews}|mul=${filters.minMultiplier}" +
      "|rec=${filters.recency}|reg=${filters.regionCode}|lang=${filters.language}" +
      "|dur=${filters.maxDurationSeconds}|age=${filters.minBaselineAgeHours}" +
      "|token=${pageToken ?: ""}"
  }
}
