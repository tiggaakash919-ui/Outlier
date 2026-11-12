package com.example.domain

object MedianCalculator {
  /**
   * Calculates median views of the channel's other recent Shorts.
   * Requires at least 5 other Shorts; returns null otherwise.
   */
  fun calculateMedian(viewsList: List<Long>): Long? {
    if (viewsList.size < 5) return null
    val sorted = viewsList.sorted()
    val size = sorted.size
    return if (size % 2 == 1) {
      sorted[size / 2]
    } else {
      (sorted[size / 2 - 1] + sorted[size / 2]) / 2
    }
  }
}
