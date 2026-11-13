package com.outlierradar.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.KeyVault
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.remote.ShortsVerifier
import com.outlierradar.app.data.remote.YouTubeApi
import com.outlierradar.app.data.repository.AiRepository
import com.outlierradar.app.data.repository.ApiKeyManager
import com.outlierradar.app.data.repository.OutlierRepository
import com.outlierradar.app.data.repository.QuotaTracker
import com.outlierradar.app.data.repository.SearchResult
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.ShortStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutlierRepositoryMockWebServerTest {

  private lateinit var server: MockWebServer
  private lateinit var db: AppDatabase
  private lateinit var context: Context

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    runBlocking {
      try { db.close() } catch (_: Exception) {}
    }
    server.shutdown()
  }

  private fun buildRepository(
    dispatcher: Dispatcher? = null,
    shortsVerifier: ShortsVerifier = AlwaysShortVerifier()
  ): OutlierRepository {
    if (dispatcher != null) server.dispatcher = dispatcher
    val client = OkHttpClient.Builder()
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(2, TimeUnit.SECONDS)
      .build()
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/youtube/v3/"))
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
    val api = retrofit.create(YouTubeApi::class.java)

    val keyVault = object : KeyVault() {
      override fun useAndroidKeystore(): Boolean = false
      override fun encrypt(plainText: String): String = "plain:$plainText"
      override fun decrypt(cipherTextBase64: String): String =
        cipherTextBase64.removePrefix("plain:")
    }
    val settingsStore = SettingsStore(context, keyVault)
    val quotaTracker = QuotaTracker(db)
    val apiKeyManager = ApiKeyManager(settingsStore, quotaTracker)
    val aiRepository = AiRepository(db, settingsStore)
    return OutlierRepository(
      youTubeApi = api,
      database = db,
      settingsStore = settingsStore,
      quotaTracker = quotaTracker,
      apiKeyManager = apiKeyManager,
      shortsVerifier = shortsVerifier,
      aiRepository = aiRepository
    )
  }

  private suspend fun OutlierRepository.withTestKey(): OutlierRepository {
    // seed key via settings store used inside repo components
    return this
  }

  private fun seedApiKey(repoDeps: RepoDeps) = runBlocking {
    repoDeps.settingsStore.addApiKey("TEST_API_KEY_12345")
    repoDeps.apiKeyManager.ensureMigrated()
  }

  private data class RepoDeps(
    val repo: OutlierRepository,
    val settingsStore: SettingsStore,
    val apiKeyManager: ApiKeyManager,
    val db: AppDatabase
  )

  private fun buildDeps(
    dispatcher: Dispatcher,
    shortsVerifier: ShortsVerifier = AlwaysShortVerifier()
  ): RepoDeps {
    server.dispatcher = dispatcher
    val client = OkHttpClient.Builder()
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(2, TimeUnit.SECONDS)
      .build()
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/youtube/v3/"))
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
    val api = retrofit.create(YouTubeApi::class.java)

    val keyVault = object : KeyVault() {
      override fun useAndroidKeystore(): Boolean = false
      override fun encrypt(plainText: String): String = "plain:$plainText"
      override fun decrypt(cipherTextBase64: String): String = cipherTextBase64.removePrefix("plain:")
    }
    val settingsStore = SettingsStore(context, keyVault)
    val quotaTracker = QuotaTracker(db)
    val apiKeyManager = ApiKeyManager(settingsStore, quotaTracker)
    val aiRepository = AiRepository(db, settingsStore)
    val repo = OutlierRepository(
      youTubeApi = api,
      database = db,
      settingsStore = settingsStore,
      quotaTracker = quotaTracker,
      apiKeyManager = apiKeyManager,
      shortsVerifier = shortsVerifier,
      aiRepository = aiRepository
    )
    return RepoDeps(repo, settingsStore, apiKeyManager, db)
  }

  private fun defaultFilters() = FilterOptions(
    keyword = "test niche",
    minSubs = 0L,
    maxSubs = 100_000L,
    minViralViews = 10_000L,
    maxMedianViews = 50_000L,
    minMultiplier = 5,
    samplePerChannel = 10,
    minBaselineAgeHours = 48,
    recency = "30d",
    regionCode = "Any",
    language = "Any",
    pagesPerSearch = 1
  )

  private fun published(daysAgo: Long): String =
    Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString()

  private fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code)
      .setHeader("Content-Type", "application/json")
      .setBody(body)

  @Test
  fun emptySearch_returnsSuccessWithNoOutliers() = runBlocking {
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.contains("/search") -> jsonResponse("""{"items":[],"nextPageToken":null}""")
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val result = deps.repo.searchOutliers(defaultFilters())
    assertTrue(result is SearchResult.Success)
    val success = result as SearchResult.Success
    assertTrue(success.outliers.isEmpty())
    assertTrue(success.nearMisses.isEmpty())
    assertEquals(0, success.debugStats.searchReturned)
    assertEquals(0, success.debugStats.finalOutliers)
  }

  @Test
  fun hiddenSubs_channelExcludedWhenNotFullRange() = runBlocking {
    val vId = "vid_hidden_1"
    val chId = "UC_HIDDEN_CH"
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.contains("/search") -> jsonResponse(
            """{"items":[{"id":{"videoId":"$vId"}}],"nextPageToken":null}"""
          )
          path.contains("/videos") -> jsonResponse(
            """
            {"items":[{"id":"$vId","snippet":{"title":"Hidden sub short","description":"","channelId":"$chId","channelTitle":"HiddenCh","publishedAt":"${published(5)}","tags":[],"thumbnails":{"high":{"url":"https://i.ytimg.com/vi/$vId/hq.jpg"}}},"statistics":{"viewCount":"50000","likeCount":"500","commentCount":"20"},"contentDetails":{"duration":"PT30S"}}]}
            """.trimIndent()
          )
          path.contains("/channels") -> jsonResponse(
            """{"items":[{"id":"$chId","snippet":{"title":"HiddenCh"},"statistics":{"subscriberCount":"5000","hiddenSubscriberCount":true},"contentDetails":{"relatedPlaylists":{"uploads":"UU_HIDDEN"}}}]}"""
          )
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val filters = defaultFilters().copy(minSubs = 1_000L, maxSubs = 10_000L)
    val result = deps.repo.searchOutliers(filters)
    assertTrue(result is SearchResult.Success)
    val success = result as SearchResult.Success
    assertTrue("hidden-sub channel should not produce outliers", success.outliers.isEmpty())
    // no median/baseline units should be spent for excluded channel
    assertEquals(0, success.debugStats.uushCount + success.debugStats.uploadsFallbackCount)
  }

  @Test
  fun uush404_fallsBackToUploadsAndBuildsBaseline() = runBlocking {
    val viralId = "vid_viral_1"
    val base1 = "vid_base_1"
    val base2 = "vid_base_2"
    val base3 = "vid_base_3"
    val base4 = "vid_base_4"
    val base5 = "vid_base_5"
    val chId = "UC_FALLBACK_CH"
    val uploadsId = "UU_FALLBACK_CH"
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val url = request.requestUrl?.toString().orEmpty()
        return when {
          path.contains("/search") -> jsonResponse(
            """{"items":[{"id":{"videoId":"$viralId"}}],"nextPageToken":null}"""
          )
          path.contains("/videos") -> {
            val ids = request.requestUrl?.queryParameter("id").orEmpty()
            val items = ids.split(",").filter { it.isNotBlank() }.joinToString(",") { id ->
              val views = if (id == viralId) "500000" else "2000"
              val pub = if (id == viralId) published(3) else published(10)
              """{"id":"$id","snippet":{"title":"t $id","description":"","channelId":"$chId","channelTitle":"FallbackCh","publishedAt":"$pub","tags":[],"thumbnails":{"high":{"url":"https://i.ytimg.com/vi/$id/hq.jpg"}}},"statistics":{"viewCount":"$views","likeCount":"100","commentCount":"10"},"contentDetails":{"duration":"PT25S"}}"""
            }
            jsonResponse("""{"items":[$items]}""")
          }
          path.contains("/channels") -> jsonResponse(
            """{"items":[{"id":"$chId","snippet":{"title":"FallbackCh"},"statistics":{"subscriberCount":"2000","hiddenSubscriberCount":false},"contentDetails":{"relatedPlaylists":{"uploads":"$uploadsId"}}}]}"""
          )
          path.contains("/playlistItems") -> {
            val pl = request.requestUrl?.queryParameter("playlistId").orEmpty()
            if (pl.startsWith("UUSH")) {
              // UUSH not found -> 404
              jsonResponse("""{"error":{"code":404,"message":"not found","errors":[{"reason":"playlistNotFound"}]}}""", 404)
            } else {
              // uploads fallback returns mature baseline shorts
              val baselineIds = listOf(base1, base2, base3, base4, base5)
              val items = baselineIds.joinToString(",") { id ->
                """{"snippet":{"publishedAt":"${published(10)}"},"contentDetails":{"videoId":"$id","videoPublishedAt":"${published(10)}"}}"""
              }
              jsonResponse("""{"items":[$items]}""")
            }
          }
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val filters = defaultFilters().copy(minMultiplier = 10, maxMedianViews = 50_000L)
    val result = deps.repo.searchOutliers(filters)
    assertTrue(result is SearchResult.Success)
    val success = result as SearchResult.Success
    assertEquals("uploads fallback should be used", 1, success.debugStats.uploadsFallbackCount)
    assertEquals(0, success.debugStats.uushCount)
    assertTrue(success.debugStats.channelsPassingMedian >= 1)
  }

  @Test
  fun quotaExceeded_returnsQuotaError() = runBlocking {
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.contains("/search") -> jsonResponse(
            """{"error":{"code":403,"message":"quotaExceeded","errors":[{"reason":"quotaExceeded","domain":"youtube.quota"}]}}""",
            403
          )
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val result = deps.repo.searchOutliers(defaultFilters())
    assertTrue(result is SearchResult.Error)
    val err = result as SearchResult.Error
    assertTrue(err.isQuotaExceeded)
  }

  @Test
  fun keyInvalid_returnsKeyInvalidError() = runBlocking {
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.contains("/search") -> jsonResponse(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","errors":[{"reason":"keyInvalid"}]}}""",
            400
          )
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val result = deps.repo.searchOutliers(defaultFilters())
    assertTrue(result is SearchResult.Error)
    val err = result as SearchResult.Error
    assertTrue(err.isKeyInvalid)
  }

  @Test
  fun cacheHit_returnsCachedResultWithoutNetworkSearch() = runBlocking {
    val vId = "vid_cache_1"
    val base1 = "vid_cbase_1"; val base2 = "vid_cbase_2"; val base3 = "vid_cbase_3"
    val base4 = "vid_cbase_4"; val base5 = "vid_cbase_5"
    val chId = "UC_CACHE_CH"
    val dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
          path.contains("/search") -> jsonResponse(
            """{"items":[{"id":{"videoId":"$vId"}}],"nextPageToken":null}"""
          )
          path.contains("/videos") -> {
            val ids = request.requestUrl?.queryParameter("id").orEmpty()
            val items = ids.split(",").filter { it.isNotBlank() }.joinToString(",") { id ->
              val views = if (id == vId) "300000" else "1500"
              val pub = if (id == vId) published(4) else published(12)
              """{"id":"$id","snippet":{"title":"t $id","description":"","channelId":"$chId","channelTitle":"CacheCh","publishedAt":"$pub","tags":[],"thumbnails":{"high":{"url":"https://i.ytimg.com/vi/$id/hq.jpg"}}},"statistics":{"viewCount":"$views","likeCount":"80","commentCount":"5"},"contentDetails":{"duration":"PT20S"}}"""
            }
            jsonResponse("""{"items":[$items]}""")
          }
          path.contains("/channels") -> jsonResponse(
            """{"items":[{"id":"$chId","snippet":{"title":"CacheCh"},"statistics":{"subscriberCount":"3000","hiddenSubscriberCount":false},"contentDetails":{"relatedPlaylists":{"uploads":"UU_CACHE"}}}]}"""
          )
          path.contains("/playlistItems") -> {
            val baselineIds = listOf(base1, base2, base3, base4, base5)
            val items = baselineIds.joinToString(",") { id ->
              """{"snippet":{"publishedAt":"${published(12)}"},"contentDetails":{"videoId":"$id","videoPublishedAt":"${published(12)}"}}"""
            }
            jsonResponse("""{"items":[$items]}""")
          }
          else -> jsonResponse("""{"items":[]}""")
        }
      }
    }
    val deps = buildDeps(dispatcher)
    seedApiKey(deps)
    val filters = defaultFilters().copy(minMultiplier = 10)

    val first = deps.repo.searchOutliers(filters)
    assertTrue(first is SearchResult.Success)
    val requestsAfterFirst = server.requestCount

    val second = deps.repo.searchOutliers(filters, forceRefresh = false)
    assertTrue(second is SearchResult.Success)
    val s2 = second as SearchResult.Success
    assertTrue("second call should be cache hit", s2.isCacheHit)
    assertEquals("no extra network requests on cache hit", requestsAfterFirst, server.requestCount)
  }

  private class AlwaysShortVerifier : ShortsVerifier() {
    override suspend fun verifyShort(videoId: String): ShortStatus = ShortStatus.CONFIRMED_SHORT
  }
}
