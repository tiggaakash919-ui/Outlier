package com.example.domain

import androidx.compose.ui.graphics.Color
import com.example.ui.theme.BadgeGray
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

enum class BadgeTier {
  MEGA, // >= 500x (Green)
  HIGH, // 50–499x (Yellow)
  LOW   // < 50x (Gray)
}

object OutlierCalculator {
  fun trimKeyword(raw: String): String = raw.trim()

  fun calculateMultiplier(viralViews: Long, medianViews: Long): Int {
    if (medianViews <= 0) return 0
    return (viralViews.toDouble() / medianViews.toDouble()).roundToInt()
  }

  fun calculateViewsPerSub(viralViews: Long, subscriberCount: Long): Double {
    if (subscriberCount <= 0) return 0.0
    return viralViews.toDouble() / subscriberCount.toDouble()
  }

  fun calculateEngagementRate(views: Long, likes: Long): String? {
    if (views <= 0 || likes <= 0) return null
    val pct = (likes.toDouble() / views.toDouble()) * 100.0
    if (pct < 0.1 || pct > 100.0) return null
    return String.format(Locale.US, "%.1f%% likes", pct)
  }

  fun calculateViewsPerDay(views: Long, publishedAt: String): String? {
    if (views <= 0 || publishedAt.isBlank()) return null
    return try {
      val publishedInstant = Instant.parse(publishedAt)
      val days = max(1L, ChronoUnit.DAYS.between(publishedInstant, Instant.now()))
      val perDay = views / days
      if (perDay <= 0L) null else "${formatCompactNumber(perDay)}/day"
    } catch (_: Exception) {
      null
    }
  }

  fun getMicroNichesFor(keyword: String): List<String> {
    val norm = keyword.trim().lowercase(Locale.ROOT)
    return when {
      norm.contains("minecraft") -> listOf(
        "minecraft but challenge",
        "minecraft hindi shorts",
        "minecraft survival tips",
        "minecraft build hacks",
        "minecraft redstone facts",
        "minecraft speedrun moment"
      )
      norm.contains("gaming") || norm.contains("game") -> listOf(
        "minecraft hindi shorts",
        "bgmi clutch shorts",
        "gta 5 funny hindi",
        "free fire tips",
        "roblox story shorts",
        "indie game devlog"
      )
      norm.contains("tech") -> listOf(
        "android secret tricks",
        "useful websites shorts",
        "ai tools hindi",
        "pc tips and tricks",
        "budget gadgets india"
      )
      norm.contains("finance") || norm.contains("crypto") -> listOf(
        "stock market mistake",
        "passive income hindi",
        "credit card hacks",
        "business case study",
        "money psychology"
      )
      norm.contains("fact") -> listOf(
        "amazing facts hindi",
        "space mystery shorts",
        "psychology facts hindi",
        "history untaught facts",
        "science experiment short"
      )
      norm.contains("motiv") -> listOf(
        "study motivation hindi",
        "success mindset short",
        "discipline habits",
        "life lessons hindi",
        "gym motivation short"
      )
      norm.contains("cook") || norm.contains("food") -> listOf(
        "street food recipe",
        "5 minute hostel recipe",
        "healthy protein snack",
        "desi tiffin ideas",
        "instant breakfast hindi"
      )
      else -> listOf(
        "minecraft hindi shorts",
        "psychology facts hindi",
        "android secret tricks",
        "5 minute recipe",
        "business case study",
        "space mystery shorts"
      )
    }
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

  /**
   * Finds the first pipeline stage where the count dropped to 0.
   * - after duration/minViews = 0 -> DURATION_OR_MIN_VIEWS ("Itne views wale Shorts nahi mile")
   * - after subs filter = 0 -> SUBS_FILTER ("Sab channels bade hain (subs zyada)")
   * - median stage = 0 -> MEDIAN_STAGE ("Channels ke baaki Shorts bhi zyada views le rahe hain")
   * - multiplier stage = 0 -> MULTIPLIER_STAGE ("Multiplier kam hai")
   */
  fun detectEmptyStage(stats: DebugStats): EmptyStageReason {
    return when {
      stats.afterDurationFilter == 0 || stats.afterMinViewsFilter == 0 -> EmptyStageReason.DURATION_OR_MIN_VIEWS
      stats.afterSubsFilter == 0 -> EmptyStageReason.SUBS_FILTER
      stats.channelsPassingMedian == 0 -> EmptyStageReason.MEDIAN_STAGE
      else -> EmptyStageReason.MULTIPLIER_STAGE
    }
  }

  /**
   * Applies the targeted one-tap filter adjustment for the detected empty stage.
   * If the user already applied the standard fix and still got 0 results, progressively relaxes further
   * so the button never gets stuck in a no-op loop.
   */
  fun applyEmptyStageFix(filters: FilterOptions, stage: EmptyStageReason): FilterOptions {
    val base = filters.withTrimmedKeyword()
    return when (stage) {
      EmptyStageReason.DURATION_OR_MIN_VIEWS -> {
        if (base.minViralViews > 100_000L) {
          base.copy(minViralViews = 100_000L)
        } else {
          base.copy(
            minViralViews = 25_000L,
            maxDurationSeconds = 180L,
            regionCode = "Any",
            recency = "30d"
          )
        }
      }
      EmptyStageReason.SUBS_FILTER -> {
        if (base.maxSubs < 100_000L || base.recency != "7d") {
          base.copy(minSubs = 0L, maxSubs = 100_000L, recency = "7d")
        } else {
          base.copy(
            minSubs = 0L,
            maxSubs = 500_000L,
            recency = "30d",
            regionCode = "Any",
            maxMedianViews = maxOf(base.maxMedianViews, 20_000L),
            minMultiplier = minOf(base.minMultiplier, 20)
          )
        }
      }
      EmptyStageReason.MEDIAN_STAGE -> {
        if (base.maxMedianViews < 20_000L) {
          base.copy(maxMedianViews = 20_000L)
        } else {
          base.copy(
            maxMedianViews = 100_000L,
            maxSubs = maxOf(base.maxSubs, 250_000L),
            minViralViews = minOf(base.minViralViews, 100_000L),
            minMultiplier = minOf(base.minMultiplier, 15),
            recency = "30d",
            regionCode = "Any"
          )
        }
      }
      EmptyStageReason.MULTIPLIER_STAGE -> {
        if (base.minMultiplier > 20) {
          base.copy(minMultiplier = 20)
        } else {
          base.copy(
            minMultiplier = 5,
            maxMedianViews = maxOf(base.maxMedianViews, 50_000L),
            maxSubs = maxOf(base.maxSubs, 250_000L),
            regionCode = "Any"
          )
        }
      }
    }
  }

  /**
   * Generates the exact near-miss reason chip text for a rejected candidate:
   * - "Subs 250K > max 10K"
   * - "<5 Shorts on channel"
   * - "Median 12K > max 5K"
   * - "Multiplier 8x < 50x"
   * Returns null if the candidate passes all filters.
   */
  fun buildNearMissReason(
    subscriberCount: Long,
    hiddenSubscribers: Boolean = false,
    shortCount: Int?,
    medianViews: Long?,
    multiplier: Int?,
    filters: FilterOptions
  ): String? {
    val isFullRangeSubs = filters.minSubs <= 0L && filters.maxSubs >= 100_000L
    if (hiddenSubscribers && !isFullRangeSubs) {
      return "Hidden subs"
    }
    if (subscriberCount > filters.maxSubs) {
      return "Subs ${formatCompactNumber(subscriberCount)} > max ${formatCompactNumber(filters.maxSubs)}"
    }
    if (subscriberCount < filters.minSubs) {
      return "Subs ${formatCompactNumber(subscriberCount)} < min ${formatCompactNumber(filters.minSubs)}"
    }
    if (shortCount == null || shortCount < 5 || medianViews == null) {
      return "<5 Shorts on channel"
    }
    if (medianViews > filters.maxMedianViews) {
      return "Median ${formatCompactNumber(medianViews)} > max ${formatCompactNumber(filters.maxMedianViews)}"
    }
    val actualMultiplier = multiplier ?: calculateMultiplier(0L, medianViews)
    if (actualMultiplier < filters.minMultiplier) {
      return "Multiplier ${actualMultiplier}x < ${filters.minMultiplier}x"
    }
    return null
  }
}
