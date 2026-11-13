package com.outlierradar.app.domain

import java.util.regex.Pattern

object DurationParser {
  private val DURATION_REGEX = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")

  /**
   * Parses an ISO 8601 duration string (e.g. PT45S, PT3M, PT1H2M3S) into total seconds.
   * Returns 0 for invalid, empty, or zero durations (e.g. P0D).
   */
  fun parseToSeconds(isoDuration: String?): Long {
    if (isoDuration.isNullOrBlank()) return 0L
    val matcher = DURATION_REGEX.matcher(isoDuration)
    if (!matcher.matches()) return 0L

    val hours = matcher.group(1)?.toLongOrNull() ?: 0L
    val minutes = matcher.group(2)?.toLongOrNull() ?: 0L
    val seconds = matcher.group(3)?.toLongOrNull() ?: 0L

    return (hours * 3600L) + (minutes * 60L) + seconds
  }

  fun formatSeconds(totalSeconds: Long): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return if (mins > 0) {
      "${mins}m ${secs}s"
    } else {
      "${secs}s"
    }
  }
}
