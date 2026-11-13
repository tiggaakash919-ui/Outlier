package com.outlierradar.app.data.repository.usecase

import com.outlierradar.app.data.local.ShortVerificationCacheEntity
import com.outlierradar.app.data.local.ShortVerificationDao
import com.outlierradar.app.data.remote.ShortsVerifier
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShortStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

class VerifyShortsUseCase(
  private val shortsVerifier: ShortsVerifier,
  private val verificationCacheDao: ShortVerificationDao
) {
  suspend fun execute(
    finalCandidates: List<OutlierVideo>,
    progressFlow: MutableStateFlow<String?>,
    maxVerify: Int = 30,
    concurrency: Int = 5
  ): VerificationResult {
    progressFlow.value = "Shorts verify ho rahe hain..."
    val verifyCandidates = finalCandidates.take(maxVerify)
    val verifiedOutliers = mutableListOf<OutlierVideo>()
    val verifySemaphore = Semaphore(concurrency)
    val verifyMutex = Mutex()
    val verifiedShortsCount = AtomicInteger(0)
    val verifiedIndex = AtomicInteger(0)

    coroutineScope {
      val verifyJobs = verifyCandidates.map { cand ->
        async {
          verifySemaphore.withPermit {
            val cachedVerification = verificationCacheDao.getVerification(cand.videoId)
            val status = when {
              cachedVerification != null -> {
                if (cachedVerification.isUnverified) ShortStatus.UNVERIFIED
                else if (cachedVerification.isShort) ShortStatus.CONFIRMED_SHORT
                else ShortStatus.NOT_A_SHORT
              }
              else -> {
                val verified = shortsVerifier.verifyShort(cand.videoId)
                verificationCacheDao.insertVerification(
                  ShortVerificationCacheEntity(
                    videoId = cand.videoId,
                    isShort = verified == ShortStatus.CONFIRMED_SHORT,
                    isUnverified = verified == ShortStatus.UNVERIFIED,
                    timestamp = System.currentTimeMillis()
                  )
                )
                verified
              }
            }

            verifyMutex.withLock {
              if (status == ShortStatus.CONFIRMED_SHORT) verifiedShortsCount.incrementAndGet()
              val idx = verifiedIndex.incrementAndGet()
              progressFlow.value = "Shorts verify ho rahe hain $idx/${verifyCandidates.size}..."
              verifiedOutliers.add(cand.copy(shortStatus = status))
            }
          }
        }
      }
      verifyJobs.awaitAll()
    }

    if (finalCandidates.size > maxVerify) {
      verifiedOutliers.addAll(finalCandidates.drop(maxVerify))
    }

    val finalizedOutliers = DiscoveryHelpers.flagReuploads(verifiedOutliers)
    val reuploadCount = finalizedOutliers.count { it.isPossibleReupload }
    return VerificationResult(
      finalizedOutliers = finalizedOutliers,
      verifiedShortsCount = verifiedShortsCount.get(),
      reuploadCount = reuploadCount
    )
  }
}
