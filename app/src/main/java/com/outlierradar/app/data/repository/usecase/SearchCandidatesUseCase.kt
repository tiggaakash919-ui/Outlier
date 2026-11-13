package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.VideoStatsCacheEntity
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.data.repository.ActiveKey
import com.outlierradar.app.data.repository.QuotaBucket
import com.outlierradar.app.data.repository.SearchResult
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.DurationParser
import com.outlierradar.app.domain.FilterOptions
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.Response
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class SearchCandidatesUseCase(
  private val youTubeApi: YouTubeApi
) {
  private val stringListAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    .adapter<List<String>>(Types.newParameterizedType(List::class.java, String::class.java))

  suspend fun execute(
    filters: FilterOptions,
    pageToken: String?,
    key: ActiveKey,
    progressFlow: MutableStateFlow<String?>,
    executeWithRetry: suspend (suspend () -> Response<com.outlierradar.app.data.remote.SearchResponse>) -> Response<com.outlierradar.app.data.remote.SearchResponse>,
    handleResponse: suspend (Response<*>, ActiveKey, QuotaBucket) -> SearchResult.Error?,
    fetchVideoDetails: suspend (List<String>, ActiveKey, (Int) -> Unit, (Int) -> Unit) -> Map<String, VideoStatsCacheEntity>
  ): CandidatesOutcome {
    val collectedVideoIds = mutableListOf<String>()
    var curToken: String? = pageToken
    var finalNextPageToken: String? = null
    var currentSearchCalls = 0

    val publishedAfter = computePublishedAfterRfc(
      DiscoveryHelpers.effectiveRecencyDays(filters.recency, filters.maxViralAgeDays)
    )
    val region = if (filters.regionCode.equals("Any", ignoreCase = true)) null else filters.regionCode
    val relLang = when (filters.language.lowercase(Locale.ROOT)) {
      "hindi" -> "hi"
      "english" -> "en"
      else -> null
    }

    for (page in 1..filters.pagesPerSearch) {
      progressFlow.value = "Search kar rahe hain (Page $page/${filters.pagesPerSearch})..."
      val response = executeWithRetry {
        youTubeApi.searchVideos(
          query = filters.keyword,
          pageToken = curToken,
          publishedAfter = publishedAfter,
          regionCode = region,
          relevanceLanguage = relLang,
          apiKey = key.key
        )
      }

      val error = handleResponse(response, key, QuotaBucket.SEARCH)
      if (error != null) return CandidatesOutcome.Error(error)
      currentSearchCalls++

      val searchBody = response.body()
      val items = searchBody?.items ?: emptyList()
      items.forEach { item ->
        item.id?.videoId?.let { vid ->
          if (!collectedVideoIds.contains(vid)) {
            collectedVideoIds.add(vid)
          }
        }
      }

      finalNextPageToken = searchBody?.nextPageToken
      curToken = finalNextPageToken
      if (curToken.isNullOrBlank()) break
    }

    val searchReturnedCount = collectedVideoIds.size
    if (collectedVideoIds.isEmpty()) {
      return CandidatesOutcome.Empty(searchReturnedCount = 0, pages = currentSearchCalls)
    }

    progressFlow.value = "Videos ki details fetch ho rahi hain..."
    var videosListCost = 0
    var videoStatsCacheHits = 0
    val videoDetails = fetchVideoDetails(
      collectedVideoIds,
      key,
      { videosListCost += it },
      { videoStatsCacheHits += it }
    )

    val allFetchedVideos = videoDetails.values.map { v ->
      RawVideoCandidate(
        videoId = v.videoId,
        title = v.title,
        description = v.description,
        tags = parseTags(v.tagsJson),
        thumbnailUrl = v.thumbnailUrl,
        channelId = v.channelId,
        channelTitle = v.channelTitle,
        views = v.views,
        likes = v.likes,
        comments = v.comments,
        durationSeconds = v.durationSeconds,
        publishedAt = v.publishedAt
      )
    }

    val durationFilteredVideos =
      allFetchedVideos.filter { it.durationSeconds <= filters.maxDurationSeconds }
    val afterDurationCount = durationFilteredVideos.size
    val validCandidateVideos = durationFilteredVideos.filter { it.views >= filters.minViralViews }
    val afterMinViewsCount = validCandidateVideos.size
    val excludes = DiscoveryHelpers.parseExcludeKeywords(filters.excludeKeywords)
    val excludeFilteredVideos = validCandidateVideos.filter {
      !DiscoveryHelpers.matchesExcludeKeyword(it.title, it.tags, excludes)
    }
    val afterExcludeCount = excludeFilteredVideos.size
    val likesFilteredVideos = excludeFilteredVideos.filter {
      DiscoveryHelpers.passesLikesRatio(it.likes, it.views, filters.minLikesRatio)
    }
    val afterLikesCount = likesFilteredVideos.size
    val ageFilteredVideos = likesFilteredVideos.filter {
      DiscoveryHelpers.isWithinViralAge(it.publishedAt, filters.maxViralAgeDays)
    }
    val afterAgeCount = ageFilteredVideos.size
    val uniqueChannels = ageFilteredVideos.map { it.channelId }.distinct()

    return CandidatesOutcome.Success(
      CandidateSearchResult(
        collectedVideoIds = collectedVideoIds,
        videoDetails = videoDetails,
        allFetchedVideos = allFetchedVideos,
        ageFilteredVideos = ageFilteredVideos,
        uniqueChannels = uniqueChannels,
        nextPageToken = finalNextPageToken,
        searchReturnedCount = searchReturnedCount,
        afterDurationCount = afterDurationCount,
        afterMinViewsCount = afterMinViewsCount,
        afterExcludeCount = afterExcludeCount,
        afterLikesCount = afterLikesCount,
        afterAgeCount = afterAgeCount,
        currentSearchCalls = currentSearchCalls,
        videosListCost = videosListCost,
        videoStatsCacheHits = videoStatsCacheHits
      )
    )
  }

  private fun parseTags(tagsJson: String): List<String> {
    return runCatching { stringListAdapter.fromJson(tagsJson) }.getOrNull() ?: emptyList()
  }

  private fun computePublishedAfterRfc(days: Int?): String? {
    if (days == null || days <= 0) return null
    val targetInstant = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
    return DateTimeFormatter.ISO_INSTANT.format(targetInstant)
  }

  sealed interface CandidatesOutcome {
    data class Success(val result: CandidateSearchResult) : CandidatesOutcome
    data class Empty(val searchReturnedCount: Int, val pages: Int) : CandidatesOutcome
    data class Error(val error: SearchResult.Error) : CandidatesOutcome
  }
}
