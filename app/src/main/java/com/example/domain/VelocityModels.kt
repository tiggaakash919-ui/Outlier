package com.example.domain

import com.example.data.local.ViewSnapshotEntity

enum class VelocityTrend {
  RISING,
  PEAKED,
  SLOW
}

data class VelocityState(
  val label: String,
  val trend: VelocityTrend,
  val viewsGained24h: Long,
  val latestViews: Long,
  val latestLikes: Long,
  val latestComments: Long,
  val accelerationPerHour: Double,
  val snapshots: List<ViewSnapshotEntity>
) {
  companion object {
    val EMPTY = VelocityState(
      label = "Abhi data kam hai",
      trend = VelocityTrend.SLOW,
      viewsGained24h = 0L,
      latestViews = 0L,
      latestLikes = 0L,
      latestComments = 0L,
      accelerationPerHour = 0.0,
      snapshots = emptyList()
    )
  }
}

object VelocityCalculator {
  const val HOUR_MS = 60L * 60L * 1000L
  const val DAY_MS = 24L * HOUR_MS

  fun compute(snapshots: List<ViewSnapshotEntity>, nowMs: Long = System.currentTimeMillis()): VelocityState {
    if (snapshots.isEmpty()) return VelocityState.EMPTY
    val sorted = snapshots.sortedBy { it.timestamp }
    val latest = sorted.last()
    val viewsGained24h = viewsGainedIn(sorted, nowMs, DAY_MS)

    val recentWindowMs = 6L * HOUR_MS
    val recentGain = viewsGainedIn(sorted, nowMs, recentWindowMs)
    val previousGain = viewsGainedBetween(sorted, nowMs - (2 * recentWindowMs), nowMs - recentWindowMs)

    val recentRatePerHour = recentGain.toDouble() / 6.0
    val previousRatePerHour = previousGain.toDouble() / 6.0
    val acceleration = recentRatePerHour - previousRatePerHour

    val trend = classify(viewsGained24h, recentGain, previousGain, acceleration)
    val label = when (trend) {
      VelocityTrend.RISING -> "🔥 Abhi viral ho raha hai"
      VelocityTrend.PEAKED -> "Peak ho chuka"
      VelocityTrend.SLOW -> "Slow growth"
    }

    return VelocityState(
      label = label,
      trend = trend,
      viewsGained24h = viewsGained24h.coerceAtLeast(0L),
      latestViews = latest.views,
      latestLikes = latest.likes,
      latestComments = latest.comments,
      accelerationPerHour = acceleration,
      snapshots = sorted
    )
  }

  internal fun classify(
    viewsGained24h: Long,
    recentGain: Long,
    previousGain: Long,
    acceleration: Double
  ): VelocityTrend {
    return when {
      // Strong recent growth and still speeding up (or holding strong)
      (viewsGained24h >= 10_000L && acceleration >= 0.0) ||
        (recentGain >= 2_000L && acceleration > 0.0) -> VelocityTrend.RISING

      // Had meaningful previous growth but recent growth slowed materially
      previousGain >= 2_000L && recentGain <= (previousGain * 0.45).toLong() -> VelocityTrend.PEAKED
      viewsGained24h < 1_000L && recentGain < 300L -> VelocityTrend.SLOW

      acceleration < -50.0 && previousGain > 0L -> VelocityTrend.PEAKED
      else -> VelocityTrend.SLOW
    }
  }

  fun viewsGainedIn(sorted: List<ViewSnapshotEntity>, nowMs: Long, windowMs: Long): Long {
    return viewsGainedBetween(sorted, nowMs - windowMs, nowMs)
  }

  fun viewsGainedBetween(
    sorted: List<ViewSnapshotEntity>,
    startMs: Long,
    endMs: Long
  ): Long {
    if (sorted.isEmpty()) return 0L
    val baseline = snapshotAtOrBefore(sorted, startMs) ?: sorted.first()
    val latest = snapshotAtOrBefore(sorted, endMs) ?: sorted.last()
    return (latest.views - baseline.views).coerceAtLeast(0L)
  }

  private fun snapshotAtOrBefore(
    sorted: List<ViewSnapshotEntity>,
    timeMs: Long
  ): ViewSnapshotEntity? {
    if (sorted.isEmpty() || timeMs < sorted.first().timestamp) return null
    var lo = 0
    var hi = sorted.lastIndex
    var ans = 0
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (sorted[mid].timestamp <= timeMs) {
        ans = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    return sorted[ans]
  }
}
