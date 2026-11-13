package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.ChannelCacheDao
import com.outlierradar.app.data.local.ChannelCacheStatus
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.data.repository.ActiveKey
import com.outlierradar.app.data.repository.QuotaBucket
import com.outlierradar.app.data.repository.SearchResult
import com.outlierradar.app.domain.CacheTtl
import com.outlierradar.app.domain.FilterOptions
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.Response

class FetchChannelStatsUseCase(
  private val youTubeApi: YouTubeApi,
  private val channelCacheDao: ChannelCacheDao
) {
  suspend fun execute(
    filters: FilterOptions,
    uniqueChannels: List<String>,
    key: ActiveKey,
    progressFlow: MutableStateFlow<String?>,
    executeWithRetry: suspend (suspend () -> Response<com.outlierradar.app.data.remote.ChannelListResponse>) -> Response<com.outlierradar.app.data.remote.ChannelListResponse>,
    handleResponse: suspend (Response<*>, ActiveKey, QuotaBucket) -> SearchResult.Error?
  ): Outcome {
    progressFlow.value = "Channels ka subscriber check ho raha hai (${uniqueChannels.size} channels)..."
    val now = System.currentTimeMillis()
    val cachedChannels = if (uniqueChannels.isEmpty()) {
      emptyMap()
    } else {
      channelCacheDao.getChannels(uniqueChannels).associateBy { it.channelId }
    }
    val channelDataMap = mutableMapOf<String, ChannelStatsHolder>()
    val channelsToFetch = mutableListOf<String>()
    var channelsListCost = 0

    for (chId in uniqueChannels) {
      val cached = cachedChannels[chId]
      if (cached != null && CacheTtl.isFresh(now, cached.subsCachedAt, CacheTtl.SUBS_MS)) {
        channelDataMap[chId] = cached.toHolder()
      } else {
        channelsToFetch.add(chId)
      }
    }

    if (channelsToFetch.isNotEmpty()) {
      for (chunk in channelsToFetch.chunked(50)) {
        val response = executeWithRetry {
          youTubeApi.getChannels(
            ids = chunk.joinToString(","),
            apiKey = key.key
          )
        }
        val error = handleResponse(response, key, QuotaBucket.MAIN)
        if (error != null) return Outcome.Error(error)
        channelsListCost++

        val returnedIds = mutableSetOf<String>()
        val chItems = response.body()?.items ?: emptyList()
        for (cItem in chItems) {
          val subs = cItem.statistics?.subscriberCount?.toLongOrNull() ?: 0L
          val hiddenSubs = cItem.statistics?.hiddenSubscriberCount ?: false
          val uploads = cItem.contentDetails?.relatedPlaylists?.uploads
          returnedIds.add(cItem.id)

          val previous = cachedChannels[cItem.id]
          channelDataMap[cItem.id] = ChannelStatsHolder(
            subscriberCount = subs,
            hasHiddenSubscribers = hiddenSubs,
            uploadsPlaylistId = uploads,
            cachedStatus = previous?.status ?: ChannelCacheStatus.PENDING,
            cachedMedianAt = previous?.medianCachedAt ?: 0L,
            cachedMedian = previous?.medianViews ?: 0L,
            cachedShortCount = previous?.shortCount ?: 0,
            cachedSource = previous?.source ?: "",
            cachedTrimmedMean = previous?.trimmedMeanViews ?: 0L,
            cachedP90 = previous?.p90Views ?: 0L,
            cachedAbove5xCount = previous?.othersAbove5xCount ?: 0,
            cachedAbove10xCount = previous?.othersAbove10xCount ?: 0
          )

          channelCacheDao.insertIfAbsent(cItem.id, subs, hiddenSubs, uploads, now)
          channelCacheDao.updateSubscriberInfo(cItem.id, subs, hiddenSubs, uploads, now)
          if (hiddenSubs) {
            channelCacheDao.updateNegativeStatus(cItem.id, ChannelCacheStatus.HIDDEN_SUBS, now)
          }
        }

        for (chId in chunk) {
          if (chId !in returnedIds) {
            channelCacheDao.insertIfAbsent(chId, 0L, false, null, now)
            channelCacheDao.updateSubscriberInfo(chId, 0L, false, null, now)
            channelCacheDao.updateNegativeStatus(chId, ChannelCacheStatus.NO_CHANNEL, now)
          }
        }
      }
    }

    val passingChannelIds = mutableSetOf<String>()
    val isFullRangeSubs = filters.minSubs <= 0L && filters.maxSubs >= 100_000L
    for (chId in uniqueChannels) {
      val chInfo = channelDataMap[chId] ?: continue
      if (chInfo.hasHiddenSubscribers && !isFullRangeSubs) continue
      if (chInfo.subscriberCount in filters.minSubs..filters.maxSubs) {
        passingChannelIds.add(chId)
      }
    }

    return Outcome.Success(
      ChannelStatsResult(
        channelDataMap = channelDataMap,
        passingChannelIds = passingChannelIds,
        afterSubsFilterCount = passingChannelIds.size,
        channelsListCost = channelsListCost
      )
    )
  }

  sealed interface Outcome {
    data class Success(val result: ChannelStatsResult) : Outcome
    data class Error(val error: SearchResult.Error) : Outcome
  }
}
