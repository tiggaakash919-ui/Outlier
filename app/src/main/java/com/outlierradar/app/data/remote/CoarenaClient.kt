package com.outlierradar.app.data.remote

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Raised for any non-2xx or malformed Coarena Model API response. */
class CoarenaException(
  message: String,
  val statusCode: Int = 0,
  val requestId: String? = null
) : Exception(message)

/**
 * Minimal OpenAI-compatible client for the Coarena Model API.
 *
 * Endpoint: POST https://coarena.ai/api/v1/chat/completions
 * Auth:     Authorization: Bearer <key>
 *
 * The API key is never logged or embedded in thrown messages. Callers are still
 * expected to redact it from user-facing error strings.
 */
class CoarenaClient(
  baseUrl: String = DEFAULT_BASE_URL,
  private val client: OkHttpClient = defaultClient()
) {
  private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

  suspend fun generateJson(
    apiKey: String,
    model: String,
    prompt: String,
    systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    schema: JSONObject? = null,
    imagePng: ByteArray? = null,
    maxTokens: Int = 8192,
    temperature: Double = 0.7
  ): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) {
      throw CoarenaException("Coarena API key missing. Settings me key add karo.")
    }

    val userContent: Any = if (imagePng != null && imagePng.isNotEmpty()) {
      val encoded = Base64.encodeToString(imagePng, Base64.NO_WRAP)
      JSONArray().apply {
        put(JSONObject().apply {
          put("type", "text")
          put("text", prompt)
        })
        put(JSONObject().apply {
          put("type", "image_url")
          put("image_url", JSONObject().put("url", "data:image/png;base64,$encoded"))
        })
      }
    } else {
      prompt
    }

    val messages = JSONArray().apply {
      put(JSONObject().apply {
        put("role", "system")
        put("content", systemPrompt)
      })
      put(JSONObject().apply {
        put("role", "user")
        put("content", userContent)
      })
    }

    val payload = JSONObject().apply {
      put("model", model.trim().ifEmpty { DEFAULT_MODEL })
      put("messages", messages)
      put("max_tokens", maxTokens)
      put("temperature", temperature)
      if (schema != null) {
        put(
          "response_format",
          JSONObject().apply {
            put("type", "json_schema")
            put(
              "json_schema",
              JSONObject().apply {
                put("name", "outlier_response")
                put("strict", true)
                put("schema", schema)
              }
            )
          }
        )
      } else {
        put("response_format", JSONObject().put("type", "json_object"))
      }
    }

    val request = Request.Builder()
      .url(base + "chat/completions")
      .header("Authorization", "Bearer $apiKey")
      .header("Content-Type", "application/json")
      .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
      .build()

    client.newCall(request).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        val detail = runCatching {
          JSONObject(bodyText).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        throw CoarenaException(
          "Coarena HTTP ${response.code}${if (detail.isNotBlank()) ": $detail" else ""}",
          statusCode = response.code,
          requestId = response.header("x-coarena-request-id")
        )
      }
      if (bodyText.isBlank()) {
        throw CoarenaException("Coarena se khaali response aaya.")
      }
      val json = runCatching { JSONObject(bodyText) }.getOrElse {
        throw CoarenaException("Coarena response JSON nahi hai.")
      }
      val choice = json.optJSONArray("choices")?.optJSONObject(0)
        ?: throw CoarenaException("Coarena response me choices nahi mili.")
      val finishReason = choice.optString("finish_reason")
      val content = choice.optJSONObject("message")?.optString("content").orEmpty()
      if (content.isBlank()) {
        throw CoarenaException(
          "Coarena response content khaali hai (finish=$finishReason).",
          requestId = json.optString("id")
        )
      }
      extractJson(content)
    }
  }

  /** Lists model ids available to [apiKey]. Used by the Settings "Test" action. */
  suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) {
      throw CoarenaException("Coarena API key missing. Settings me key add karo.")
    }
    val request = Request.Builder()
      .url(base + "models")
      .header("Authorization", "Bearer $apiKey")
      .get()
      .build()
    client.newCall(request).execute().use { response ->
      val bodyText = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw CoarenaException("Coarena HTTP ${response.code}", statusCode = response.code)
      }
      val data = runCatching { JSONObject(bodyText).optJSONArray("data") }.getOrNull()
        ?: return@use emptyList()
      buildList {
        for (i in 0 until data.length()) {
          val id = data.optJSONObject(i)?.optString("id").orEmpty()
          if (id.isNotBlank()) add(id)
        }
      }
    }
  }

  companion object {
    const val DEFAULT_BASE_URL = "https://coarena.ai/api/v1/"
    const val DEFAULT_MODEL = "gemini-3-8-flash"

    private const val DEFAULT_SYSTEM_PROMPT =
      "You are an expert YouTube Shorts growth strategist. Return only valid JSON that matches the requested schema. Never wrap JSON in markdown fences."

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(150, TimeUnit.SECONDS)
      .build()

    /**
     * Some models still wrap JSON in ```json fences or add a short preamble.
     * Keep only the JSON payload so existing org.json parsers stay happy.
     */
    fun extractJson(raw: String): String {
      var text = raw.trim()
      if (text.startsWith("```")) {
        text = text.removePrefix("```")
        text = text.removePrefix("json").removePrefix("JSON")
        text = text.trim()
        val fence = text.lastIndexOf("```")
        if (fence >= 0) text = text.substring(0, fence).trim()
      }
      val firstObj = text.indexOf('{')
      val firstArr = text.indexOf('[')
      val start = when {
        firstObj < 0 -> firstArr
        firstArr < 0 -> firstObj
        else -> minOf(firstObj, firstArr)
      }
      if (start > 0) text = text.substring(start)
      return text.trim()
    }

    /** True when the thrown error is a rate-limit / transient overload response. */
    fun isRateLimit(t: Throwable): Boolean {
      val c = t as? CoarenaException
      if (c != null) return c.statusCode == 429 || c.statusCode == 503
      val msg = (t.message ?: "").lowercase()
      return msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")
    }

    /** True when the key is missing/invalid or lacks access. */
    fun isAuthError(t: Throwable): Boolean {
      val c = t as? CoarenaException
      if (c != null) return c.statusCode == 401 || c.statusCode == 403
      val msg = (t.message ?: "").lowercase()
      return msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")
    }
  }
}
