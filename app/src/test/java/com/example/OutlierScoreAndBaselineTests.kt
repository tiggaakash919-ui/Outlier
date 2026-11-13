package com.example

import com.example.domain.FilterOptions
import com.example.domain.MedianCalculator
import com.example.domain.OutlierCalculator
import com.example.domain.SortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests for the accuracy improvements: robust baseline (trimmed mean + p90),
 * baseline age gating, Double multipliers and the composite OutlierScore.
 */
class OutlierScoreAndBaselineTests {

  private val now = Instant.parse("2026-11-13T00:00:00Z")

  private val tenSamples = listOf(
    100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L
  )

  // ---------------------------------------------------------------- baseline

  @Test
  fun testTrimmedMeanDropsTenPercentFromEachTail() {
    // 10 samples -> floor(10 * 0.10) = 1 dropped per side -> mean(200..900) = 550
    assertEquals(550.0, MedianCalculator.calculateTrimmedMean(tenSamples)!!, 0.001)

    // 20 samples -> 2 dropped per side
    val twenty = (1..20).map { it.toLong() * 100L } // 100..2000
    val trimmed = MedianCalculator.calculateTrimmedMean(twenty)!!
    assertEquals((300L..1800L step 100L).sum().toDouble() / 16.0, trimmed, 0.001)

    // A single huge outlier must not drag the trimmed mean up materially
    val withOneViral = tenSamples.toMutableList().also { it.add(5_000_000L) }
    val trimmedWithViral = MedianCalculator.calculateTrimmedMean(withOneViral)!!
    assertTrue(trimmedWithViral < 1000.0)

    // Too few samples -> null
    assertNull(MedianCalculator.calculateTrimmedMean(listOf(1L, 2L, 3L, 4L)))
  }

  @Test
  fun testPercentileP90() {
    // Nearest rank: ceil(0.90 * 10) = 9 -> sorted[8] = 900
    assertEquals(900L, MedianCalculator.calculatePercentile(tenSamples, 90.0))
    // Median percentile of an odd list
    assertEquals(300L, MedianCalculator.calculatePercentile(listOf(100L, 200L, 300L, 400L, 500L), 50.0))
    // Clamps
    assertEquals(100L, MedianCalculator.calculatePercentile(tenSamples, 0.0))
    assertEquals(1000L, MedianCalculator.calculatePercentile(tenSamples, 100.0))
    assertNull(MedianCalculator.calculatePercentile(listOf(1L, 2L, 3L, 4L)))
  }

  @Test
  fun testBaselineCombinesMedianTrimmedMeanAndP90() {
    val baseline = MedianCalculator.calculateBaseline(tenSamples)!!
    assertEquals(550L, baseline.median)
    assertEquals(550.0, baseline.trimmedMean, 0.001)
    assertEquals(900L, baseline.p90)
    assertEquals(10, baseline.sampleSize)

    // p90 far above the median -> channel normally gets low views (one-off spike)
    val spiky = listOf(100L, 120L, 150L, 180L, 200L, 220L, 900_000L, 1_000_000L, 1_200_000L, 1_500_000L)
    val spikyBaseline = MedianCalculator.calculateBaseline(spiky)!!
    assertTrue(spikyBaseline.p90 > spikyBaseline.median * 100)

    // A consistently high channel keeps p90 close to the median
    val consistent = listOf(900L, 950L, 1000L, 1000L, 1050L, 1100L, 1100L, 1150L, 1200L, 1250L)
    val consistentBaseline = MedianCalculator.calculateBaseline(consistent)!!
    assertTrue(consistentBaseline.p90 < consistentBaseline.median * 2)

    assertNull(MedianCalculator.calculateBaseline(listOf(1L, 2L, 3L, 4L)))
    assertNull(MedianCalculator.calculateBaseline(emptyList()))
  }

  @Test
  fun testCountAboveMultiplier() {
    val views = listOf(500L, 1000L, 5000L, 10_000L, 15_000L)
    // strictly greater than factor * baseline
    assertEquals(2, MedianCalculator.countAboveMultiplier(views, 1000L, 5.0))
    assertEquals(1, MedianCalculator.countAboveMultiplier(views, 1000L, 10.0))
    assertEquals(0, MedianCalculator.countAboveMultiplier(views, 1000L, 100.0))
    assertEquals(0, MedianCalculator.countAboveMultiplier(views, 0L, 5.0))
    assertEquals(0, MedianCalculator.countAboveMultiplier(emptyList(), 1000L, 5.0))
  }

  @Test
  fun testBaselineAgeGating() {
    // 72h old is mature at a 72h threshold, 71h is not
    assertTrue(MedianCalculator.isMatureEnough("2026-11-10T00:00:00Z", 72, now))
    assertFalse(MedianCalculator.isMatureEnough("2026-11-10T01:00:00Z", 72, now))
    assertFalse(MedianCalculator.isMatureEnough("2026-11-12T00:00:00Z", 72, now))
    assertTrue(MedianCalculator.isMatureEnough("2026-11-12T00:00:00Z", 24, now))

    // Unknown dates are kept (never silently shrink the sample)
    assertTrue(MedianCalculator.isMatureEnough("", 72, now))
    assertTrue(MedianCalculator.isMatureEnough("not-a-date", 72, now))

    // Disabled gate
    assertTrue(MedianCalculator.isMatureEnough("2026-11-12T23:00:00Z", 0, now))
  }

  @Test
  fun testFilterOptionsBaselineAgeDefault() {
    assertEquals(72, FilterOptions().minBaselineAgeHours)
    assertEquals(24, FilterOptions().copy(minBaselineAgeHours = 24).minBaselineAgeHours)
    // Changing the threshold must not disturb the rest of the filter set
    val filters = FilterOptions(keyword = "  minecraft  ").copy(minBaselineAgeHours = 168)
    assertEquals("minecraft", filters.withTrimmedKeyword().keyword)
    assertEquals(72, filters.copy(minBaselineAgeHours = 72).minBaselineAgeHours)
  }

  // -------------------------------------------------------------- multiplier

  @Test
  fun testMultiplierIsDouble() {
    assertEquals(8.5, OutlierCalculator.calculateMultiplier(17_000L, 2_000L), 0.0001)
    assertEquals(8.4, OutlierCalculator.calculateMultiplier(16_800L, 2_000L), 0.0001)
    assertEquals(1200.0, OutlierCalculator.calculateMultiplier(1_200_000L, 1_000L), 0.0001)
    assertEquals(0.0, OutlierCalculator.calculateMultiplier(0L, 0L), 0.0001)
    assertEquals(0.0, OutlierCalculator.calculateMultiplier(5_000L, -10L), 0.0001)
  }

  @Test
  fun testFormatMultiplierShowsOneDecimalUnderTen() {
    assertEquals("8.5", OutlierCalculator.formatMultiplier(8.5))
    assertEquals("8.4", OutlierCalculator.formatMultiplier(8.44))
    assertEquals("1.0", OutlierCalculator.formatMultiplier(1.0))
    assertEquals("9.9", OutlierCalculator.formatMultiplier(9.94))
    assertEquals("10", OutlierCalculator.formatMultiplier(10.0))
    assertEquals("120", OutlierCalculator.formatMultiplier(120.4))
    assertEquals("1200", OutlierCalculator.formatMultiplier(1200.0))
    assertEquals("0", OutlierCalculator.formatMultiplier(0.0))
    assertEquals("0", OutlierCalculator.formatMultiplier(-3.0))
    assertEquals("0", OutlierCalculator.formatMultiplier(Double.NaN))
  }

  // ------------------------------------------------------------ outlier score

  @Test
  fun testOutlierScoreReferenceCase() {
    // 50x, 10 views/sub, 10 days old, 1.1% engagement (likes + comments), no penalty
    val score = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 10.0,
      publishedAt = "2026-11-03T00:00:00Z",
      views = 100_000L,
      likes = 1_000L,
      comments = 100L,
      othersAbove10xCount = 0,
      now = now
    )
    assertEquals(52, score)
  }

  @Test
  fun testOutlierScoreClampsTo100ForPerfectCase() {
    val score = OutlierCalculator.calculateOutlierScore(
      multiplier = 1000.0,
      viewsPerSub = 1000.0,
      publishedAt = "2026-11-13T00:00:00Z",
      views = 1_000_000L,
      likes = 80_000L,
      comments = 20_000L,
      othersAbove10xCount = 0,
      now = now
    )
    assertEquals(100, score)

    // Absurd inputs can never exceed 100
    assertEquals(
      100,
      OutlierCalculator.calculateOutlierScore(
        multiplier = 1_000_000.0,
        viewsPerSub = 1_000_000.0,
        publishedAt = "2026-11-13T00:00:00Z",
        views = 10L,
        likes = 10L,
        comments = 10L,
        othersAbove10xCount = 0,
        now = now
      )
    )
  }

  @Test
  fun testOutlierScoreClampsToZeroForWorstCase() {
    val score = OutlierCalculator.calculateOutlierScore(
      multiplier = 1.0,
      viewsPerSub = 0.0,
      publishedAt = "2024-01-01T00:00:00Z",
      views = 0L,
      likes = 0L,
      comments = 0L,
      othersAbove10xCount = 10,
      now = now
    )
    assertEquals(0, score)
  }

  @Test
  fun testOutlierScoreMonotonicInMultiplier() {
    fun scoreFor(multiplier: Double) = OutlierCalculator.calculateOutlierScore(
      multiplier = multiplier,
      viewsPerSub = 5.0,
      publishedAt = "2026-11-10T00:00:00Z",
      views = 100_000L,
      likes = 2_000L,
      comments = 200L,
      othersAbove10xCount = 0,
      now = now
    )
    val s1 = scoreFor(1.0)
    val s10 = scoreFor(10.0)
    val s100 = scoreFor(100.0)
    val s1000 = scoreFor(1000.0)
    assertTrue(s1 < s10)
    assertTrue(s10 < s100)
    assertTrue(s100 < s1000)
  }

  @Test
  fun testOutlierScoreMonotonicInViewsPerSub() {
    fun scoreFor(vps: Double) = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = vps,
      publishedAt = "2026-11-10T00:00:00Z",
      views = 100_000L,
      likes = 2_000L,
      comments = 200L,
      othersAbove10xCount = 0,
      now = now
    )
    assertTrue(scoreFor(0.0) < scoreFor(1.0))
    assertTrue(scoreFor(1.0) < scoreFor(10.0))
    assertTrue(scoreFor(10.0) < scoreFor(100.0))
  }

  @Test
  fun testOutlierScoreMonotonicInEngagement() {
    fun scoreFor(likes: Long, comments: Long) = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 5.0,
      publishedAt = "2026-11-10T00:00:00Z",
      views = 100_000L,
      likes = likes,
      comments = comments,
      othersAbove10xCount = 0,
      now = now
    )
    val none = scoreFor(0L, 0L)
    val low = scoreFor(500L, 50L)
    val high = scoreFor(6_000L, 2_000L)
    val saturated = scoreFor(50_000L, 50_000L) // 100% engagement, clamped
    assertTrue(none < low)
    assertTrue(low < high)
    assertTrue(high <= saturated)
  }

  @Test
  fun testOutlierScoreRecencyBonus() {
    fun scoreAgeDays(days: Long) = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 5.0,
      publishedAt = now.minusSeconds(days * 86_400L).toString(),
      views = 100_000L,
      likes = 2_000L,
      comments = 200L,
      othersAbove10xCount = 0,
      now = now
    )
    val fresh = scoreAgeDays(1)
    val month = scoreAgeDays(30)
    val quarter = scoreAgeDays(90)
    val halfYear = scoreAgeDays(180)
    val old = scoreAgeDays(400)

    assertTrue(fresh > month)
    assertTrue(month > quarter)
    assertTrue(quarter > halfYear)
    assertTrue(halfYear > old)
    // Missing dates score like an old Short, never like a fresh one
    val unknown = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 5.0,
      publishedAt = "",
      views = 100_000L,
      likes = 2_000L,
      comments = 200L,
      othersAbove10xCount = 0,
      now = now
    )
    assertEquals(old, unknown)
  }

  @Test
  fun testOutlierScoreConsistencyPenalty() {
    fun scoreFor(othersAbove10x: Int) = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 10.0,
      publishedAt = "2026-11-03T00:00:00Z",
      views = 100_000L,
      likes = 1_000L,
      comments = 100L,
      othersAbove10xCount = othersAbove10x,
      now = now
    )
    // No penalty / small / medium / large tiers
    assertEquals(52, scoreFor(0))
    assertEquals(47, scoreFor(1))
    assertEquals(47, scoreFor(2))
    assertEquals(42, scoreFor(3))
    assertEquals(42, scoreFor(5))
    assertEquals(37, scoreFor(6))
    assertEquals(37, scoreFor(50))
  }

  @Test
  fun testOutlierScoreP90SpreadPenalty() {
    fun scoreFor(median: Long, p90: Long) = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 10.0,
      publishedAt = "2026-11-03T00:00:00Z",
      views = 100_000L,
      likes = 1_000L,
      comments = 100L,
      othersAbove10xCount = 0,
      channelMedianViews = median,
      channelP90Views = p90,
      now = now
    )

    // Tight channel: p90 close to the median -> no spread penalty
    assertEquals(52, scoreFor(5_000L, 6_000L))
    // p90 >= 5x median -> channel routinely spikes by 5x
    assertEquals(47, scoreFor(5_000L, 25_000L))
    // p90 >= 20x median -> "always high views" channel
    assertEquals(42, scoreFor(5_000L, 100_000L))
    // Unknown/legacy baseline must not penalise
    assertEquals(52, scoreFor(0L, 0L))
    assertEquals(52, scoreFor(5_000L, 0L))

    // Count penalty + spread penalty are capped at -20
    val combined = OutlierCalculator.calculateOutlierScore(
      multiplier = 50.0,
      viewsPerSub = 10.0,
      publishedAt = "2026-11-03T00:00:00Z",
      views = 100_000L,
      likes = 1_000L,
      comments = 100L,
      othersAbove10xCount = 10,
      channelMedianViews = 5_000L,
      channelP90Views = 100_000L,
      now = now
    )
    assertEquals(32, combined)
  }

  @Test
  fun testRecencyFactorBuckets() {
    fun factor(days: Long) =
      OutlierCalculator.recencyFactor(now.minusSeconds(days * 86_400L).toString(), now)
    assertEquals(1.0, factor(0), 0.0001)
    assertEquals(1.0, factor(7), 0.0001)
    assertEquals(0.8, factor(8), 0.0001)
    assertEquals(0.8, factor(30), 0.0001)
    assertEquals(0.6, factor(31), 0.0001)
    assertEquals(0.6, factor(90), 0.0001)
    assertEquals(0.4, factor(91), 0.0001)
    assertEquals(0.4, factor(180), 0.0001)
    assertEquals(0.2, factor(181), 0.0001)
    assertEquals(0.2, OutlierCalculator.recencyFactor("", now), 0.0001)
    assertEquals(0.2, OutlierCalculator.recencyFactor("garbage", now), 0.0001)
  }

  // ---------------------------------------------------------- one-hit wonder

  @Test
  fun testIsOneHitWonder() {
    // >= 20x with no other > 5x Short
    assertTrue(OutlierCalculator.isOneHitWonder(20.0, 0))
    assertTrue(OutlierCalculator.isOneHitWonder(500.0, 0))

    // Below 20x is never a one-hit wonder
    assertFalse(OutlierCalculator.isOneHitWonder(19.9, 0))
    assertFalse(OutlierCalculator.isOneHitWonder(5.0, 0))

    // Another > 5x Short means the channel is trending, not a fluke
    assertFalse(OutlierCalculator.isOneHitWonder(20.0, 1))
    assertFalse(OutlierCalculator.isOneHitWonder(1200.0, 2))
  }

  // --------------------------------------------------------------- sort enum

  @Test
  fun testScoreSortOptionExists() {
    assertTrue(SortOption.entries.any { it == SortOption.SCORE })
    assertEquals("Score", SortOption.SCORE.label)
    // SCORE is the first option, so it is the default selection
    assertEquals(SortOption.SCORE, SortOption.entries.first())
  }
}
