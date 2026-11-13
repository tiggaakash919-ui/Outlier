package com.outlierradar.app

import com.outlierradar.app.data.remote.CoarenaClient
import com.outlierradar.app.data.remote.CoarenaException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CoarenaClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: CoarenaClient

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = CoarenaClient(baseUrl = server.url("/api/v1/").toString())
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `generateJson sends bearer auth and json schema then returns content`() = runBlocking {
    val content = """{"title":"Chai hack","hook":"Yeh secret try karo"}"""
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
          JSONObject().apply {
            put("id", "chatcmpl-test")
            put(
              "choices",
              org.json.JSONArray().put(
                JSONObject().apply {
                  put("finish_reason", "stop")
                  put("message", JSONObject().put("role", "assistant").put("content", content))
                }
              )
            )
          }.toString()
        )
    )

    val schema = JSONObject().apply {
      put("type", "object")
      put("properties", JSONObject().put("title", JSONObject().put("type", "string")))
    }
    val result = client.generateJson(
      apiKey = "coa_test_key",
      model = "gemini-3-8-flash",
      prompt = "Give one idea",
      schema = schema
    )

    assertEquals(content, result)
    val request = server.takeRequest()
    assertEquals("Bearer coa_test_key", request.getHeader("Authorization"))
    val body = JSONObject(request.body.readUtf8())
    assertEquals("gemini-3-8-flash", body.getString("model"))
    assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
    assertEquals(
      "object",
      body.getJSONObject("response_format")
        .getJSONObject("json_schema")
        .getJSONObject("schema")
        .getString("type")
    )
  }

  @Test
  fun `generateJson attaches thumbnail as base64 image_url`() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody(
          """{"choices":[{"finish_reason":"stop","message":{"content":"{\"ok\":true}"}}]}"""
        )
    )

    client.generateJson(
      apiKey = "coa_test_key",
      model = "gemini-3-8-flash",
      prompt = "Analyze",
      schema = JSONObject().put("type", "object"),
      imagePng = byteArrayOf(1, 2, 3, 4)
    )

    val body = JSONObject(server.takeRequest().body.readUtf8())
    val userContent = body.getJSONArray("messages")
      .getJSONObject(1)
      .getJSONArray("content")
    assertEquals("text", userContent.getJSONObject(0).getString("type"))
    assertEquals("image_url", userContent.getJSONObject(1).getString("type"))
    assertTrue(
      userContent.getJSONObject(1)
        .getJSONObject("image_url")
        .getString("url")
        .startsWith("data:image/png;base64,")
    )
  }

  @Test
  fun `429 response is mapped to rate limit`() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(429)
        .setBody("""{"error":{"message":"Too many requests"}}""")
    )

    val error = runCatching {
      client.generateJson("coa_test_key", "gemini-3-8-flash", "hi", schema = JSONObject())
    }.exceptionOrNull()

    assertTrue(error is CoarenaException)
    assertEquals(429, (error as CoarenaException).statusCode)
    assertTrue(CoarenaClient.isRateLimit(error))
    assertFalse(CoarenaClient.isAuthError(error))
  }

  @Test
  fun `401 response is mapped to auth error`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Bad key"}}"""))

    val error = runCatching {
      client.generateJson("bad_key", "gemini-3-8-flash", "hi", schema = JSONObject())
    }.exceptionOrNull()

    assertTrue(error is CoarenaException)
    assertEquals(401, (error as CoarenaException).statusCode)
    assertTrue(CoarenaClient.isAuthError(error))
    assertFalse(CoarenaClient.isRateLimit(error))
  }

  @Test
  fun `blank api key fails before hitting network`() = runBlocking {
    val error = runCatching {
      client.generateJson("", "gemini-3-8-flash", "hi", schema = JSONObject())
    }.exceptionOrNull()

    assertTrue(error is CoarenaException)
    assertTrue(error!!.message!!.contains("key missing", ignoreCase = true))
    assertEquals(0, server.requestCount)
  }

  @Test
  fun `listModels parses ids`() = runBlocking {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setBody("""{"object":"list","data":[{"id":"model-a"},{"id":"model-b"}]}""")
    )

    val models = client.listModels("coa_test_key")

    assertEquals(listOf("model-a", "model-b"), models)
    assertEquals("/api/v1/models", server.takeRequest().path)
  }

  @Test
  fun `extractJson strips markdown fences and preamble`() {
    assertEquals(
      """{"a":1}""",
      CoarenaClient.extractJson("```json\n{\"a\":1}\n```")
    )
    assertEquals(
      """{"a":1}""",
      CoarenaClient.extractJson("Here is the JSON:\n{\"a\":1}")
    )
    assertEquals(
      """[{"a":1}]""",
      CoarenaClient.extractJson("```\n[{\"a\":1}]\n```")
    )
  }
}
