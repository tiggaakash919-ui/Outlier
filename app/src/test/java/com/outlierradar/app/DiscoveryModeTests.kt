package com.outlierradar.app

import com.outlierradar.app.domain.ChannelInputParser
import com.outlierradar.app.domain.DiscoveryCacheKey
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.DiscoveryMode
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DiscoveryModeTests {

  // ---------------------------------------------------------------- parsing

  @Test
  fun testParseKeywordListTrimsDedupesAndCapsAtFive() {
    val parsed = DiscoveryHelpers.parseKeywordList(
      " minecraft, Cooking ,, MINECRAFT, facts, gaming, comedy, tech "
    )
    assertEquals(listOf("minecraft", "Cooking", "facts", "gaming", "comedy"), parsed)
    assertEquals(5, parsed.size)
    assertTrue(DiscoveryHelpers.parseKeywordList("   ").isEmpty())
  }

  @Test
  fun testExcludeKeywordsMatchTitleAndTags() {
    val excludes = DiscoveryHelpers.parseExcludeKeywords("song, EDIT, status")
    assertTrue(
      DiscoveryHelpers.matchesExcludeKeyword(
        "Best song ever", emptyList(), excludes
      )
    )
    assertTrue(
      DiscoveryHelpers.matchesExcludeKeyword(
        "Random clip", listOf("status", "funny"), excludes
      )
    )
    assertFalse(
      DiscoveryHelpers.matchesExcludeKeyword(
        "Cooking recipe", listOf("food"), excludes
      )
    )
  }

  // ---------------------------------------------------------------- filters

  @Test
  fun testLikesRatioRejectsHiddenLikes() {
    assertTrue(DiscoveryHelpers.passesLikesRatio(0L, 1_000_000L, 0.0))
    assertTrue(DiscoveryHelpers.passesLikesRatio(20_000L, 1_000_000L, 0.02))
    assertFalse(DiscoveryHelpers.passesLikesRatio(10_000L, 1_000_000L, 0.02))
    // A hidden like count cannot be verified against a requested ratio.
    assertFalse(DiscoveryHelpers.passesLikesRatio(0L, 1_000_000L, 0.01))
  }

  @Test
  fun testViralAgeFilter() {
    val now = Instant.parse("2026-11-13T00:00:00Z")
    assertTrue(DiscoveryHelpers.isWithinViralAge("2026-11-10T00:00:00Z", 7, now))
    assertFalse(DiscoveryHelpers.isWithinViralAge("2026-10-01T00:00:00Z", 7, now))
    assertTrue(DiscoveryHelpers.isWithinViralAge("2020-01-01T00:00:00Z", 0, now))
  }

  @Test
  fun testEffectiveRecencyUsesTighterWindow() {
    assertEquals(7, DiscoveryHelpers.effectiveRecencyDays("90d", 7))
    assertEquals(30, DiscoveryHelpers.effectiveRecencyDays("30d", 90))
    assertNull(DiscoveryHelpers.effectiveRecencyDays("any", 0))
    assertEquals(7, DiscoveryHelpers.effectiveRecencyDays("any", 7))
  }

  // ---------------------------------------------------------------- re-uploads

  @Test
  fun testTitleSimilarityIgnoresCaseEmojiAndHashtags() {
    val similarity = DiscoveryHelpers.titleSimilarity(
      "Minecraft But 100 Days 😱 #shorts",
      "minecraft but 100 days"
    )
    assertTrue(similarity > 0.85)
    assertEquals(1.0, DiscoveryHelpers.titleSimilarity("Cooking", "cooking!!"), 0.0001)
    assertEquals(0.0, DiscoveryHelpers.titleSimilarity("", "cooking"), 0.0001)
  }

  @Test
  fun testFlagReuploadsKeepsEarliestAndFlagsLater() {
    val earliest = video(
      id = "aaa",
      title = "Minecraft But 100 Days",
      publishedAt = "2026-01-01T00:00:00Z",
      views = 1_000_000L
    )
    val later = video(
      id = "bbb",
      title = "minecraft but 100 days 😱 #shorts",
      publishedAt = "2026-02-01T00:00:00Z",
      views = 900_000L
    )
    val unrelated = video(
      id = "ccc",
      title = "Best cooking hacks",
      publishedAt = "2026-01-15T00:00:00Z",
      views = 700_000L
    )

    val flagged = DiscoveryHelpers.flagReuploads(listOf(later, unrelated, earliest))
      .associateBy { it.videoId }

    assertFalse(flagged.getValue("aaa").isPossibleReupload)
    assertTrue(flagged.getValue("bbb").isPossibleReupload)
    assertEquals("aaa", flagged.getValue("bbb").reuploadOfVideoId)
    assertFalse(flagged.getValue("ccc").isPossibleReupload)
  }

  // ---------------------------------------------------------------- similar

  @Test
  fun testBuildSimilarQueryUsesTopTags() {
    val query = DiscoveryHelpers.buildSimilarQuery(
      listOf("#minecraft", "100days", "survival", "ignored"),
      "Some title"
    )
    assertEquals("minecraft|100days|survival", query)
  }

  @Test
  fun testBuildSimilarQueryFallsBackToTitleWords() {
    val query = DiscoveryHelpers.buildSimilarQuery(
      emptyList(),
      "This is the best Minecraft survival trick"
    )
    assertEquals("minecraft survival trick", query)
  }

  // ---------------------------------------------------------------- channel input

  @Test
  fun testChannelInputParserHandlesAllShapes() {
    val handle = ChannelInputParser.parse("https://www.youtube.com/@MrBeast?si=abc")
    assertNotNull(handle)
    assertEquals(ChannelInputParser.Kind.HANDLE, handle!!.kind)
    assertEquals("MrBeast", handle.value)

    val atHandle = ChannelInputParser.parse("@mkbhd")
    assertEquals(ChannelInputParser.Kind.HANDLE, atHandle!!.kind)
    assertEquals("mkbhd", atHandle.value)

    val channelUrl = ChannelInputParser.parse(
      "https://youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA/"
    )
    assertEquals(ChannelInputParser.Kind.ID, channelUrl!!.kind)
    assertEquals("UCX6OQ3DkcsbYNE6H8uQQuVA", channelUrl.value)

    val legacy = ChannelInputParser.parse("youtube.com/c/SomeCreator")
    assertEquals(ChannelInputParser.Kind.USERNAME, legacy!!.kind)
    assertEquals("SomeCreator", legacy.value)

    assertNull(ChannelInputParser.parse("   "))
  }

  // ---------------------------------------------------------------- cache key

  @Test
  fun testCacheKeyVariesByModeAndNewFilters() {
    val base = FilterOptions(keyword = "minecraft")
    val multi = base.copy(mode = DiscoveryMode.MULTI_KEYWORD, multiKeywords = "a,b")
    val excluded = base.copy(excludeKeywords = "song")
    val likes = base.copy(minLikesRatio = 0.02)
    val age = base.copy(maxViralAgeDays = 7)
    val channel = base.copy(mode = DiscoveryMode.CHANNEL_SCAN, channelInput = "@abc")

    val baseKey = DiscoveryCacheKey.build(base)
    assertTrue(baseKey.startsWith("v=3|"))
    assertNotEquals(baseKey, DiscoveryCacheKey.build(multi))
    assertNotEquals(baseKey, DiscoveryCacheKey.build(excluded))
    assertNotEquals(baseKey, DiscoveryCacheKey.build(likes))
    assertNotEquals(baseKey, DiscoveryCacheKey.build(age))
    assertNotEquals(baseKey, DiscoveryCacheKey.build(channel))
    assertEquals(baseKey, DiscoveryCacheKey.build(base.copy(keyword = "  minecraft  ")))
  }

  // ---------------------------------------------------------------- fallback

  @Test
  fun testOfflineMicroNicheFallbackIsAlwaysAvailable() {
    val fallback = OutlierCalculator.getMicroNichesFor("minecraft")
    assertTrue(fallback.isNotEmpty())
    assertTrue(fallback.size <= 6)
    assertTrue(OutlierCalculator.getMicroNichesFor("random niche").isNotEmpty())
  }

  private fun video(
    id: String,
    title: String,
    publishedAt: String,
    views: Long
  ): OutlierVideo = OutlierVideo(
    videoId = id,
    title = title,
    description = "",
    tags = emptyList(),
    thumbnailUrl = "",
    channelId = "UC$id",
    channelTitle = "Channel $id",
    subscriberCount = 1_000L,
    hiddenSubscriberCount = false,
    views = views,
    channelMedianViews = 1_000L,
    outlierMultiplier = views / 1_000.0,
    viewsPerSub = views / 1_000.0,
    likes = 10_000L,
    comments = 100L,
    durationSeconds = 45L,
    publishedAt = publishedAt
  )
}
