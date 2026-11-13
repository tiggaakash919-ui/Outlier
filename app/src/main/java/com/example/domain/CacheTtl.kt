package com.example.domain

/** Cache freshness helper. A zero/negative timestamp is always stale. */
object CacheTtl {
  const val SUBS_MS = 24L * 60L * 60L * 1000L          // 24h
  const val CHANNEL_MEDIAN_MS = 72L * 60L * 60L * 1000L // 72h
  const val VIDEO_STATS_MS = 6L * 60L * 60L * 1000L     // 6h
  const val SEARCH_RESULTS_MS = 24L * 60L * 60L * 1000L // 24h

  /**
   * Failed channels (too few Shorts, playlist missing, deleted channel) are
   * re-checked once a day instead of on every search.
   */
  const val NEGATIVE_STATUS_MS = 24L * 60L * 60L * 1000L // 24h

  fun isFresh(nowMs: Long, cachedAtMs: Long, ttlMs: Long): Boolean {
    if (cachedAtMs <= 0L) return false
    val age = nowMs - cachedAtMs
    return age >= 0L && age < ttlMs
  }
}
