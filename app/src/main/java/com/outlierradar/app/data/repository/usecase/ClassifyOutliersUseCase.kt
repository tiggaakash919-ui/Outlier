package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.SavedOutlierDao
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.NearMissVideo
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShortStatus

class ClassifyOutliersUseCase(
  private val savedDao: SavedOutlierDao
) {
  suspend fun execute(
    ageFilteredVideos: List<RawVideoCandidate>,
    channelDataMap: Map<String, ChannelStatsHolder>,
    channelMedianResults: Map<String, ChannelMedianResult>,
    skippedByCostIds: Set<String>,
    filters: FilterOptions
  ): ClassificationResult {
    val finalCandidates = mutableListOf<OutlierVideo>()
    val rejectedCandidates = mutableListOf<NearMissVideo>()

    for (v in ageFilteredVideos) {
      val chInfo = channelDataMap[v.channelId]
      val subs = chInfo?.subscriberCount ?: 0L
      val hiddenSubs = chInfo?.hasHiddenSubscribers ?: false
      val medianInfo = channelMedianResults[v.channelId]
      val multiplier = if (medianInfo != null) {
        OutlierCalculator.calculateMultiplier(v.views, medianInfo.median)
      } else null

      // Small-channel replicable filter (1of10/NexLev): only channels with subs <= threshold.
      val smallChannelReject = if (filters.smallChannelMaxSubs > 0L && subs > filters.smallChannelMaxSubs) {
        "Channel ${OutlierCalculator.formatCompactNumber(subs)} subs > ${OutlierCalculator.formatCompactNumber(filters.smallChannelMaxSubs)} (not replicable)"
      } else null

      // Published-within-N-days filter for the viral Short itself.
      val publishedWithinReject = if (filters.publishedWithinDays > 0) {
        val ageDays = try {
          java.time.Duration.between(java.time.Instant.parse(v.publishedAt), java.time.Instant.now()).toDays()
        } catch (_: Exception) { 0L }
        if (ageDays > filters.publishedWithinDays) "Viral Short older than ${filters.publishedWithinDays}d" else null
      } else null

      val rejectionReason = smallChannelReject ?: publishedWithinReject ?: if (medianInfo == null && v.channelId in skippedByCostIds) {
        "Median skipped (cost)"
      } else {
        OutlierCalculator.buildNearMissReason(
          subscriberCount = subs,
          hiddenSubscribers = hiddenSubs,
          shortCount = medianInfo?.shortCount,
          medianViews = medianInfo?.median,
          multiplier = multiplier,
          filters = filters
        )
      }

      if (rejectionReason != null) {
        val stagePriority = when {
          rejectionReason.startsWith("Multiplier") -> 4
          rejectionReason.startsWith("Median") -> 3
          rejectionReason == "<5 Shorts on channel" -> 2
          else -> 1
        }
        rejectedCandidates.add(
          NearMissVideo(
            videoId = v.videoId,
            title = v.title,
            thumbnailUrl = v.thumbnailUrl,
            channelId = v.channelId,
            channelTitle = v.channelTitle,
            views = v.views,
            subscriberCount = subs,
            hiddenSubscriberCount = hiddenSubs,
            medianViews = medianInfo?.median,
            multiplier = multiplier,
            reasonChip = rejectionReason,
            stagePriority = stagePriority
          )
        )
        continue
      }

      val validBaseline = medianInfo!!
      val validMedian = validBaseline.median
      val validMultiplier = multiplier!!
      val viewsPerSub = OutlierCalculator.calculateViewsPerSub(v.views, subs)
      val isSaved = savedDao.isSaved(v.videoId)
      val oneHitWonder = OutlierCalculator.isOneHitWonder(
        multiplier = validMultiplier,
        othersAbove5xCount = validBaseline.othersAbove5xCount
      )

      val outlier = OutlierVideo(
        videoId = v.videoId,
        title = v.title,
        description = v.description,
        tags = v.tags,
        thumbnailUrl = v.thumbnailUrl,
        channelId = v.channelId,
        channelTitle = v.channelTitle,
        subscriberCount = subs,
        hiddenSubscriberCount = hiddenSubs,
        views = v.views,
        channelMedianViews = validMedian,
        channelTrimmedMeanViews = validBaseline.trimmedMean,
        channelP90Views = validBaseline.p90,
        outlierMultiplier = validMultiplier,
        viewsPerSub = viewsPerSub,
        viewsPerHour = OutlierCalculator.calculateViewsPerHour(v.views, v.publishedAt),
        likes = v.likes,
        comments = v.comments,
        durationSeconds = v.durationSeconds,
        publishedAt = v.publishedAt,
        othersAbove5xCount = validBaseline.othersAbove5xCount,
        othersAbove10xCount = validBaseline.othersAbove10xCount,
        shortStatus = ShortStatus.UNVERIFIED,
        isSaved = isSaved,
        isOneHitWonder = oneHitWonder
      )
      finalCandidates.add(
        outlier.copy(
          rpmEstimate = OutlierCalculator.estimateRpm(outlier),
          facelessPotential = OutlierCalculator.facelessPotential(outlier)
        )
      )
    }

    val topNearMisses = rejectedCandidates
      .sortedWith(
        compareByDescending<NearMissVideo> { it.stagePriority }
          .thenByDescending { it.multiplier ?: 0.0 }
          .thenByDescending { it.views }
      )
      .take(10)

    return ClassificationResult(finalCandidates = finalCandidates, topNearMisses = topNearMisses)
  }
}
