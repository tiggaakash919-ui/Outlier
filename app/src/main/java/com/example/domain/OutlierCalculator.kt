package com.example.domain

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.BadgeGray
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import java.util.Locale
import kotlin.math.roundToInt

enum class BadgeTier {
  MEGA, // >= 500x (Green)
  HIGH, // 50–499x (Yellow)
  LOW   // < 50x (Gray)
}

object OutlierCalculator {
  fun calculateMultiplier(viralViews: Long, medianViews: Long): Int {
    if (medianViews <= 0) return 0
    return (viralViews.toDouble() / medianViews.toDouble()).roundToInt()
  }

  fun calculateViewsPerSub(viralViews: Long, subscriberCount: Long): Double {
    if (subscriberCount <= 0) return 0.0
    return viralViews.toDouble() / subscriberCount.toDouble()
  }

  fun getBadgeTier(multiplier: Int): BadgeTier {
    return when {
      multiplier >= 500 -> BadgeTier.MEGA
      multiplier >= 50 -> BadgeTier.HIGH
      else -> BadgeTier.LOW
    }
  }

  fun getBadgeColor(multiplier: Int): Color {
    return when (getBadgeTier(multiplier)) {
      BadgeTier.MEGA -> BadgeGreen
      BadgeTier.HIGH -> BadgeYellow
      BadgeTier.LOW -> BadgeGray
    }
  }

  fun formatCompactNumber(number: Long): String {
    if (number < 1_000) return number.toString()
    if (number < 1_000_000) {
      val value = number / 1000.0
      return if (value >= 10 || (number % 1000 < 100)) {
        "${(number / 1000)}K"
      } else {
        String.format(Locale.US, "%.1fK", value)
      }
    }
    val value = number / 1_000_000.0
    return if (value >= 10 || (number % 1_000_000 < 100_000)) {
      "${(number / 1_000_000)}M"
    } else {
      String.format(Locale.US, "%.1fM", value)
    }
  }

  fun formatSubscribers(subs: Long, hidden: Boolean): String {
    if (hidden) return "Hidden subs"
    return "~${formatCompactNumber(subs)}"
  }
}
