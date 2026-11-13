package com.outlierradar.app.data.repository

import android.graphics.BitmapFactory
import android.util.Log
import com.outlierradar.app.BuildConfig
import com.outlierradar.app.data.local.AiCacheEntity
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.NicheCacheEntity
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.remote.CoarenaClient
import com.outlierradar.app.data.remote.CoarenaException
import com.outlierradar.app.data.remote.CoarenaSchemas
import com.outlierradar.app.domain.AiAnalysis
import com.outlierradar.app.domain.CacheTtl
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.ContentGap
import com.outlierradar.app.domain.ContentGapsReport
import com.outlierradar.app.domain.ContentPack
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.ShotItem
import com.outlierradar.app.domain.ScriptOutline
import com.outlierradar.app.domain.TrendIdea
import com.outlierradar.app.domain.TrendPatternSummary
import com.outlierradar.app.domain.TrendReport
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.pow

private const val TAG = "AiRepository"
private const val ANALYSIS_CACHE_VERSION = "v2"
private const val TREND_CACHE_VERSION = "v1"
private const val MAX_THUMBNAIL_BYTES = 1_500_000L
private const val RATE_LIMIT_MAX_RETRIES = 3
private const val RATE_LIMIT_INITIAL_DELAY_MS = 1000L
private const val RATE_LIMIT_HINGLISH =
  "AI rate limit aa gaya (429). Thodi der baad dobara try karo."
private const val COARENA_AUTH_HINGLISH =
  "Coarena API key galat ya missing hai. Settings me Coarena key check karo."

sealed interface AiResult {
  data class Success(val analysis: AiAnalysis, val isCached: Boolean) : AiResult
  data class Error(val message: String) : AiResult
}

sealed interface TrendReportResult {
  data class Success(val report: TrendReport, val isCached: Boolean) : TrendReportResult
  data class Error(val message: String) : TrendReportResult
}

sealed interface ContentPackResult {
  data class Success(val pack: ContentPack, val isCached: Boolean) : ContentPackResult
  data class Error(val message: String) : ContentPackResult
}

sealed interface ContentGapsResult {
  data class Success(val report: ContentGapsReport, val isCached: Boolean) : ContentGapsResult
  data class Error(val message: String) : ContentGapsResult
}

/** Result of AUTO_EXPAND keyword generation. [fromAi] is false for the offline fallback. */
data class KeywordExpansion(
  val keywords: List<String>,
  val fromAi: Boolean
)

class AiRepository(
  private val database: AppDatabase,
  private val settingsStore: SettingsStore,
  private val coarenaClient: CoarenaClient = CoarenaClient(),
  private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()
) {
  private val aiCacheDao = database.aiCacheDao()
  private val nicheCacheDao = database.nicheCacheDao()

  private val analysisSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "whyViral" to Schema.array(
          items = Schema.string(),
          description = "3 points explaining why this Short went viral: hook, topic, emotion/curiosity"
        ),
        "hookIdeas" to Schema.array(
          items = Schema.string(),
          description = "3 punchy opening lines for a similar viral Short"
        ),
        "titleIdeas" to Schema.array(
          items = Schema.string(),
          description = "3 high-CTR Short titles, max 60 characters each"
        ),
        "contentIdea" to Schema.string(
          description = "One similar Short idea with a 3-step outline"
        ),
        "thumbnailAnalysis" to Schema.string(
          description = "Thumbnail analysis covering visible text, dominant colors, face/emotion if any, and curiosity element"
        ),
        "targetAudience" to Schema.string(
          description = "Primary target audience for this Short"
        ),
        "replicabilityScore" to Schema.integer(
          description = "How easy it is to replicate this Short, from 1 to 10"
        ),
        "replicabilityReason" to Schema.string(
          description = "Brief reason for the replicability score"
        ),
        "scriptOutline" to Schema.obj(
          properties = mapOf(
            "hook0to3s" to Schema.string(description = "Hook for first 0-3 seconds"),
            "body" to Schema.string(description = "Main body/content flow"),
            "payoffLoop" to Schema.string(description = "Payoff or loop ending to drive rewatches")
          )
        ),
        "bestPostingTip" to Schema.string(
          description = "Best posting tip for this kind of Short"
        )
      )
    )
  }

  private val genConfig by lazy {
    generationConfig {
      responseMimeType = "application/json"
      responseSchema = analysisSchema
    }
  }

  private val trendSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "patterns" to Schema.obj(
          properties = mapOf(
            "titleFormulas" to Schema.array(items = Schema.string(), description = "Common title formulas/patterns"),
            "topics" to Schema.array(items = Schema.string(), description = "Common topics/themes"),
            "hookStyles" to Schema.array(items = Schema.string(), description = "Common hook styles"),
            "durations" to Schema.array(items = Schema.string(), description = "Common duration/length patterns")
          )
        ),
        "ideas" to Schema.array(
          items = Schema.obj(
            properties = mapOf(
              "title" to Schema.string(description = "Ready-to-shoot idea title"),
              "hook" to Schema.string(description = "Opening hook for 0-3 seconds"),
              "concept" to Schema.string(description = "Core concept and body plan"),
              "whyItCanWork" to Schema.string(description = "Why this idea can work")
            )
          ),
          description = "Exactly 5 ready-to-shoot Short ideas"
        )
      )
    )
  }

  private val trendGenConfig by lazy {
    generationConfig {
      responseMimeType = "application/json"
      responseSchema = trendSchema
    }
  }

  private val nicheSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "keywords" to Schema.array(
          items = Schema.string(),
          description = "Exactly 6 micro-niche YouTube Shorts search keywords (Hindi, English and Hinglish)"
        )
      )
    )
  }

  private val nicheGenConfig by lazy {
    generationConfig {
      responseMimeType = "application/json"
      responseSchema = nicheSchema
    }
  }

  private val contentPackSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "hookBreakdown" to Schema.string(description = "Why the first 0-3s hook works"),
        "bodyBreakdown" to Schema.string(description = "Body/content flow breakdown"),
        "payoffBreakdown" to Schema.string(description = "Payoff/loop ending breakdown"),
        "pacingNotes" to Schema.string(description = "Pacing and retention notes"),
        "emotionTriggers" to Schema.array(items = Schema.string(), description = "Emotion/curiosity triggers"),
        "retentionDrivers" to Schema.array(items = Schema.string(), description = "Retention drivers"),
        "filmableConcepts" to Schema.array(items = Schema.string(), description = "3 ready-to-film concepts"),
        "titleIdeas" to Schema.array(items = Schema.string(), description = "5 high-CTR title ideas"),
        "hookIdeas" to Schema.array(items = Schema.string(), description = "5 hook lines for 0-3s"),
        "shotList" to Schema.array(
          items = Schema.obj(
            properties = mapOf(
              "shot" to Schema.string(description = "Shot description"),
              "visual" to Schema.string(description = "Visual/camera"),
              "audio" to Schema.string(description = "Audio/voiceover")
            )
          ),
          description = "Shot-by-shot list"
        ),
        "bRollIdeas" to Schema.array(items = Schema.string(), description = "B-roll ideas"),
        "postingTime" to Schema.string(description = "Best posting time/day"),
        "hashtags" to Schema.array(items = Schema.string(), description = "Relevant hashtags")
      )
    )
  }

  private val contentPackGenConfig by lazy {
    generationConfig {
      responseMimeType = "application/json"
      responseSchema = contentPackSchema
    }
  }

  private val contentGapsSchema by lazy {
    Schema.obj(
      properties = mapOf(
        "summary" to Schema.string(description = "One-line summary of the content gap opportunity"),
        "gaps" to Schema.array(
          items = Schema.obj(
            properties = mapOf(
              "topic" to Schema.string(description = "Underserved topic/format"),
              "whyGap" to Schema.string(description = "Why it is a gap"),
              "suggestedIdea" to Schema.string(description = "Suggested Short idea"),
              "estimatedDemand" to Schema.string(description = "Demand level: high/medium/low")
            )
          ),
          description = "List of content gaps"
        )
      )
    )
  }

  private val contentGapsGenConfig by lazy {
    generationConfig {
      responseMimeType = "application/json"
      responseSchema = contentGapsSchema
    }
  }

  private var cachedModelName: String = ""
  private var cachedGenerativeModel: GenerativeModel? = null
  private var cachedTrendModelName: String = ""
  private var cachedTrendModel: GenerativeModel? = null
  private var cachedNicheModelName: String = ""
  private var cachedNicheModel: GenerativeModel? = null
  private var cachedPackModelName: String = ""
  private var cachedPackModel: GenerativeModel? = null
  private var cachedGapsModelName: String = ""
  private var cachedGapsModel: GenerativeModel? = null

  private fun getModelForName(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName != cachedModelName || cachedGenerativeModel == null) {
      cachedModelName = cleanName
      cachedGenerativeModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = genConfig
      )
    }
    return cachedGenerativeModel!!
  }

  private fun getTrendModel(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName != cachedTrendModelName || cachedTrendModel == null) {
      cachedTrendModelName = cleanName
      cachedTrendModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = trendGenConfig
      )
    }
    return cachedTrendModel!!
  }

  private fun getNicheModel(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName != cachedNicheModelName || cachedNicheModel == null) {
      cachedNicheModelName = cleanName
      cachedNicheModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = nicheGenConfig
      )
    }
    return cachedNicheModel ?: Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = nicheGenConfig
    )
  }

  private fun getPackModel(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName != cachedPackModelName || cachedPackModel == null) {
      cachedPackModelName = cleanName
      cachedPackModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = contentPackGenConfig
      )
    }
    return cachedPackModel ?: Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = contentPackGenConfig
    )
  }

  private fun getGapsModel(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName != cachedGapsModelName || cachedGapsModel == null) {
      cachedGapsModelName = cleanName
      cachedGapsModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = contentGapsGenConfig
      )
    }
    return cachedGapsModel ?: Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = contentGapsGenConfig
    )
  }

  private fun normalizeLanguageForPrompt(language: String): String {
    return when (language.trim().lowercase(Locale.ROOT)) {
      "hindi", "hindi (devanagari)", "devanagari", "hi" -> "Hindi (Devanagari script)"
      "english", "en" -> "English"
      else -> "Hinglish (Hindi-English mix, Roman script)"
    }
  }

  private fun languageKey(language: String): String = when (language.trim().lowercase(Locale.ROOT)) {
    "hindi", "hindi (devanagari)", "devanagari", "hi" -> "hindi"
    "english", "en" -> "english"
    else -> "hinglish"
  }

  private fun isRateLimitError(t: Throwable): Boolean {
    var cur: Throwable? = t
    while (cur != null) {
      val msg = (cur.message ?: "").lowercase(Locale.ROOT)
      val name = cur.javaClass.simpleName.lowercase(Locale.ROOT)
      if (msg.contains("429") ||
        msg.contains("resource_exhausted") ||
        msg.contains("resource exhausted") ||
        msg.contains("rate limit") ||
        msg.contains("too many requests") ||
        (msg.contains("quota") && (msg.contains("exceed") || msg.contains("limit")))
      ) return true
      if (name.contains("quota") || name.contains("ratelimit")) return true
      cur = cur.cause
    }
    return false
  }

  private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt <= RATE_LIMIT_MAX_RETRIES) {
      try {
        return block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        if (!isRateLimitError(e) || attempt == RATE_LIMIT_MAX_RETRIES) throw e
        lastError = e
        val delayMs = RATE_LIMIT_INITIAL_DELAY_MS * (2.0.pow(attempt.toDouble())).toLong()
        delay(delayMs)
        attempt++
      }
    }
    throw lastError ?: IllegalStateException("Rate limited")
  }

  private suspend fun downloadThumbnailBitmap(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    if (url.isBlank()) return@withContext null
    try {
      val req = Request.Builder().url(url).get().build()
      okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@withContext null
        val body = resp.body ?: return@withContext null
        val bytes = body.bytes()
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_THUMBNAIL_BYTES) return@withContext null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Thumbnail download failed: ${e.message}")
      null
    }
  }

  // ---------------------------------------------------------------------------
  // AI provider routing (Coarena by default, Firebase Gemini as fallback)
  // ---------------------------------------------------------------------------

  private suspend fun currentProvider(): String =
    settingsStore.aiProvider.firstOrNull()?.uppercase(Locale.ROOT) ?: "COARENA"

  private suspend fun modelNameForProvider(provider: String): String = when (provider) {
    "FIREBASE" -> settingsStore.geminiModel.firstOrNull()?.trim().orEmpty()
      .ifEmpty { "gemini-3.8-flash" }
    else -> settingsStore.coarenaModel.firstOrNull()?.trim().orEmpty()
      .ifEmpty { CoarenaClient.DEFAULT_MODEL }
  }

  private suspend fun resolveCoarenaKey(): String {
    val stored = settingsStore.getCoarenaApiKeyNow().trim()
    if (stored.isNotBlank()) return stored
    return BuildConfig.COARENA_API_KEY.trim()
  }

  private fun bitmapToPngBytes(bitmap: android.graphics.Bitmap): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
  }

  /** Raises a clear, Hinglish error before hitting the network when no key exists. */
  private suspend fun requireCoarenaKey(): String {
    val key = resolveCoarenaKey()
    if (key.isBlank()) {
      throw CoarenaException("Coarena API key missing. Settings me key add karo.")
    }
    return key
  }

  private suspend fun generateWithCoarena(
    modelName: String,
    prompt: String,
    schema: JSONObject,
    thumbnail: android.graphics.Bitmap? = null,
    timeoutMs: Long = 90_000L
  ): String {
    val key = requireCoarenaKey()
    return withRateLimitRetry {
      withTimeout(timeoutMs) {
        coarenaClient.generateJson(
          apiKey = key,
          model = modelName,
          prompt = prompt,
          schema = schema,
          imagePng = thumbnail?.let { bitmapToPngBytes(it) }
        )
      }
    }
  }

  private fun redactKey(message: String, key: String): String {
    if (key.isBlank()) return message
    return message.replace(key, "[REDACTED]")
  }

  private fun mapAiException(e: Exception, modelName: String): String {
    return when {
      e is TimeoutCancellationException -> "AI analysis timed out. Dobara try karo."
      e is CoarenaException && (e.statusCode == 401 || e.statusCode == 403) -> COARENA_AUTH_HINGLISH
      e is CoarenaException && (e.statusCode == 429 || e.statusCode == 503) -> RATE_LIMIT_HINGLISH
      CoarenaClient.isAuthError(e) -> COARENA_AUTH_HINGLISH
      CoarenaClient.isRateLimit(e) || isRateLimitError(e) -> RATE_LIMIT_HINGLISH
      else -> {
        val detail = e.localizedMessage ?: "Unknown error"
        "AI request fail ho gaya ($detail). Settings me model/connection check karo."
      }
    }
  }

  suspend fun analyzeOutlier(
    video: OutlierVideo,
    language: String,
    forceRefresh: Boolean = false
  ): AiResult = withContext(Dispatchers.IO) {
    val promptLanguage = normalizeLanguageForPrompt(language)
    val cacheKey = "${ANALYSIS_CACHE_VERSION}_${video.videoId}_${languageKey(language)}"

    if (!forceRefresh) {
      val cached = aiCacheDao.getAiCache(cacheKey)
      if (cached != null) {
        try {
          val parsed = parseJsonToAnalysis(cached.responseJson)
          return@withContext AiResult.Success(parsed, isCached = true)
        } catch (e: Exception) {
          Log.w(TAG, "Cached AI JSON parse failed, regenerating", e)
        }
      }
    }

    val provider = currentProvider()
    val modelName = modelNameForProvider(provider)
    val prompt = buildPrompt(video, promptLanguage)
    val thumbnail = downloadThumbnailBitmap(video.thumbnailUrl)

    try {
      val jsonString = if (provider == "FIREBASE") {
        val model = getModelForName(modelName)
        val response = withRateLimitRetry {
          withTimeout(60_000L) {
            if (thumbnail != null) {
              model.generateContent(
                content {
                  image(thumbnail)
                  text(prompt)
                }
              )
            } else {
              model.generateContent(prompt)
            }
          }
        }
        response.text
      } else {
        generateWithCoarena(modelName, prompt, CoarenaSchemas.analysis, thumbnail)
      }

      if (jsonString.isNullOrBlank()) {
        return@withContext AiResult.Error("AI se khaali response aaya. Model/settings check karo ($modelName).")
      }

      val analysis = parseJsonToAnalysis(jsonString)

      aiCacheDao.insertAiCache(
        AiCacheEntity(
          cacheKey = cacheKey,
          videoId = video.videoId,
          language = languageKey(language),
          responseJson = jsonString,
          timestamp = System.currentTimeMillis()
        )
      )

      AiResult.Success(analysis, isCached = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Firebase AI generation failed for model=$modelName", e)
      AiResult.Error(mapAiException(e, modelName))
    }
  }

  suspend fun generateContentPack(
    video: OutlierVideo,
    language: String,
    forceRefresh: Boolean = false
  ): ContentPackResult = withContext(Dispatchers.IO) {
    val promptLanguage = normalizeLanguageForPrompt(language)
    val cacheKey = "contentpack_v1_${video.videoId}_${languageKey(language)}"

    if (!forceRefresh) {
      val cached = aiCacheDao.getAiCache(cacheKey)
      if (cached != null) {
        try {
          val parsed = parseContentPack(cached.responseJson)
          return@withContext ContentPackResult.Success(parsed, isCached = true)
        } catch (e: Exception) {
          Log.w(TAG, "Cached content pack parse failed, regenerating", e)
        }
      }
    }

    val provider = currentProvider()
    val modelName = modelNameForProvider(provider)
    val prompt = buildContentPackPrompt(video, promptLanguage)
    val thumbnail = downloadThumbnailBitmap(video.thumbnailUrl)

    try {
      val jsonString = if (provider == "FIREBASE") {
        val model = getPackModel(modelName)
        val response = withRateLimitRetry {
          withTimeout(90_000L) {
            if (thumbnail != null) {
              model.generateContent(
                content {
                  image(thumbnail)
                  text(prompt)
                }
              )
            } else {
              model.generateContent(prompt)
            }
          }
        }
        response.text
      } else {
        generateWithCoarena(modelName, prompt, CoarenaSchemas.contentPack, thumbnail)
      }
      if (jsonString.isNullOrBlank()) {
        return@withContext ContentPackResult.Error("Content pack ka response khaali aaya. Dobara try karo.")
      }
      val pack = parseContentPack(jsonString)
      aiCacheDao.insertAiCache(
        AiCacheEntity(
          cacheKey = cacheKey,
          videoId = video.videoId,
          language = languageKey(language),
          responseJson = jsonString,
          timestamp = System.currentTimeMillis()
        )
      )
      ContentPackResult.Success(pack, isCached = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Content pack generation failed for model=$modelName", e)
      ContentPackResult.Error(mapAiException(e, modelName))
    }
  }

  suspend fun generateContentGaps(
    keyword: String,
    language: String,
    forceRefresh: Boolean = false
  ): ContentGapsResult = withContext(Dispatchers.IO) {
    val cleanKw = keyword.trim()
    if (cleanKw.isBlank()) {
      return@withContext ContentGapsResult.Error("Keyword daalo pehle.")
    }
    val promptLanguage = normalizeLanguageForPrompt(language)
    val cacheKey = "contentgaps_v1_${sha256(cleanKw).take(16)}_${languageKey(language)}"

    if (!forceRefresh) {
      val cached = aiCacheDao.getAiCache(cacheKey)
      if (cached != null) {
        try {
          val parsed = parseContentGaps(cached.responseJson)
          return@withContext ContentGapsResult.Success(parsed, isCached = true)
        } catch (e: Exception) {
          Log.w(TAG, "Cached content gaps parse failed, regenerating", e)
        }
      }
    }

    val provider = currentProvider()
    val modelName = modelNameForProvider(provider)
    val prompt = buildContentGapsPrompt(cleanKw, promptLanguage)

    try {
      val jsonString = if (provider == "FIREBASE") {
        val model = getGapsModel(modelName)
        val response = withRateLimitRetry {
          withTimeout(60_000L) { model.generateContent(prompt) }
        }
        response.text
      } else {
        generateWithCoarena(modelName, prompt, CoarenaSchemas.contentGaps)
      }
      if (jsonString.isNullOrBlank()) {
        return@withContext ContentGapsResult.Error("Content gaps ka response khaali aaya. Dobara try karo.")
      }
      val report = parseContentGaps(jsonString)
      aiCacheDao.insertAiCache(
        AiCacheEntity(
          cacheKey = cacheKey,
          videoId = "__contentgaps__",
          language = languageKey(language),
          responseJson = jsonString,
          timestamp = System.currentTimeMillis()
        )
      )
      ContentGapsResult.Success(report, isCached = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Content gaps generation failed for model=$modelName", e)
      ContentGapsResult.Error(mapAiException(e, modelName))
    }
  }

  suspend fun generateTrendReport(
    videos: List<OutlierVideo>,
    language: String,
    forceRefresh: Boolean = false
  ): TrendReportResult = withContext(Dispatchers.IO) {
    val selected = videos.distinctBy { it.videoId }.take(20)
    if (selected.size < 5) {
      return@withContext TrendReportResult.Error("Trend report ke liye kam se kam 5 outliers select karo.")
    }
    if (selected.size > 20) {
      return@withContext TrendReportResult.Error("Trend report ke liye zyada se zyada 20 outliers select karo.")
    }

    val promptLanguage = normalizeLanguageForPrompt(language)
    val idsKey = selected.map { it.videoId }.sorted().joinToString(",")
    val hash = sha256(idsKey).take(16)
    val cacheKey = "trend_${TREND_CACHE_VERSION}_${hash}_${languageKey(language)}"

    if (!forceRefresh) {
      val cached = aiCacheDao.getAiCache(cacheKey)
      if (cached != null) {
        try {
          val parsed = parseTrendReport(cached.responseJson)
          return@withContext TrendReportResult.Success(parsed, isCached = true)
        } catch (e: Exception) {
          Log.w(TAG, "Cached trend report parse failed, regenerating", e)
        }
      }
    }

    val provider = currentProvider()
    val modelName = modelNameForProvider(provider)
    val prompt = buildTrendPrompt(selected, promptLanguage)

    try {
      val jsonString = if (provider == "FIREBASE") {
        val model = getTrendModel(modelName)
        val response = withRateLimitRetry {
          withTimeout(90_000L) { model.generateContent(prompt) }
        }
        response.text
      } else {
        generateWithCoarena(modelName, prompt, CoarenaSchemas.trend)
      }
      if (jsonString.isNullOrBlank()) {
        return@withContext TrendReportResult.Error("Trend report ka response khaali aaya. Dobara try karo.")
      }
      val report = parseTrendReport(jsonString)

      aiCacheDao.insertAiCache(
        AiCacheEntity(
          cacheKey = cacheKey,
          videoId = "__trend__",
          language = languageKey(language),
          responseJson = jsonString,
          timestamp = System.currentTimeMillis()
        )
      )

      TrendReportResult.Success(report, isCached = false)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Trend report generation failed for model=$modelName", e)
      TrendReportResult.Error(mapAiException(e, modelName))
    }
  }

  private fun sha256(input: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
  }

  private fun buildPrompt(video: OutlierVideo, language: String): String {
    val descTrimmed = video.description.take(500).replace("\n", " ")
    val tagsStr = if (video.tags.isEmpty()) "none" else video.tags.joinToString(", ")
    val subsFormatted = OutlierCalculator.formatCompactNumber(video.subscriberCount)
    val viewsFormatted = OutlierCalculator.formatCompactNumber(video.views)
    val medianFormatted = OutlierCalculator.formatCompactNumber(video.channelMedianViews)
    val uploadDate = if (video.publishedAt.length >= 10) video.publishedAt.substring(0, 10) else video.publishedAt

    return """
You are a YouTube Shorts growth analyst.
You ONLY see the provided metadata and the attached thumbnail image (if any). Do NOT invent or assume watch-content, spoken words, scenes, audio, or events not visible in the metadata/thumbnail. Do not claim to have watched the video.
Analyze this outlier Short using only that evidence.
Title: ${video.title}
Description (first 500 chars): $descTrimmed
Tags: $tagsStr
Channel: ${video.channelTitle} (~$subsFormatted subs)
Views: $viewsFormatted | Channel median: $medianFormatted | Outlier: ${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x
Duration: ${video.durationSeconds}s | Uploaded: $uploadDate | Likes: ${video.likes} | Comments: ${video.comments}

Respond in $language.
Return JSON with:
- whyViral: [3 points: hook, topic, emotion/curiosity]
- hookIdeas: [3 punchy opening lines]
- titleIdeas: [3 high-CTR titles, max 60 chars each]
- contentIdea: one similar Short idea with a 3-step outline
- thumbnailAnalysis: one text covering visible text, dominant colors, face/emotion if any, and curiosity element
- targetAudience: primary target audience
- replicabilityScore: integer 1-10
- replicabilityReason: brief reason for score
- scriptOutline: {hook0to3s, body, payoffLoop}
- bestPostingTip: best posting tip
""".trimIndent()
  }

  private fun buildTrendPrompt(videos: List<OutlierVideo>, language: String): String {
    val items = videos.mapIndexed { idx, v ->
      val tags = if (v.tags.isEmpty()) "none" else v.tags.take(8).joinToString(", ")
      val desc = v.description.take(140).replace("\n", " ")
      "${idx + 1}. Title: ${v.title} | Channel: ${v.channelTitle} | Views: ${v.views} | Median: ${v.channelMedianViews} | " +
        "Multiplier: ${OutlierCalculator.formatMultiplier(v.outlierMultiplier)}x | Duration: ${v.durationSeconds}s | " +
        "Published: ${v.publishedAt.take(10)} | Tags: $tags | Desc: $desc"
    }.joinToString("\n")

    return """
You are a YouTube Shorts trend analyst.
You ONLY see the metadata listed below for ${videos.size} outlier Shorts. You do NOT see watch-content, audio, or thumbnails. Do NOT invent or assume content not present in this metadata.
Find common patterns across these outliers: title formulas, topics, hook styles, and durations. Then produce exactly 5 ready-to-shoot Short ideas based on those patterns.

Outliers:
$items

Respond in $language.
Return JSON with:
- patterns: {titleFormulas:[...], topics:[...], hookStyles:[...], durations:[...]}
- ideas: exactly 5 items, each {title, hook, concept, whyItCanWork}
""".trimIndent()
  }

  private fun buildContentPackPrompt(video: OutlierVideo, language: String): String {
    val tags = if (video.tags.isEmpty()) "none" else video.tags.take(8).joinToString(", ")
    val desc = video.description.take(200).replace("\n", " ")
    return """
You are a YouTube Shorts viral-content strategist. You ONLY see metadata for one outlier Short. Do NOT invent watch-content, audio, or thumbnail details not present.
Outlier: Title: ${video.title} | Channel: ${video.channelTitle} | Views: ${video.views} | Median: ${video.channelMedianViews} | Multiplier: ${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x | Duration: ${video.durationSeconds}s | Published: ${video.publishedAt.take(10)} | Tags: $tags | Desc: $desc
Produce a ready-to-film content pack that a creator with ~500 subscribers can replicate to chase 100k views. Break down why it went viral (hook/body/payoff/pacing/emotion/retention), then give 3 filmable concepts, 5 titles, 5 hooks, a shot list, B-roll ideas, best posting time, and hashtags.
Respond in $language.
Return JSON with keys: hookBreakdown, bodyBreakdown, payoffBreakdown, pacingNotes, emotionTriggers[], retentionDrivers[], filmableConcepts[], titleIdeas[], hookIdeas[], shotList[{shot,visual,audio}], bRollIdeas[], postingTime, hashtags[].
""".trimIndent()
  }

  private fun buildContentGapsPrompt(keyword: String, language: String): String {
    return """
You are a YouTube Shorts niche analyst. For the niche keyword "$keyword", identify content gaps: topics/formats that are under-served but have high demand potential. Suggest concrete Short ideas.
Respond in $language.
Return JSON with: summary (one line) and gaps[] where each has topic, whyGap, suggestedIdea, estimatedDemand (high/medium/low).
""".trimIndent()
  }

  private fun parseStringArray(json: JSONObject, key: String): List<String> {
    val arr = json.optJSONArray(key) ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until arr.length()) {
      val item = arr.optString(i).trim()
      if (item.isNotBlank()) out.add(item)
    }
    return out
  }

  private fun parseJsonToAnalysis(jsonString: String): AiAnalysis {
    val json = JSONObject(jsonString)
    val scriptObj = json.optJSONObject("scriptOutline")
    val replicabilityFromObj = json.optJSONObject("replicability")?.optInt("score", 0) ?: 0
    val replicabilityReasonFromObj = json.optJSONObject("replicability")?.optString("reason", "") ?: ""

    val score = when {
      json.has("replicabilityScore") -> json.optInt("replicabilityScore", 0)
      replicabilityFromObj > 0 -> replicabilityFromObj
      else -> 0
    }.coerceIn(0, 10)

    val reason = json.optString("replicabilityReason", replicabilityReasonFromObj)

    return AiAnalysis(
      whyViral = parseStringArray(json, "whyViral"),
      hookIdeas = parseStringArray(json, "hookIdeas"),
      titleIdeas = parseStringArray(json, "titleIdeas"),
      contentIdea = json.optString("contentIdea", ""),
      thumbnailAnalysis = json.optString("thumbnailAnalysis", ""),
      targetAudience = json.optString("targetAudience", ""),
      replicabilityScore = score,
      replicabilityReason = reason,
      scriptOutline = ScriptOutline(
        hook0to3s = scriptObj?.optString("hook0to3s", "") ?: "",
        body = scriptObj?.optString("body", "") ?: "",
        payoffLoop = scriptObj?.optString("payoffLoop", "") ?: ""
      ),
      bestPostingTip = json.optString("bestPostingTip", "")
    )
  }

  private fun parseTrendReport(jsonString: String): TrendReport {
    val json = JSONObject(jsonString)
    val patterns = json.optJSONObject("patterns") ?: JSONObject()
    val ideasArr = json.optJSONArray("ideas") ?: JSONArray()
    val ideas = mutableListOf<TrendIdea>()
    for (i in 0 until ideasArr.length()) {
      val obj = ideasArr.optJSONObject(i) ?: continue
      val idea = TrendIdea(
        title = obj.optString("title", ""),
        hook = obj.optString("hook", ""),
        concept = obj.optString("concept", ""),
        whyItCanWork = obj.optString("whyItCanWork", "")
      )
      if (idea.title.isNotBlank() || idea.concept.isNotBlank()) ideas.add(idea)
    }
    return TrendReport(
      patterns = TrendPatternSummary(
        titleFormulas = parseStringArray(patterns, "titleFormulas"),
        topics = parseStringArray(patterns, "topics"),
        hookStyles = parseStringArray(patterns, "hookStyles"),
        durations = parseStringArray(patterns, "durations")
      ),
      ideas = ideas.take(5)
    )
  }

  private fun parseContentPack(jsonString: String): ContentPack {
    val json = JSONObject(jsonString)
    val shotArr = json.optJSONArray("shotList") ?: JSONArray()
    val shots = mutableListOf<ShotItem>()
    for (i in 0 until shotArr.length()) {
      val o = shotArr.optJSONObject(i) ?: continue
      shots.add(ShotItem(
        shot = o.optString("shot", ""),
        visual = o.optString("visual", ""),
        audio = o.optString("audio", "")
      ))
    }
    return ContentPack(
      hookBreakdown = json.optString("hookBreakdown", ""),
      bodyBreakdown = json.optString("bodyBreakdown", ""),
      payoffBreakdown = json.optString("payoffBreakdown", ""),
      pacingNotes = json.optString("pacingNotes", ""),
      emotionTriggers = parseStringArray(json, "emotionTriggers"),
      retentionDrivers = parseStringArray(json, "retentionDrivers"),
      filmableConcepts = parseStringArray(json, "filmableConcepts"),
      titleIdeas = parseStringArray(json, "titleIdeas"),
      hookIdeas = parseStringArray(json, "hookIdeas"),
      shotList = shots,
      bRollIdeas = parseStringArray(json, "bRollIdeas"),
      postingTime = json.optString("postingTime", ""),
      hashtags = parseStringArray(json, "hashtags")
    )
  }

  private fun parseContentGaps(jsonString: String): ContentGapsReport {
    val json = JSONObject(jsonString)
    val arr = json.optJSONArray("gaps") ?: JSONArray()
    val gaps = mutableListOf<ContentGap>()
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      gaps.add(ContentGap(
        topic = o.optString("topic", ""),
        whyGap = o.optString("whyGap", ""),
        suggestedIdea = o.optString("suggestedIdea", ""),
        estimatedDemand = o.optString("estimatedDemand", "")
      ))
    }
    return ContentGapsReport(gaps = gaps, summary = json.optString("summary", ""))
  }

  /**
   * Validates a Coarena key (or the currently configured one) against
   * `GET /models`. Returns null when the key works, otherwise a Hinglish error.
   */
  suspend fun testCoarenaConnection(keyOverride: String? = null): String? = withContext(Dispatchers.IO) {
    val key = keyOverride?.trim()?.takeIf { it.isNotBlank() } ?: resolveCoarenaKey()
    if (key.isBlank()) {
      return@withContext "Coarena API key missing hai. Key enter karke Save karo."
    }
    try {
      val models = coarenaClient.listModels(key)
      if (models.isEmpty()) "Coarena se koi model nahi mila. Dobara try karo." else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      redactKey(mapAiException(e, CoarenaClient.DEFAULT_MODEL), key)
    }
  }

  /**
   * AUTO_EXPAND: asks Gemini for 6 micro-niche keyword variations, mixing
   * Hindi, English and Hinglish. Falls back to the hardcoded
   * [OutlierCalculator.getMicroNichesFor] list whenever the model or cache is
   * unavailable, so the mode always works offline.
   */
  suspend fun expandKeywords(
    keyword: String,
    language: String = "Hinglish",
    forceRefresh: Boolean = false
  ): KeywordExpansion = withContext(Dispatchers.IO) {
    val seed = keyword.trim()
    if (seed.isEmpty()) {
      return@withContext KeywordExpansion(
        keywords = OutlierCalculator.getMicroNichesFor(""),
        fromAi = false
      )
    }
    val normLang = language.trim().ifEmpty { "Hinglish" }
    val cacheKey = "${seed.lowercase(Locale.ROOT)}_${normLang.lowercase(Locale.ROOT)}"

    if (!forceRefresh) {
      val cached = nicheCacheDao.getNicheCache(cacheKey)
      if (cached != null &&
        CacheTtl.isFresh(System.currentTimeMillis(), cached.timestamp, CacheTtl.NICHE_MS)
      ) {
        val parsed = runCatching { parseKeywordsJson(cached.keywordsJson) }.getOrNull()
        if (!parsed.isNullOrEmpty()) {
          return@withContext KeywordExpansion(parsed.take(6), fromAi = true)
        }
      }
    }

    val provider = currentProvider()
    val modelName = modelNameForProvider(provider)
    val prompt = buildNichePrompt(seed, normLang)

    try {
      val jsonString = if (provider == "FIREBASE") {
        val model = getNicheModel(modelName)
        val response = withTimeout(30_000L) { model.generateContent(prompt) }
        response.text
      } else {
        generateWithCoarena(
          modelName = modelName,
          prompt = prompt,
          schema = CoarenaSchemas.niche,
          timeoutMs = 30_000L
        )
      }
      if (!jsonString.isNullOrBlank()) {
        val aiKeywords = sanitizeKeywords(parseKeywordsJson(jsonString), seed)
        // Top up with the offline list so the mode always offers 6 chips.
        val keywords = (aiKeywords + sanitizeKeywords(OutlierCalculator.getMicroNichesFor(seed), seed))
          .distinctBy { it.lowercase(Locale.ROOT) }
          .take(6)
        if (keywords.size >= 6) {
          nicheCacheDao.insertNicheCache(
            NicheCacheEntity(
              cacheKey = cacheKey,
              keyword = seed,
              language = normLang,
              keywordsJson = JSONArray(keywords).toString(),
              timestamp = System.currentTimeMillis()
            )
          )
          return@withContext KeywordExpansion(keywords, fromAi = true)
        }
      }
      Log.w(TAG, "Niche expansion returned too few keywords; using offline fallback")
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "Niche expansion timed out", e)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Niche expansion failed for model=$modelName", e)
    }

    KeywordExpansion(
      keywords = sanitizeKeywords(OutlierCalculator.getMicroNichesFor(seed), seed).take(6),
      fromAi = false
    )
  }

  private fun buildNichePrompt(keyword: String, language: String): String {
    return """
You are a YouTube Shorts niche researcher for the Indian market.
Seed keyword: "$keyword"
Generate exactly 6 micro-niche search keywords that are likely to surface viral Shorts from small channels.
Rules:
- Mix languages: some Hindi (Devanagari), some English, some Hinglish (Roman script).
- 2 to 5 words each, no hashtags, no quotes, no numbering, no duplicates.
- Do not repeat the seed keyword verbatim; go one level deeper into sub-topics.
- Respond in $language where it makes sense, and return JSON only.
Return: {"keywords":["...","...","...","...","...","..."]}
""".trimIndent()
  }

  private fun parseKeywordsJson(jsonString: String): List<String> {
    val trimmed = jsonString.trim()
    val array = if (trimmed.startsWith("[")) {
      JSONArray(trimmed)
    } else {
      JSONObject(trimmed).optJSONArray("keywords")
    } ?: return emptyList()
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) {
      val item = array.optString(i).trim()
      if (item.isNotEmpty()) result.add(item)
    }
    return result
  }

  private fun sanitizeKeywords(raw: List<String>, seed: String): List<String> {
    val seedNorm = DiscoveryHelpers.normalizeTitle(seed)
    return raw.asSequence()
      .map { it.removePrefix("-").trim().removeSurrounding("\"").removePrefix("#").trim() }
      .filter { it.isNotEmpty() && it.length <= 60 }
      .filter { it.lowercase(Locale.ROOT) != seed.trim().lowercase(Locale.ROOT) }
      .filter { DiscoveryHelpers.normalizeTitle(it) != seedNorm }
      .distinctBy { it.lowercase(Locale.ROOT) }
      .take(6)
      .toList()
  }
}
