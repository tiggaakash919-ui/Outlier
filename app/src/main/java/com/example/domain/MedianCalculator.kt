package com.example.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Robust statistics for a channel's recent Shorts.
 *
 * All functions are pure and side-effect free; [Instant] is injectable so the
 * behaviour is fully testable.
 */
object MedianCalculator {
  /** Minimum number of other Shorts required before a baseline is trusted. */
  const val MIN_SAMPLES = 5

  /** Fraction trimmed from EACH tail when computing the trimmed mean. */
  const val DEFAULT_TRIM_FRACTION = 0.10

  data class BaselineStats(
    val median: Long,
    val trimmedMean: Double,
    val p90: Long,
    val sampleSize: Int
  )

  /**
   * Calculates median views of the channel's other recent Shorts.
   * Requires at least 5 other Shorts; returns null otherwise.
   */
  fun calculateMedian(viewsList: List<Long>): Long? {
    if (viewsList.size < MIN_SAMPLES) return null
    val sorted = viewsList.sorted()
    val size = sorted.size
    return if (size % 2 == 1) {
      sorted[size / 2]
    } else {
      (sorted[size / 2 - 1] + sorted[size / 2]) / 2
    }
  }

  /**
   * Mean after dropping [trimFraction] of the samples from BOTH tails.
   * Trimming removes a viral hit or a dead upload from skewing the baseline.
   * Returns null when fewer than [MIN_SAMPLES] samples are available.
   */
  fun calculateTrimmedMean(
    viewsList: List<Long>,
    trimFraction: Double = DEFAULT_TRIM_FRACTION
  ): Double? {
    if (viewsList.size < MIN_SAMPLES) return null
    val sorted = viewsList.sorted()
    val trimCount = floor(sorted.size * trimFraction.coerceIn(0.0, 0.49)).toInt()
    val kept = sorted.subList(trimCount, sorted.size - trimCount)
    if (kept.isEmpty()) return null
    return kept.sumOf { it.toDouble() } / kept.size
  }

  /**
   * Nearest-rank percentile (1..100). p90 near the median means the channel
   * always gets high views; p90 far above it means a true one-off spike.
   * Returns null when fewer than [MIN_SAMPLES] samples are available.
   */
  fun calculatePercentile(
    viewsList: List<Long>,
    percentile: Double = 90.0
  ): Long? {
    if (viewsList.size < MIN_SAMPLES) return null
    val sorted = viewsList.sorted()
    val p = percentile.coerceIn(0.0, 100.0)
    val rank = ceil((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }

  /** Median + trimmed mean + p90 in one pass. Null when the sample is too small. */
  fun calculateBaseline(
    viewsList: List<Long>,
    trimFraction: Double = DEFAULT_TRIM_FRACTION
  ): BaselineStats? {
    if (viewsList.size < MIN_SAMPLES) return null
    val median = calculateMedian(viewsList) ?: return null
    val trimmedMean = calculateTrimmedMean(viewsList, trimFraction) ?: return null
    val p90 = calculatePercentile(viewsList, 90.0) ?: return null
    return BaselineStats(
      median = median,
      trimmedMean = trimmedMean,
      p90 = p90,
      sampleSize = viewsList.size
    )
  }

  /**
   * How many other Shorts on the channel beat [factor]x the baseline.
   * Used for the consistency penalty (>10x) and the one-hit-wonder test (>5x).
   */
  fun countAboveMultiplier(
    viewsList: List<Long>,
    baselineViews: Long,
    factor: Double
  ): Int {
    if (baselineViews <= 0L || factor <= 0.0) return 0
    val threshold = baselineViews.toDouble() * factor
    return viewsList.count { it.toDouble() > threshold }
  }

  /**
   * True when a Short is old enough for its view count to be a fair baseline sample.
   * A missing or unparseable publish date is treated as mature so the sample is not
   * silently shrunk by API gaps.
   */
  fun isMatureEnough(
    publishedAt: String,
    minBaselineAgeHours: Int,
    now: Instant = Instant.now()
  ): Boolean {
    if (minBaselineAgeHours <= 0) return true
    if (publishedAt.isBlank()) return true
    return try {
      val published = Instant.parse(publishedAt)
      ChronoUnit.HOURS.between(published, now) >= minBaselineAgeHours
    } catch (_: Exception) {
      true
    }
  }
}
