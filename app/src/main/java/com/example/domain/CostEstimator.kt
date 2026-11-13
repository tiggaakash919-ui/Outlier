package com.example.domain

/**
 * Estimated cost of the per-channel median stage, shown to the user before we
 * spend units on it.
 *
 * Worst case per uncached channel is 3 units: one `playlistItems.list` for the
 * UUSH playlist, one `playlistItems.list` for the uploads fallback, and one
 * `videos.list` for the statistics.
 */
data class CostEstimate(
  val uncachedChannels: Int = 0,
  val cachedChannels: Int = 0,
  val estimatedUnits: Int = 0,
  val remainingUnits: Int = 0
) {
  val needsConfirmation: Boolean
    get() = estimatedUnits > CostEstimator.CONFIRM_THRESHOLD_UNITS

  val worstCaseExceedsRemaining: Boolean
    get() = remainingUnits in 0 until estimatedUnits
}

object CostEstimator {
  /** Ask the user before spending more than this many units. */
  const val CONFIRM_THRESHOLD_UNITS = 500

  /** UUSH playlist + uploads fallback + videos.list. */
  const val UNITS_PER_UNCACHED_CHANNEL = 3

  fun estimate(
    uncachedChannels: Int,
    cachedChannels: Int,
    remainingUnits: Int
  ): CostEstimate {
    val uncached = uncachedChannels.coerceAtLeast(0)
    return CostEstimate(
      uncachedChannels = uncached,
      cachedChannels = cachedChannels.coerceAtLeast(0),
      estimatedUnits = uncached * UNITS_PER_UNCACHED_CHANNEL,
      remainingUnits = remainingUnits.coerceAtLeast(0)
    )
  }
}
