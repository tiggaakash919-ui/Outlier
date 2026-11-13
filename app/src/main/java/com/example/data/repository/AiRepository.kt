package com.example.data.repository

import android.util.Log
import com.example.data.local.AiCacheEntity
import com.example.data.local.AppDatabase
import com.example.data.local.NicheCacheEntity
import com.example.data.local.SettingsStore
import com.example.domain.AiAnalysis
import com.example.domain.CacheTtl
import com.example.domain.DiscoveryHelpers
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "AiRepository"

sealed interface AiResult {
  data class Success(val analysis: AiAnalysis, val isCached: Boolean) : AiResult
  data class Error(val message: String) : AiResult
}

/** Result of AUTO_EXPAND keyword generation. [fromAi] is false for the offline fallback. */
data class KeywordExpansion(
  val keywords: List<String>,
  val fromAi: Boolean
)

class AiRepository(
  private val database: AppDatabase,
  private val settingsStore: SettingsStore
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

  private var cachedNicheModelName: String = ""
  private var cachedNicheModel: GenerativeModel? = null

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

  private var cachedModelName: String = ""
  private var cachedGenerativeModel: GenerativeModel? = null

  private val defaultModel by lazy {
    Firebase.ai.generativeModel(
      modelName = "gemini-3.8-flash",
      generationConfig = genConfig
    )
  }

  private fun getModelForName(modelName: String): GenerativeModel {
    val cleanName = modelName.trim().ifEmpty { "gemini-3.8-flash" }
    if (cleanName == "gemini-3.8-flash" && cachedGenerativeModel == null) {
      return defaultModel
    }
    if (cleanName != cachedModelName || cachedGenerativeModel == null) {
      cachedModelName = cleanName
      cachedGenerativeModel = Firebase.ai.generativeModel(
        modelName = cleanName,
        generationConfig = genConfig
      )
    }
    return cachedGenerativeModel ?: defaultModel
  }

  suspend fun analyzeOutlier(
    video: OutlierVideo,
    language: String,
    forceRefresh: Boolean = false
  ): AiResult = withContext(Dispatchers.IO) {
    val normLang = language.trim().ifEmpty { "Hinglish" }
    val cacheKey = "${video.videoId}_${normLang.lowercase(Locale.ROOT)}"

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

    val modelName = settingsStore.geminiModel.firstOrNull()?.trim().orEmpty().ifEmpty { "gemini-3.8-flash" }
    val prompt = buildPrompt(video, normLang)

    try {
      val model = getModelForName(modelName)
      val response = withTimeout(60_000L) {
        model.generateContent(prompt)
      }

      val jsonString = response.text
      if (jsonString.isNullOrBlank()) {
        return@withContext AiResult.Error("Settings me model naam check karo (Empty response from model '$modelName')")
      }

      val analysis = parseJsonToAnalysis(jsonString)

      aiCacheDao.insertAiCache(
        AiCacheEntity(
          cacheKey = cacheKey,
          videoId = video.videoId,
          language = normLang,
          responseJson = jsonString,
          timestamp = System.currentTimeMillis()
        )
      )

      AiResult.Success(analysis, isCached = false)
    } catch (e: TimeoutCancellationException) {
      Log.e(TAG, "AI analysis timed out", e)
      AiResult.Error("AI analysis timed out after 60s. Dobara try karo.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Firebase AI generation failed for model=$modelName", e)
      val detail = e.localizedMessage ?: "Unknown error"
      AiResult.Error("Settings me model naam check karo ($detail)")
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

    val modelName = settingsStore.geminiModel.firstOrNull()?.trim().orEmpty()
      .ifEmpty { "gemini-3.8-flash" }
    val prompt = buildNichePrompt(seed, normLang)

    try {
      val model = getNicheModel(modelName)
      val response = withTimeout(30_000L) { model.generateContent(prompt) }
      val jsonString = response.text
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

  private fun buildPrompt(video: OutlierVideo, language: String): String {
    val descTrimmed = video.description.take(500).replace("\n", " ")
    val tagsStr = if (video.tags.isEmpty()) "none" else video.tags.joinToString(", ")
    val subsFormatted = OutlierCalculator.formatCompactNumber(video.subscriberCount)
    val viewsFormatted = OutlierCalculator.formatCompactNumber(video.views)
    val medianFormatted = OutlierCalculator.formatCompactNumber(video.channelMedianViews)
    val uploadDate = if (video.publishedAt.length >= 10) video.publishedAt.substring(0, 10) else video.publishedAt

    return """
You are a YouTube Shorts growth analyst. Analyze this outlier Short using ONLY the metadata below.
Do not claim to have watched it.
Title: ${video.title} | Description (first 500 chars): $descTrimmed | Tags: $tagsStr
Channel: ${video.channelTitle} (~$subsFormatted subs) | Views: $viewsFormatted | Channel median: $medianFormatted | Outlier: ${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x
Duration: ${video.durationSeconds}s | Uploaded: $uploadDate | Likes: ${video.likes} | Comments: ${video.comments}
Respond in $language (Hinglish = Hindi-English mix, Roman script). Return JSON:
{"whyViral":[3 points: hook, topic, emotion/curiosity],
 "hookIdeas":[3 punchy opening lines],
 "titleIdeas":[3 titles, max 60 chars],
 "contentIdea":"one similar Short idea with a 3-step outline"}
""".trimIndent()
  }

  private fun parseJsonToAnalysis(jsonString: String): AiAnalysis {
    val json = JSONObject(jsonString)

    val whyViralArray = json.optJSONArray("whyViral")
    val whyViral = mutableListOf<String>()
    if (whyViralArray != null) {
      for (i in 0 until whyViralArray.length()) {
        val item = whyViralArray.optString(i)
        if (item.isNotBlank()) whyViral.add(item)
      }
    }

    val hookIdeasArray = json.optJSONArray("hookIdeas")
    val hookIdeas = mutableListOf<String>()
    if (hookIdeasArray != null) {
      for (i in 0 until hookIdeasArray.length()) {
        val item = hookIdeasArray.optString(i)
        if (item.isNotBlank()) hookIdeas.add(item)
      }
    }

    val titleIdeasArray = json.optJSONArray("titleIdeas")
    val titleIdeas = mutableListOf<String>()
    if (titleIdeasArray != null) {
      for (i in 0 until titleIdeasArray.length()) {
        val item = titleIdeasArray.optString(i)
        if (item.isNotBlank()) titleIdeas.add(item)
      }
    }

    val contentIdea = json.optString("contentIdea", "")

    return AiAnalysis(
      whyViral = whyViral,
      hookIdeas = hookIdeas,
      titleIdeas = titleIdeas,
      contentIdea = contentIdea
    )
  }
}
