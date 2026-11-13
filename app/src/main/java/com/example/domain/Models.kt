package com.example.domain

enum class ShortStatus {
  CONFIRMED_SHORT,
  NOT_A_SHORT,
  UNVERIFIED
}

enum class SortOption(val label: String) {
  SCORE("Score"),
  MULTIPLIER("Multiplier"),
  VIEWS("Views"),
  NEWEST("Newest"),
  LOWEST_SUBS("Lowest subs"),
  VIEWS_PER_SUB("Views per sub")
}

data class OutlierVideo(
  val videoId: String,
  val title: String,
  val description: String,
  val tags: List<String> = emptyList(),
  val thumbnailUrl: String,
  val channelId: String,
  val channelTitle: String,
  val subscriberCount: Long,
  val hiddenSubscriberCount: Boolean,
  val views: Long,
  val channelMedianViews: Long,
  val channelTrimmedMeanViews: Long = 0L,
  val channelP90Views: Long = 0L,
  val outlierMultiplier: Double,
  val viewsPerSub: Double,
  val likes: Long,
  val comments: Long,
  val durationSeconds: Long,
  val publishedAt: String,
  val othersAbove5xCount: Int = 0,
  val othersAbove10xCount: Int = 0,
  val shortStatus: ShortStatus = ShortStatus.UNVERIFIED,
  val isSaved: Boolean = false,
  val savedAt: Long = 0L,
  val isOneHitWonder: Boolean = false
)

data class NearMissVideo(
  val videoId: String,
  val title: String,
  val thumbnailUrl: String,
  val channelId: String,
  val channelTitle: String,
  val views: Long,
  val subscriberCount: Long,
  val hiddenSubscriberCount: Boolean = false,
  val medianViews: Long? = null,
  val multiplier: Double? = null,
  val reasonChip: String,
  val stagePriority: Int = 0
)

enum class EmptyStageReason(
  val message: String,
  val fixButtonLabel: String
) {
  DURATION_OR_MIN_VIEWS(
    message = "Itne views wale Shorts nahi mile",
    fixButtonLabel = "Min views 100K"
  ),
  SUBS_FILTER(
    message = "Sab channels bade hain (subs zyada)",
    fixButtonLabel = "Subs 0–100K + recency 7d"
  ),
  MEDIAN_STAGE(
    message = "Channels ke baaki Shorts bhi zyada views le rahe hain",
    fixButtonLabel = "Max median 20K"
  ),
  MULTIPLIER_STAGE(
    message = "Multiplier kam hai",
    fixButtonLabel = "Min multiplier 20x"
  )
}

data class AiAnalysis(
  val whyViral: List<String> = emptyList(),
  val hookIdeas: List<String> = emptyList(),
  val titleIdeas: List<String> = emptyList(),
  val contentIdea: String = ""
)

sealed interface AiUiState {
  data object Idle : AiUiState
  data object Loading : AiUiState
  data class Success(val analysis: AiAnalysis, val isCached: Boolean) : AiUiState
  data class Error(val message: String) : AiUiState
}

data class FilterOptions(
  val keyword: String = "",
  val minSubs: Long = 0L,
  val maxSubs: Long = 10_000L,
  val minViralViews: Long = 500_000L,
  val maxMedianViews: Long = 5_000L,
  val minMultiplier: Int = 50,
  val samplePerChannel: Int = 50,
  val minBaselineAgeHours: Int = 72, // fresh Shorts are excluded from the baseline until views mature
  val recency: String = "90d", // 7d, 30d, 90d, 6mo, 1y, any
  val regionCode: String = "IN", // IN, US, Any
  val language: String = "Any", // Hindi, English, Any
  val maxDurationSeconds: Long = 180L, // 60s, 180s
  val pagesPerSearch: Int = 2 // 1, 2, 3
) {
  fun withTrimmedKeyword(): FilterOptions = copy(keyword = keyword.trim())
}

enum class PresetType {
  TINY_GEMS, // subs 0–1K, median <=2K, min views 500K, 100x
  BALANCED,  // subs 0-10K, median <=5K, min views 500K, 50x
  LOOSE      // subs 0–100K, median <=20K, min views 100K, 20x, recency 30d, region Any, language Any
}

object Presets {
  fun applyPreset(type: PresetType, currentKw: String): FilterOptions {
    val trimmedKw = currentKw.trim()
    return when (type) {
      PresetType.TINY_GEMS -> FilterOptions(
        keyword = trimmedKw,
        minSubs = 0L,
        maxSubs = 1_000L,
        minViralViews = 500_000L,
        maxMedianViews = 2_000L,
        minMultiplier = 100,
        samplePerChannel = 50,
        recency = "90d",
        regionCode = "IN",
        language = "Any",
        maxDurationSeconds = 180L,
        pagesPerSearch = 2
      )
      PresetType.BALANCED -> FilterOptions(
        keyword = trimmedKw,
        minSubs = 0L,
        maxSubs = 10_000L,
        minViralViews = 500_000L,
        maxMedianViews = 5_000L,
        minMultiplier = 50,
        samplePerChannel = 50,
        recency = "90d",
        regionCode = "IN",
        language = "Any",
        maxDurationSeconds = 180L,
        pagesPerSearch = 2
      )
      PresetType.LOOSE -> FilterOptions(
        keyword = trimmedKw,
        minSubs = 0L,
        maxSubs = 100_000L,
        minViralViews = 100_000L,
        maxMedianViews = 20_000L,
        minMultiplier = 20,
        samplePerChannel = 50,
        recency = "30d",
        regionCode = "Any",
        language = "Any",
        maxDurationSeconds = 180L,
        pagesPerSearch = 2
      )
    }
  }
}

data class DebugStats(
  val searchReturned: Int = 0,
  val pages: Int = 0,
  val afterDurationFilter: Int = 0,
  val afterMinViewsFilter: Int = 0,
  val uniqueChannels: Int = 0,
  val afterSubsFilter: Int = 0,
  val channelsWithTooFewShorts: Int = 0,
  val channelsMedianTooHigh: Int = 0,
  val channelsPassingMedian: Int = 0,
  val uushCount: Int = 0,
  val uploadsFallbackCount: Int = 0,
  val finalOutliers: Int = 0,
  val verifiedShorts: Int = 0,
  val searchCallsCost: Int = 0,
  val videosListCost: Int = 0,
  val channelsListCost: Int = 0,
  val perChannelCost: Int = 0,
  val mainTotalCost: Int = 0,
  val cacheHits: Int = 0,
  val negativeCacheHits: Int = 0,
  val videoStatsCacheHits: Int = 0,
  val medianSkippedByCost: Int = 0
)

/** Per-key quota usage, used by the key list in Settings. */
data class KeyQuotaUsage(
  val index: Int = 0,
  val searchCalls: Int = 0,
  val mainUnits: Int = 0,
  val isSearchExceeded: Boolean = false,
  val isMainExceeded: Boolean = false
) {
  val isExhausted: Boolean
    get() = isSearchExceeded || isMainExceeded
}

data class QuotaStatus(
  val searchCalls: Int = 0,
  val searchLimit: Int = 100,
  val mainUnits: Int = 0,
  val mainLimit: Int = 10_000,
  val isSearchExceeded: Boolean = false,
  val isMainExceeded: Boolean = false,
  val resetTimeLocal: String = "",
  val activeKeyIndex: Int = 0,
  val keyCount: Int = 0,
  val keyUsages: List<KeyQuotaUsage> = emptyList(),
  val hasUsableKey: Boolean = true
) {
  val badgeText: String
    get() {
      val keyPart = if (keyCount > 0) " · Key ${activeKeyIndex + 1}/$keyCount" else ""
      return "$mainUnits / $mainLimit units · Search $searchCalls/$searchLimit$keyPart"
    }

  val isActiveKeyExhausted: Boolean
    get() = isSearchExceeded || isMainExceeded
}

/** A masked API key row for the Settings screen. */
data class ApiKeyUi(
  val index: Int = 0,
  val masked: String = "",
  val isActive: Boolean = false
)
