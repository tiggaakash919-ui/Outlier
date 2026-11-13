package com.outlierradar.app.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

/**
 * Pure helpers behind the discovery modes. Kept out of the repository so they
 * can be unit-tested without Android/Room/Retrofit.
 */
object DiscoveryHelpers {

  const val MAX_MULTI_KEYWORDS = 5
  const val REUPLOAD_SIMILARITY_THRESHOLD = 0.85

  private val HASH_TAG_REGEX = Regex("#[\\p{L}\\p{N}_]+")
  private val WHITESPACE_REGEX = Regex("\\s+")

  private val TITLE_STOPWORDS = setOf(
    "the", "and", "for", "with", "from", "that", "this", "you", "your", "are",
    "was", "were", "has", "have", "had", "but", "not", "all", "can", "how",
    "why", "what", "when", "who", "will", "just", "new", "video", "shorts",
    "short", "viral", "full", "best", "top", "ka", "ki", "ke", "ko", "se",
    "me", "mein", "hai", "ho", "hoga", "aur", "par", "bhi", "ye", "yeh", "wo"
  )

  /** Splits "a, b ,, c" into up to [max] distinct keywords. */
  fun parseKeywordList(raw: String, max: Int = MAX_MULTI_KEYWORDS): List<String> {
    if (raw.isBlank()) return emptyList()
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    raw.split(',', '\n')
      .asSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .forEach { keyword ->
        val key = keyword.lowercase(Locale.ROOT)
        if (seen.add(key)) result.add(keyword)
      }
    return result.take(max)
  }

  fun parseExcludeKeywords(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(',', '\n')
      .asSequence()
      .map { it.trim().lowercase(Locale.ROOT) }
      .filter { it.isNotEmpty() }
      .distinct()
      .take(20)
      .toList()
  }

  /** True when the title or any tag contains one of the excluded phrases. */
  fun matchesExcludeKeyword(title: String, tags: List<String>, excludes: List<String>): Boolean {
    if (excludes.isEmpty()) return false
    val haystack = (title + " " + tags.joinToString(" ")).lowercase(Locale.ROOT)
    return excludes.any { it.isNotBlank() && haystack.contains(it) }
  }

  /**
   * Lowercase, emoji/punctuation-free version of a title used for re-upload
   * detection. Devanagari and other letters are preserved.
   */
  fun normalizeTitle(raw: String): String {
    if (raw.isBlank()) return ""
    val withoutTags = HASH_TAG_REGEX.replace(raw, " ")
    val sb = StringBuilder(withoutTags.length)
    for (ch in withoutTags) {
      when {
        Character.isLetterOrDigit(ch) -> sb.append(ch.lowercaseChar())
        else -> sb.append(' ')
      }
    }
    return sb.toString().replace(WHITESPACE_REGEX, " ").trim()
  }

  /** 1.0 for identical normalized strings; 0.0 when either side is empty. */
  fun titleSimilarity(a: String, b: String): Double {
    val left = normalizeTitle(a)
    val right = normalizeTitle(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val maxLen = max(left.length, right.length)
    val distance = levenshtein(left, right)
    return (1.0 - distance.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)
  }

  /**
   * Flags later re-uploads of the same Short. The earliest upload wins; every
   * later upload with > [threshold] title similarity is marked and points at
   * the root original. Transitively similar titles (A~B, B~C) are grouped.
   */
  fun flagReuploads(
    videos: List<OutlierVideo>,
    threshold: Double = REUPLOAD_SIMILARITY_THRESHOLD
  ): List<OutlierVideo> {
    if (videos.size < 2) return videos

    val normalized = videos.map { normalizeTitle(it.title) }
    // Earliest first, ties broken by higher views, so the winner is deterministic.
    val order = videos.indices.sortedWith(
      compareBy<Int> { publishedMillis(videos[it].publishedAt) }
        .thenByDescending { videos[it].views }
    )

    val flagged = HashMap<Int, String>() // index -> root videoId
    for (pos in order.indices) {
      val current = order[pos]
      val currentNorm = normalized[current]
      if (currentNorm.isEmpty()) continue
      for (prevPos in 0 until pos) {
        val previous = order[prevPos]
        val previousNorm = normalized[previous]
        if (previousNorm.isEmpty()) continue
        // Cheap length guard before the O(n*m) edit distance.
        val minLen = minOf(currentNorm.length, previousNorm.length)
        val maxLen = maxOf(currentNorm.length, previousNorm.length)
        if (minLen.toDouble() / maxLen.toDouble() < 0.6) continue

        if (titleSimilarity(currentNorm, previousNorm) > threshold) {
          flagged[current] = flagged[previous] ?: videos[previous].videoId
          break
        }
      }
    }

    if (flagged.isEmpty() && videos.none { it.isPossibleReupload }) return videos
    return videos.mapIndexed { index, video ->
      val rootId = flagged[index]
      video.copy(
        isPossibleReupload = rootId != null,
        reuploadOfVideoId = rootId
      )
    }
  }

  /**
   * Builds the SIMILAR_TO_OUTLIER query: up to 3 tags OR-ed (YouTube treats
   * "a|b|c" as an OR search). Falls back to significant title words, then to
   * the raw title.
   */
  fun buildSimilarQuery(tags: List<String>, title: String): String {
    val cleanTags = tags.asSequence()
      .map { it.trim().removePrefix("#").trim() }
      .filter { it.length >= 2 }
      .distinctBy { it.lowercase(Locale.ROOT) }
      .take(3)
      .toList()
    if (cleanTags.isNotEmpty()) return cleanTags.joinToString("|")

    val words = normalizeTitle(title)
      .split(' ')
      .asSequence()
      .filter { it.length >= 3 && it !in TITLE_STOPWORDS }
      .distinct()
      .take(3)
      .toList()
    if (words.isNotEmpty()) return words.joinToString(" ")

    return title.trim()
  }

  /**
   * Likes/views threshold. A hidden like count (0) is rejected when a ratio is
   * requested, because it cannot be verified.
   */
  fun passesLikesRatio(likes: Long, views: Long, minRatio: Double): Boolean {
    if (minRatio <= 0.0) return true
    if (views <= 0L || likes <= 0L) return false
    return likes.toDouble() / views.toDouble() >= minRatio
  }

  /** True when the Short was published within [maxDays]. 0 disables the filter. */
  fun isWithinViralAge(
    publishedAt: String,
    maxDays: Int,
    now: Instant = Instant.now()
  ): Boolean {
    if (maxDays <= 0) return true
    if (publishedAt.isBlank()) return true
    return try {
      ChronoUnit.DAYS.between(Instant.parse(publishedAt), now) <= maxDays.toLong()
    } catch (_: Exception) {
      true
    }
  }

  /**
   * Tightest upload window implied by recency and maxViralAgeDays. Used for the
   * search.list `publishedAfter` parameter.
   */
  fun effectiveRecencyDays(recency: String, maxViralAgeDays: Int): Int? {
    val recencyDays = when (recency.lowercase(Locale.ROOT)) {
      "7d" -> 7
      "30d" -> 30
      "90d" -> 90
      "6mo" -> 180
      "1y" -> 365
      else -> null
    }
    return when {
      maxViralAgeDays <= 0 -> recencyDays
      recencyDays == null -> maxViralAgeDays
      else -> minOf(recencyDays, maxViralAgeDays)
    }
  }

  private fun publishedMillis(publishedAt: String): Long {
    if (publishedAt.isBlank()) return Long.MAX_VALUE
    return try {
      Instant.parse(publishedAt).toEpochMilli()
    } catch (_: Exception) {
      Long.MAX_VALUE
    }
  }

  private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(
          previous[j] + 1,
          current[j - 1] + 1,
          previous[j - 1] + cost
        )
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[b.length]
  }
}
