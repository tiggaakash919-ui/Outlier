package com.outlierradar.app

import com.outlierradar.app.data.repository.SavedRepository
import com.outlierradar.app.domain.BadgeTier
import com.outlierradar.app.domain.DebugStats
import com.outlierradar.app.domain.DurationParser
import com.outlierradar.app.domain.EmptyStageReason
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.MedianCalculator
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.PresetType
import com.outlierradar.app.domain.Presets
import com.outlierradar.app.domain.ShortStatus
import com.outlierradar.app.domain.UushHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OutlierHunterUnitTests {

  @Test
  fun testDurationParserIso8601() {
    assertEquals(45L, DurationParser.parseToSeconds("PT45S"))
    assertEquals(180L, DurationParser.parseToSeconds("PT3M"))
    assertEquals(185L, DurationParser.parseToSeconds("PT3M5S"))
    assertEquals(3723L, DurationParser.parseToSeconds("PT1H2M3S"))
    assertEquals(3600L, DurationParser.parseToSeconds("PT1H"))
    assertEquals(0L, DurationParser.parseToSeconds("P0D"))
    assertEquals(0L, DurationParser.parseToSeconds(""))
    assertEquals(0L, DurationParser.parseToSeconds(null))
  }

  @Test
  fun testMedianCalculatorRequirements() {
    // Less than 5 items must return null (requirement)
    assertNull(MedianCalculator.calculateMedian(listOf(100L, 200L, 300L, 400L)))
    assertNull(MedianCalculator.calculateMedian(emptyList()))

    // 5 odd items: median is 300
    val oddList = listOf(500L, 100L, 400L, 200L, 300L)
    assertEquals(300L, MedianCalculator.calculateMedian(oddList))

    // 6 even items: [100, 200, 300, 400, 500, 600] -> (300+400)/2 = 350
    val evenList = listOf(600L, 100L, 500L, 200L, 400L, 300L)
    assertEquals(350L, MedianCalculator.calculateMedian(evenList))
  }

  @Test
  fun testOutlierCalculatorMultiplierAndBadges() {
    // Multiplier = viralViews / medianViews (now a Double, not rounded)
    assertEquals(500.0, OutlierCalculator.calculateMultiplier(1_000_000L, 2_000L), 0.0001)
    assertEquals(50.0, OutlierCalculator.calculateMultiplier(250_000L, 5_000L), 0.0001)
    assertEquals(20.0, OutlierCalculator.calculateMultiplier(100_000L, 5_000L), 0.0001)
    assertEquals(8.5, OutlierCalculator.calculateMultiplier(17_000L, 2_000L), 0.0001)
    assertEquals(0.0, OutlierCalculator.calculateMultiplier(1_000L, 0L), 0.0001)

    // Badges: >= 500x MEGA, 50-499x HIGH, <50x LOW
    assertEquals(BadgeTier.MEGA, OutlierCalculator.getBadgeTier(500.0))
    assertEquals(BadgeTier.MEGA, OutlierCalculator.getBadgeTier(1200.0))
    assertEquals(BadgeTier.HIGH, OutlierCalculator.getBadgeTier(50.0))
    assertEquals(BadgeTier.HIGH, OutlierCalculator.getBadgeTier(499.0))
    assertEquals(BadgeTier.LOW, OutlierCalculator.getBadgeTier(49.0))
    assertEquals(BadgeTier.LOW, OutlierCalculator.getBadgeTier(10.0))

    // Views per sub
    val viewsPerSub = OutlierCalculator.calculateViewsPerSub(1_000_000L, 10_000L)
    assertEquals(100.0, viewsPerSub, 0.001)

    // Compact formatting
    assertEquals("450", OutlierCalculator.formatCompactNumber(450L))
    assertEquals("1.2K", OutlierCalculator.formatCompactNumber(1200L))
    assertEquals("500K", OutlierCalculator.formatCompactNumber(500_000L))
    assertEquals("1.5M", OutlierCalculator.formatCompactNumber(1_500_000L))
  }

  @Test
  fun testKeywordTrimmingAndLoosePreset() {
    assertEquals("Minecraft", OutlierCalculator.trimKeyword("  Minecraft  "))
    assertEquals("minecraft shorts", OutlierCalculator.trimKeyword(" minecraft shorts \n"))

    val rawFilter = FilterOptions(keyword = "Minecraft ")
    assertEquals("Minecraft", rawFilter.withTrimmedKeyword().keyword)

    val loose = Presets.applyPreset(PresetType.LOOSE, "  Minecraft  ")
    assertEquals("Minecraft", loose.keyword)
    assertEquals(0L, loose.minSubs)
    assertEquals(100_000L, loose.maxSubs)
    assertEquals(20_000L, loose.maxMedianViews)
    assertEquals(100_000L, loose.minViralViews)
    assertEquals(20, loose.minMultiplier)
    assertEquals("30d", loose.recency)
    assertEquals("Any", loose.regionCode)
    assertEquals("Any", loose.language)
  }

  @Test
  fun testSmartEmptyStageDetectionAndFixes() {
    // 1. Dropped at duration or minViews stage
    val statsMinViewsZero = DebugStats(
      searchReturned = 80,
      afterDurationFilter = 60,
      afterMinViewsFilter = 0
    )
    val stage1 = OutlierCalculator.detectEmptyStage(statsMinViewsZero)
    assertEquals(EmptyStageReason.DURATION_OR_MIN_VIEWS, stage1)
    assertEquals("Itne views wale Shorts nahi mile", stage1.message)
    val fixed1 = OutlierCalculator.applyEmptyStageFix(FilterOptions(keyword = "Minecraft ", minViralViews = 500_000L), stage1)
    assertEquals("Minecraft", fixed1.keyword)
    assertEquals(100_000L, fixed1.minViralViews)

    // 2. Dropped at subs filter stage
    val statsSubsZero = DebugStats(
      searchReturned = 80,
      afterDurationFilter = 60,
      afterMinViewsFilter = 25,
      uniqueChannels = 20,
      afterSubsFilter = 0
    )
    val stage2 = OutlierCalculator.detectEmptyStage(statsSubsZero)
    assertEquals(EmptyStageReason.SUBS_FILTER, stage2)
    assertEquals("Sab channels bade hain (subs zyada)", stage2.message)
    val fixed2 = OutlierCalculator.applyEmptyStageFix(FilterOptions(keyword = "Minecraft"), stage2)
    assertEquals(0L, fixed2.minSubs)
    assertEquals(100_000L, fixed2.maxSubs)
    assertEquals("7d", fixed2.recency)

    // 3. Dropped at median stage
    val statsMedianZero = DebugStats(
      searchReturned = 80,
      afterDurationFilter = 60,
      afterMinViewsFilter = 25,
      uniqueChannels = 20,
      afterSubsFilter = 6,
      channelsWithTooFewShorts = 2,
      channelsMedianTooHigh = 4,
      channelsPassingMedian = 0
    )
    val stage3 = OutlierCalculator.detectEmptyStage(statsMedianZero)
    assertEquals(EmptyStageReason.MEDIAN_STAGE, stage3)
    assertEquals("Channels ke baaki Shorts bhi zyada views le rahe hain", stage3.message)
    val fixed3 = OutlierCalculator.applyEmptyStageFix(FilterOptions(keyword = "Minecraft"), stage3)
    assertEquals(20_000L, fixed3.maxMedianViews)

    // 4. Dropped at multiplier stage
    val statsMultiplierZero = DebugStats(
      searchReturned = 80,
      afterDurationFilter = 60,
      afterMinViewsFilter = 25,
      uniqueChannels = 20,
      afterSubsFilter = 6,
      channelsPassingMedian = 3,
      finalOutliers = 0
    )
    val stage4 = OutlierCalculator.detectEmptyStage(statsMultiplierZero)
    assertEquals(EmptyStageReason.MULTIPLIER_STAGE, stage4)
    assertEquals("Multiplier kam hai", stage4.message)
    val fixed4 = OutlierCalculator.applyEmptyStageFix(FilterOptions(keyword = "Minecraft"), stage4)
    assertEquals(20, fixed4.minMultiplier)
  }

  @Test
  fun testNearMissReasonGeneration() {
    val defaultFilters = FilterOptions(
      minSubs = 0L,
      maxSubs = 10_000L,
      maxMedianViews = 5_000L,
      minMultiplier = 50
    )

    // 1. Subs exceeded: "Subs 250K > max 10K"
    val reasonSubs = OutlierCalculator.buildNearMissReason(
      subscriberCount = 250_000L,
      shortCount = 10,
      medianViews = 2_000L,
      multiplier = 100.0,
      filters = defaultFilters
    )
    assertEquals("Subs 250K > max 10K", reasonSubs)

    // 2. Too few Shorts on channel: "<5 Shorts on channel"
    val reasonFewShorts = OutlierCalculator.buildNearMissReason(
      subscriberCount = 4_000L,
      shortCount = null,
      medianViews = null,
      multiplier = null,
      filters = defaultFilters
    )
    assertEquals("<5 Shorts on channel", reasonFewShorts)

    // 3. Median too high: "Median 12K > max 5K"
    val reasonMedian = OutlierCalculator.buildNearMissReason(
      subscriberCount = 4_000L,
      shortCount = 15,
      medianViews = 12_000L,
      multiplier = 80.0,
      filters = defaultFilters
    )
    assertEquals("Median 12K > max 5K", reasonMedian)

    // 4. Multiplier too low: "Multiplier 8.0x < 50x" (one decimal under 10x)
    val reasonMultiplier = OutlierCalculator.buildNearMissReason(
      subscriberCount = 4_000L,
      shortCount = 15,
      medianViews = 4_000L,
      multiplier = 8.0,
      filters = defaultFilters
    )
    assertEquals("Multiplier 8.0x < 50x", reasonMultiplier)

    // 5. Passing candidate returns null
    val passing = OutlierCalculator.buildNearMissReason(
      subscriberCount = 4_000L,
      shortCount = 15,
      medianViews = 3_000L,
      multiplier = 120.0,
      filters = defaultFilters
    )
    assertNull(passing)
  }

  @Test
  fun testUushIdGeneration() {
    val channelId = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
    val expectedUush = "UUSH_x5XG1OV2P6uZZ5FSM9Ttw"
    assertEquals(expectedUush, UushHelper.getShortsPlaylistId(channelId))

    val otherId = "UCabc123"
    assertEquals("UUSHabc123", UushHelper.getShortsPlaylistId(otherId))
  }

  @Test
  fun testPacificDateQuotaKey() {
    val pacificZone = ZoneId.of("America/Los_Angeles")
    val dateStr = LocalDate.now(pacificZone).toString()
    assertTrue(dateStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
  }

  @Test
  fun testCsvExportUtf8WithBomAndHindiText() {
    val sampleVideo = OutlierVideo(
      videoId = "abc123xyz",
      title = "सफलता का रहस्य | Motivation Short 🔥",
      description = "Hindi motivation short",
      tags = listOf("motivation", "हिंदी"),
      thumbnailUrl = "https://i.ytimg.com/vi/abc123xyz/hqdefault.jpg",
      channelId = "UC_test_channel",
      channelTitle = "मोटिवेशनल चैनल",
      subscriberCount = 500L,
      hiddenSubscriberCount = false,
      views = 1_200_000L,
      channelMedianViews = 1_000L,
      outlierMultiplier = 1200.0,
      viewsPerSub = 2400.0,
      likes = 85_000L,
      comments = 1_200L,
      durationSeconds = 45L,
      publishedAt = "2026-10-15T10:00:00Z",
      shortStatus = ShortStatus.CONFIRMED_SHORT,
      isSaved = true,
      savedAt = 1760000000000L
    )

    val csvBytes = SavedRepository.generateCsvBytesWithBom(listOf(sampleVideo))

    // Verify 3-byte UTF-8 BOM (EF BB BF)
    assertEquals(0xEF.toByte(), csvBytes[0])
    assertEquals(0xBB.toByte(), csvBytes[1])
    assertEquals(0xBF.toByte(), csvBytes[2])

    val contentWithoutBom = String(csvBytes.drop(3).toByteArray(), Charsets.UTF_8)
    val header = contentWithoutBom.lineSequence().firstOrNull().orEmpty()
    assertTrue(header.startsWith("Title,Channel,ChannelURL,Subs,Views,Median,Multiplier,ViewsPerSub,Likes,Comments,Duration,UploadDate,Tags,ShortStatus,VideoURL"))
    assertTrue(header.contains("SavedAt"))
    assertTrue(header.contains("Folder"))
    assertTrue(header.contains("SavedTags"))
    assertTrue(contentWithoutBom.contains("सफलता का रहस्य"))
    assertTrue(contentWithoutBom.contains("मोटिवेशनल चैनल"))
    assertTrue(contentWithoutBom.contains("1200x"))
  }
}
