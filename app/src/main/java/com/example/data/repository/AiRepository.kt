package com.example.data.repository

import android.util.Log
import com.example.data.local.AiCacheEntity
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsStore
import com.example.domain.AiAnalysis
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
import org.json.JSONObject
import java.util.Locale

private const val TAG = "AiRepository"

sealed interface AiResult {
  data class Success(val analysis: AiAnalysis, val isCached: Boolean) : AiResult
  data class Error(val message: String) : AiResult
}

class AiRepository(
  private val database: AppDatabase,
  private val settingsStore: SettingsStore
) {
  private val aiCacheDao = database.aiCacheDao()

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
Channel: ${video.channelTitle} (~$subsFormatted subs) | Views: $viewsFormatted | Channel median: $medianFormatted | Outlier: ${video.outlierMultiplier}x
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
