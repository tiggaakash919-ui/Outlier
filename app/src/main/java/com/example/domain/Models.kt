package com.example.domain

enum class ShortStatus {
  CONFIRMED_SHORT,
  NOT_A_SHORT,
  UNVERIFIED
}

enum class SortOption(val label: String) {
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
  val outlierMultiplier: Int,
  val viewsPerSub: Double,
  val likes: Long,
  val comments: Long,
  val durationSeconds: Long,
  val publishedAt: String,
  val shortStatus: ShortStatus = ShortStatus.UNVERIFIED,
  val isSaved: Boolean = false
)

data class FilterOptions(
  val keyword: String = "",
  val minSubs: Long = 0L,
  val maxSubs: Long = 10_000L,
  val minViralViews: Long = 500_000L,
  val maxMedianViews: Long = 5_000L,
  val minMultiplier: Int = 50,
  val samplePerChannel: Int = 50,
  val recency: String = "90d", // 7d, 30d, 90d, 6mo, 1y, any
  val regionCode: String = "IN", // IN, US, Any
  val language: String = "Any", // Hindi, English, Any
  val maxDurationSeconds: Long = 180L, // 60s, 180s
  val pagesPerSearch: Int = 2 // 1, 2, 3
)

enum class PresetType {
  TINY_GEMS, // subs 0–1K, median <=2K, min views 500K, 100x
  BALANCED,  // subs 0-10K, median <=5K, min views 500K, 50x
  LOOSE      // subs 0–100K, median <=10K, min views 100K, 20x
}

object Presets {
  fun applyPreset(type: PresetType, currentKw: String): FilterOptions {
    return when (type) {
      PresetType.TINY_GEMS -> FilterOptions(
        keyword = currentKw,
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
        keyword = currentKw,
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
        keyword = currentKw,
        minSubs = 0L,
        maxSubs = 100_000L,
        minViralViews = 100_000L,
        maxMedianViews = 10_000L,
        minMultiplier = 20,
        samplePerChannel = 50,
        recency = "90d",
        regionCode = "IN",
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
  val cacheHits: Int = 0
)

data class QuotaStatus(
  val searchCalls: Int = 0,
  val searchLimit: Int = 100,
  val mainUnits: Int = 0,
  val mainLimit: Int = 10_000,
  val isSearchExceeded: Boolean = false,
  val isMainExceeded: Boolean = false,
  val resetTimeLocal: String = ""
) {
  val badgeText: String
    get() = "Search $searchCalls/$searchLimit · Units $mainUnits/$mainLimit"
}
