package com.example

import com.example.data.repository.SavedRepository
import com.example.domain.BadgeTier
import com.example.domain.DurationParser
import com.example.domain.MedianCalculator
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import com.example.domain.UushHelper
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
    // Multiplier = viralViews / medianViews
    assertEquals(500, OutlierCalculator.calculateMultiplier(1_000_000L, 2_000L))
    assertEquals(50, OutlierCalculator.calculateMultiplier(250_000L, 5_000L))
    assertEquals(20, OutlierCalculator.calculateMultiplier(100_000L, 5_000L))

    // Badges: >= 500x MEGA, 50-499x HIGH, <50x LOW
    assertEquals(BadgeTier.MEGA, OutlierCalculator.getBadgeTier(500))
    assertEquals(BadgeTier.MEGA, OutlierCalculator.getBadgeTier(1200))
    assertEquals(BadgeTier.HIGH, OutlierCalculator.getBadgeTier(50))
    assertEquals(BadgeTier.HIGH, OutlierCalculator.getBadgeTier(499))
    assertEquals(BadgeTier.LOW, OutlierCalculator.getBadgeTier(49))
    assertEquals(BadgeTier.LOW, OutlierCalculator.getBadgeTier(10))

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
      outlierMultiplier = 1200,
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
    assertTrue(
      contentWithoutBom.startsWith(
        "Title,Channel,ChannelURL,Subs,Views,Median,Multiplier,ViewsPerSub,Likes,Comments,Duration,UploadDate,Tags,ShortStatus,VideoURL,SavedAt"
      )
    )
    assertTrue(contentWithoutBom.contains("सफलता का रहस्य"))
    assertTrue(contentWithoutBom.contains("मोटिवेशनल चैनल"))
    assertTrue(contentWithoutBom.contains("1200x"))
  }
}
