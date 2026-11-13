package com.outlierradar.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.outlierradar.app.BuildConfig
import com.outlierradar.app.data.local.AppDatabase
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.repository.AiRepository
import com.outlierradar.app.data.repository.AiResult
import com.outlierradar.app.data.repository.ApiKeyManager
import com.outlierradar.app.data.repository.KeyRepository
import com.outlierradar.app.data.repository.OutlierRepository
import com.outlierradar.app.data.repository.QuotaTracker
import com.outlierradar.app.data.repository.SavedRepository
import com.outlierradar.app.data.repository.SearchHistoryRepository
import com.outlierradar.app.data.repository.SearchResult
import com.outlierradar.app.data.repository.ContentGapsResult
import com.outlierradar.app.data.repository.ContentPackResult
import com.outlierradar.app.data.repository.TrendReportResult
import com.outlierradar.app.data.repository.VelocityRepository
import com.outlierradar.app.domain.AiUiState
import com.outlierradar.app.domain.ContentGapsUiState
import com.outlierradar.app.domain.ContentPackUiState
import com.outlierradar.app.domain.ApiKeyUi
import com.outlierradar.app.domain.ChannelInputParser
import com.outlierradar.app.domain.CostEstimate
import com.outlierradar.app.domain.DebugStats
import com.outlierradar.app.domain.DiscoveryHelpers
import com.outlierradar.app.domain.DiscoveryMode
import com.outlierradar.app.domain.EmptyStageReason
import com.outlierradar.app.domain.FilterOptions
import com.outlierradar.app.domain.NearMissVideo
import com.outlierradar.app.domain.OutlierCalculator
import com.outlierradar.app.domain.OutlierVideo
import com.outlierradar.app.domain.PresetType
import com.outlierradar.app.domain.Presets
import com.outlierradar.app.domain.QuotaStatus
import com.outlierradar.app.domain.SearchHistoryEntry
import com.outlierradar.app.domain.ShortStatus
import com.outlierradar.app.domain.TrendReportUiState
import com.outlierradar.app.domain.VelocityCalculator
import com.outlierradar.app.domain.VelocityState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

enum class Screen {
  HOME,
  RESULTS,
  SAVED,
  SETTINGS,
  DETAIL
}

class MainViewModel(
  private val keyRepository: KeyRepository,
  private val outlierRepository: OutlierRepository,
  private val savedRepository: SavedRepository,
  private val aiRepository: AiRepository,
  private val velocityRepository: VelocityRepository,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager,
  private val settingsStore: SettingsStore,
  private val database: AppDatabase,
  private val searchHistoryRepository: SearchHistoryRepository
) : ViewModel() {

  val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val quotaStatus: StateFlow<QuotaStatus> = quotaTracker
    .getQuotaStatusFlow(
      fingerprintsFlow = settingsStore.apiKeyFingerprints,
      activeIndexFlow = settingsStore.activeKeyIndex
    )
    .stateIn(viewModelScope, SharingStarted.Eagerly, QuotaStatus())

  private val _apiKeys = MutableStateFlow<List<ApiKeyUi>>(emptyList())
  val apiKeys: StateFlow<List<ApiKeyUi>> = _apiKeys.asStateFlow()

  /** Non-null while the "this will cost ~X units" dialog is waiting for an answer. */
  private val _costEstimate = MutableStateFlow<CostEstimate?>(null)
  val costEstimate: StateFlow<CostEstimate?> = _costEstimate.asStateFlow()

  private var costConfirmation: CompletableDeferred<Boolean>? = null

  val savedOutliers: StateFlow<List<OutlierVideo>> = savedRepository.savedOutliersFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val geminiModel: StateFlow<String> = settingsStore.geminiModel
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "gemini-3.8-flash")

  val aiProvider: StateFlow<String> = settingsStore.aiProvider
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "COARENA")

  val coarenaModel: StateFlow<String> = settingsStore.coarenaModel
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "gemini-3-8-flash")

  /** Masked effective Coarena key (Settings override first, then BuildConfig). */
  val coarenaKeyMasked: StateFlow<String?> = settingsStore.coarenaApiKey
    .map { stored ->
      val effective = stored.trim().ifBlank { BuildConfig.COARENA_API_KEY.trim() }
      when {
        effective.isBlank() -> null
        effective.length <= 12 -> "••••••"
        else -> effective.take(7) + "…" + effective.takeLast(4)
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val aiLanguage: StateFlow<String> = settingsStore.aiLanguage
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hinglish")

  val uiLanguage: StateFlow<String> = settingsStore.uiLanguage
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hinglish")

  val themeMode: StateFlow<String> = settingsStore.themeMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DARK")

  private val _maskedKey = MutableStateFlow<String?>(null)
  val maskedKey: StateFlow<String?> = _maskedKey.asStateFlow()

  private val _onboardingError = MutableStateFlow<String?>(null)
  val onboardingError: StateFlow<String?> = _onboardingError.asStateFlow()

  private val _currentScreen = MutableStateFlow(Screen.HOME)
  val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

  private val _filters = MutableStateFlow(FilterOptions())
  val filters: StateFlow<FilterOptions> = _filters.asStateFlow()

  private val _outliers = MutableStateFlow<List<OutlierVideo>>(emptyList())
  val outliers: StateFlow<List<OutlierVideo>> = _outliers.asStateFlow()

  private val _nearMisses = MutableStateFlow<List<NearMissVideo>>(emptyList())
  val nearMisses: StateFlow<List<NearMissVideo>> = _nearMisses.asStateFlow()

  private val _debugStats = MutableStateFlow(DebugStats())
  val debugStats: StateFlow<DebugStats> = _debugStats.asStateFlow()

  private val _nextPageToken = MutableStateFlow<String?>(null)
  val nextPageToken: StateFlow<String?> = _nextPageToken.asStateFlow()

  private val _isSearching = MutableStateFlow(false)
  val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

  val progressMessage: StateFlow<String?> = outlierRepository.progressFlow

  val searchHistory: StateFlow<List<SearchHistoryEntry>> = searchHistoryRepository.recentFlow(20)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** Kept for compatibility with older UI wiring; prefer [searchHistory]. */
  val recentSearches: StateFlow<List<String>> = searchHistory
    .map { entries -> entries.map { it.displayLabel } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** AUTO_EXPAND chips generated on Home (Gemini or offline fallback). */
  private val _expandedKeywords = MutableStateFlow<List<String>>(emptyList())
  val expandedKeywords: StateFlow<List<String>> = _expandedKeywords.asStateFlow()

  private val _isExpandingKeywords = MutableStateFlow(false)
  val isExpandingKeywords: StateFlow<Boolean> = _isExpandingKeywords.asStateFlow()

  private val _selectedDetailVideo = MutableStateFlow<OutlierVideo?>(null)
  val selectedDetailVideo: StateFlow<OutlierVideo?> = _selectedDetailVideo.asStateFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  val detailSnapshots = _selectedDetailVideo
    .flatMapLatest { video ->
      if (video == null) flowOf(emptyList())
      else velocityRepository.snapshotsFlow(video.videoId)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val detailTracked = _selectedDetailVideo
    .flatMapLatest { video ->
      if (video == null) flowOf(null)
      else velocityRepository.trackedVideoFlow(video.videoId)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val watchedChannels = velocityRepository.watchedChannelsFlow()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _aiUiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
  val aiUiState: StateFlow<AiUiState> = _aiUiState.asStateFlow()

  private val _contentPackUiState = MutableStateFlow<ContentPackUiState>(ContentPackUiState.Idle)
  val contentPackUiState: StateFlow<ContentPackUiState> = _contentPackUiState.asStateFlow()

  private val _contentGapsUiState = MutableStateFlow<ContentGapsUiState>(ContentGapsUiState.Idle)
  val contentGapsUiState: StateFlow<ContentGapsUiState> = _contentGapsUiState.asStateFlow()

  private val _trendReportUiState = MutableStateFlow<TrendReportUiState>(TrendReportUiState.Idle)
  val trendReportUiState: StateFlow<TrendReportUiState> = _trendReportUiState.asStateFlow()

  /** Real velocity labels for current result cards (videoId -> label). */
  private val _velocityLabels = MutableStateFlow<Map<String, String>>(emptyMap())
  val velocityLabels: StateFlow<Map<String, String>> = _velocityLabels.asStateFlow()

  private val lazyVerifyInFlight = ConcurrentHashMap.newKeySet<String>()
  private val lazyVerifySemaphore = Semaphore(4)

  private val _userMessages = MutableSharedFlow<String>()
  val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

  init {
    refreshMaskedKey()
    viewModelScope.launch {
      apiKeyManager.notices.collect { notice ->
        // Refresh first: emitting can suspend until the UI collector is ready.
        _apiKeys.value = keyRepository.getMaskedKeys()
        _maskedKey.value = keyRepository.getMaskedKey()
        _userMessages.emit(notice)
      }
    }
  }

  fun refreshMaskedKey() {
    viewModelScope.launch {
      _maskedKey.value = keyRepository.getMaskedKey()
      _apiKeys.value = keyRepository.getMaskedKeys()
    }
  }

  /** Called by Settings after adding/removing/switching a key. */
  fun refreshKeys() = refreshMaskedKey()

  /** Answer to the cost-confirmation dialog. */
  fun confirmCost(proceed: Boolean) {
    _costEstimate.value = null
    val deferred = costConfirmation
    costConfirmation = null
    deferred?.complete(proceed)
  }

  private suspend fun requestCostConfirmation(estimate: CostEstimate): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    costConfirmation = deferred
    _costEstimate.value = estimate
    return deferred.await()
  }

  fun clearOnboardingError() {
    _onboardingError.value = null
  }

  fun navigateTo(screen: Screen) {
    _currentScreen.value = screen
  }

  fun openVideoDetail(video: OutlierVideo) {
    _selectedDetailVideo.value = video
    _currentScreen.value = Screen.DETAIL
  }

  fun closeVideoDetail() {
    _selectedDetailVideo.value = null
  }

  fun updateFilters(newFilters: FilterOptions) {
    _filters.value = newFilters.withTrimmedKeyword()
  }

  fun startSearch(forceRefresh: Boolean = false) {
    if (_isSearching.value) return
    val trimmedFilters = _filters.value.withTrimmedKeyword()
    _filters.value = trimmedFilters

    val inputError = validationError(trimmedFilters)
    if (inputError != null) {
      viewModelScope.launch { _userMessages.emit(inputError) }
      return
    }

    viewModelScope.launch {
      runCatching { searchHistoryRepository.record(trimmedFilters) }
    }

    _isSearching.value = true
    _currentScreen.value = Screen.RESULTS

    viewModelScope.launch {
      when (
        val result = outlierRepository.searchOutliers(
          trimmedFilters,
          forceRefresh = forceRefresh,
          onConfirmCost = { requestCostConfirmation(it) }
        )
      ) {
        is SearchResult.Success -> {
          _outliers.value = result.outliers
          _nearMisses.value = result.nearMisses
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
          refreshVelocityLabels(result.outliers)
          _isSearching.value = false
        }
        is SearchResult.Error -> {
          _isSearching.value = false
          _userMessages.emit(result.message)
          if (result.isKeyInvalid) {
            _onboardingError.value = "Key kaam nahi kar rahi, nayi key daalo"
            refreshMaskedKey()
          }
        }
      }
    }
  }

  fun rerunSearchHistory(entry: SearchHistoryEntry) {
    if (_isSearching.value) return
    _filters.value = entry.filters.withTrimmedKeyword()
    startSearch(forceRefresh = false)
  }

  private fun refreshVelocityLabels(videos: List<OutlierVideo>) {
    if (videos.isEmpty()) {
      _velocityLabels.value = emptyMap()
      return
    }
    viewModelScope.launch {
      val labels = mutableMapOf<String, String>()
      for (v in videos.distinctBy { it.videoId }) {
        val snaps = runCatching { velocityRepository.getSnapshots(v.videoId) }.getOrDefault(emptyList())
        labels[v.videoId] = if (snaps.size >= 2) {
          VelocityCalculator.compute(snaps).label
        } else {
          OutlierCalculator.fallbackVelocityLabel(v)
        }
      }
      _velocityLabels.value = labels
    }
  }

  /**
   * Trigger on-scroll lazy verification for UNVERIFIED candidates (req 8).
   * Safe/idempotent and bounded by an in-flight set + semaphore.
   */
  fun requestLazyVerification(videoId: String) {
    if (videoId.isBlank() || !lazyVerifyInFlight.add(videoId)) return
    viewModelScope.launch {
      try {
        lazyVerifySemaphore.withPermit {
          val current = _outliers.value.firstOrNull { it.videoId == videoId } ?: return@withPermit
          if (current.shortStatus != ShortStatus.UNVERIFIED) return@withPermit
          val status = outlierRepository.verifyShortOnDemand(videoId)
          if (status == ShortStatus.UNVERIFIED) return@withPermit
          _outliers.value = _outliers.value.map {
            if (it.videoId == videoId) it.copy(shortStatus = status) else it
          }
          val selected = _selectedDetailVideo.value
          if (selected?.videoId == videoId) {
            _selectedDetailVideo.value = selected.copy(shortStatus = status)
          }
        }
      } finally {
        // Allow re-check later only if still unresolved; keep resolved IDs out.
        val resolved = _outliers.value.firstOrNull { it.videoId == videoId }?.shortStatus
        if (resolved == ShortStatus.UNVERIFIED || resolved == null) {
          lazyVerifyInFlight.remove(videoId)
        }
      }
    }
  }

  /** Returns a user-facing message when the current mode input is incomplete. */
  private fun validationError(filters: FilterOptions): String? = when (filters.mode) {
    DiscoveryMode.KEYWORD ->
      if (filters.keyword.isBlank()) "Pehle keyword daalo." else null
    DiscoveryMode.MULTI_KEYWORD ->
      if (DiscoveryHelpers.parseKeywordList(filters.multiKeywords).isEmpty()) {
        "Multi-keyword mode me comma se alag karke 1-5 keywords daalo."
      } else null
    DiscoveryMode.AUTO_EXPAND ->
      if (filters.keyword.isBlank()) "Auto-expand ke liye seed keyword daalo." else null
    DiscoveryMode.CHANNEL_SCAN ->
      if (ChannelInputParser.parse(filters.channelInput) == null) {
        "Channel URL, @handle ya UC id daalo."
      } else null
    DiscoveryMode.SIMILAR_TO_OUTLIER ->
      if (filters.similarSeedVideoId.isBlank() && filters.similarSeedTitle.isBlank()) {
        "Similar search ke liye kisi result card par 🔍 dabao."
      } else null
  }

  fun findSimilar(video: OutlierVideo) {
    if (_isSearching.value) return
    _filters.value = _filters.value.copy(
      mode = DiscoveryMode.SIMILAR_TO_OUTLIER,
      keyword = "",
      multiKeywords = "",
      channelInput = "",
      similarSeedVideoId = video.videoId,
      similarSeedTitle = video.title,
      similarSeedTags = video.tags
    )
    startSearch()
  }

  fun exitSimilarMode() {
    _filters.value = _filters.value.copy(
      mode = DiscoveryMode.KEYWORD,
      similarSeedVideoId = "",
      similarSeedTitle = "",
      similarSeedTags = emptyList()
    )
  }

  fun expandKeywordsNow() {
    val seed = _filters.value.keyword.trim()
    if (seed.isBlank() || _isExpandingKeywords.value) return
    _isExpandingKeywords.value = true
    viewModelScope.launch {
      val expansion = aiRepository.expandKeywords(seed, aiLanguage.value)
      _expandedKeywords.value = expansion.keywords
      _isExpandingKeywords.value = false
      if (!expansion.fromAi) {
        _userMessages.emit("Gemini se keywords nahi bane, offline niche ideas use ho rahe hain.")
      }
    }
  }

  fun clearExpandedKeywords() {
    _expandedKeywords.value = emptyList()
  }

  fun loadMore() {
    if (_isSearching.value) return
    // Merged modes and channel scan do not expose a single page cursor.
    if (_filters.value.mode != DiscoveryMode.KEYWORD) return
    val token = _nextPageToken.value ?: return
    val trimmedFilters = _filters.value.withTrimmedKeyword()
    _filters.value = trimmedFilters
    _isSearching.value = true

    viewModelScope.launch {
      when (
        val result = outlierRepository.searchOutliers(
          trimmedFilters,
          pageToken = token,
          onConfirmCost = { requestCostConfirmation(it) }
        )
      ) {
        is SearchResult.Success -> {
          // Merge and deduplicate by videoId
          val existingIds = _outliers.value.map { it.videoId }.toSet()
          val newItems = result.outliers.filter { !existingIds.contains(it.videoId) }
          val merged = _outliers.value + newItems
          _outliers.value = merged
          _nearMisses.value = result.nearMisses
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
          refreshVelocityLabels(merged)
          _isSearching.value = false
        }
        is SearchResult.Error -> {
          _isSearching.value = false
          _userMessages.emit(result.message)
          if (result.isKeyInvalid) {
            _onboardingError.value = "Key kaam nahi kar rahi, nayi key daalo"
            refreshMaskedKey()
          }
        }
      }
    }
  }

  fun tryMore(variantOrSlice: String) {
    if (_isSearching.value) return
    val isSlice = variantOrSlice in listOf("7d", "30d", "90d", "6mo")
    val updatedFilters = if (isSlice) {
      _filters.value.copy(recency = variantOrSlice).withTrimmedKeyword()
    } else {
      _filters.value.copy(
        mode = DiscoveryMode.KEYWORD,
        keyword = variantOrSlice.trim(),
        multiKeywords = "",
        similarSeedVideoId = "",
        similarSeedTitle = "",
        similarSeedTags = emptyList()
      )
    }
    _filters.value = updatedFilters
    viewModelScope.launch { runCatching { searchHistoryRepository.record(updatedFilters) } }

    _isSearching.value = true
    viewModelScope.launch {
      when (
        val result = outlierRepository.searchOutliers(
          updatedFilters,
          forceRefresh = false,
          onConfirmCost = { requestCostConfirmation(it) }
        )
      ) {
        is SearchResult.Success -> {
          // Merge the new results and deduplicate by videoId (Section 4)
          val existingMap = _outliers.value.associateBy { it.videoId }.toMutableMap()
          result.outliers.forEach { newVid ->
            existingMap[newVid.videoId] = newVid
          }
          val merged = existingMap.values.toList()
          _outliers.value = merged
          _nearMisses.value = result.nearMisses
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
          refreshVelocityLabels(merged)
          _isSearching.value = false
        }
        is SearchResult.Error -> {
          _isSearching.value = false
          _userMessages.emit(result.message)
        }
      }
    }
  }

  fun loosenFilters() {
    val cur = _filters.value
    val loose = Presets.applyPreset(PresetType.LOOSE, cur)
    val isAlreadyLoose = cur.maxSubs >= loose.maxSubs &&
      cur.maxMedianViews >= loose.maxMedianViews &&
      cur.minViralViews <= loose.minViralViews &&
      cur.minMultiplier <= loose.minMultiplier &&
      cur.regionCode.equals("Any", ignoreCase = true)

    _filters.value = if (isAlreadyLoose && _outliers.value.isEmpty()) {
      // Progressive relaxation when Loose preset still yields 0 outliers on broad niches like Gaming/Minecraft
      loose.copy(
        maxSubs = 500_000L,
        maxMedianViews = 100_000L,
        minViralViews = 50_000L,
        minMultiplier = 5,
        recency = "7d"
      )
    } else {
      loose
    }
    startSearch(forceRefresh = false)
  }

  fun applyEmptyStageFix(stage: EmptyStageReason) {
    val stats = _debugStats.value
    var updated = OutlierCalculator.applyEmptyStageFix(_filters.value, stage)
    // If median stage failed and almost all channels were already filtered out by subs (or had <5 shorts),
    // also widen subscriber range & region so the retry checks more channels instead of getting stuck.
    if (stage == EmptyStageReason.MEDIAN_STAGE && (stats.afterSubsFilter <= 3 || stats.channelsMedianTooHigh == 0)) {
      updated = updated.copy(
        maxSubs = maxOf(updated.maxSubs, 100_000L),
        minViralViews = minOf(updated.minViralViews, 100_000L),
        minMultiplier = minOf(updated.minMultiplier, 20),
        recency = "30d",
        regionCode = "Any"
      )
    }
    _filters.value = updated
    startSearch(forceRefresh = false)
  }

  fun toggleSave(video: OutlierVideo) {
    viewModelScope.launch {
      val isNowSaved = savedRepository.toggleSave(video)
      _outliers.value = _outliers.value.map {
        if (it.videoId == video.videoId) it.copy(isSaved = isNowSaved) else it
      }
      val selected = _selectedDetailVideo.value
      if (selected?.videoId == video.videoId) {
        _selectedDetailVideo.value = selected.copy(isSaved = isNowSaved)
      }
      _userMessages.emit(if (isNowSaved) "✓ Video bookmarked in Saved" else "Removed from Saved")
    }
  }

  fun toggleWatchlist(video: OutlierVideo) {
    viewModelScope.launch {
      val nowWatchlisted = savedRepository.toggleWatchlist(video)
      _userMessages.emit(
        if (nowWatchlisted) "✓ Watchlist me add ho gaya (velocity tracking on)"
        else "Watchlist se hata diya gaya"
      )
    }
  }

  fun followWatchedChannel(
    channelId: String,
    channelTitle: String,
    uploadsPlaylistId: String? = null,
    subscriberCount: Long = 0L,
    baselineMedian: Long = 0L
  ) {
    viewModelScope.launch {
      velocityRepository.followChannel(
        channelId = channelId,
        channelTitle = channelTitle,
        uploadsPlaylistId = uploadsPlaylistId,
        subscriberCount = subscriberCount,
        hasHiddenSubscribers = false,
        baselineMedian = baselineMedian
      )
      velocityRepository.ensureVelocityWorkScheduled()
      _userMessages.emit("✓ Channel watchlist me add ho gaya")
    }
  }

  fun unfollowWatchedChannel(channelId: String) {
    viewModelScope.launch {
      velocityRepository.unfollowChannel(channelId)
      _userMessages.emit("Channel watchlist se hata diya gaya")
    }
  }

  fun removeSaved(videoId: String) {
    viewModelScope.launch {
      savedRepository.remove(videoId)
      _outliers.value = _outliers.value.map {
        if (it.videoId == videoId) it.copy(isSaved = false) else it
      }
    }
  }

  fun updateSavedFolder(videoId: String, folder: String) {
    viewModelScope.launch { savedRepository.updateFolder(videoId, folder) }
  }

  fun updateSavedTags(videoId: String, tags: List<String>) {
    viewModelScope.launch { savedRepository.updateSavedTags(videoId, tags) }
  }

  fun analyzeWithAi(video: OutlierVideo, forceRefresh: Boolean = false) {
    _aiUiState.value = AiUiState.Loading
    viewModelScope.launch {
      val lang = aiLanguage.value
      when (val res = aiRepository.analyzeOutlier(video, lang, forceRefresh = forceRefresh)) {
        is AiResult.Success -> {
          _aiUiState.value = AiUiState.Success(res.analysis, isCached = res.isCached)
        }
        is AiResult.Error -> {
          _aiUiState.value = AiUiState.Error(res.message)
        }
      }
    }
  }

  fun resetAiState() {
    _aiUiState.value = AiUiState.Idle
  }

  fun generateTrendReport(videos: List<OutlierVideo>, forceRefresh: Boolean = false) {
    if (videos.size < 5 || videos.size > 20) {
      _trendReportUiState.value = TrendReportUiState.Error("Trend report ke liye 5 se 20 outliers select karo.")
      return
    }
    _trendReportUiState.value = TrendReportUiState.Loading
    viewModelScope.launch {
      when (val res = aiRepository.generateTrendReport(videos, aiLanguage.value, forceRefresh)) {
        is TrendReportResult.Success ->
          _trendReportUiState.value = TrendReportUiState.Success(res.report, res.isCached)
        is TrendReportResult.Error ->
          _trendReportUiState.value = TrendReportUiState.Error(res.message)
      }
    }
  }

  fun resetTrendReportState() {
    _trendReportUiState.value = TrendReportUiState.Idle
  }

  fun generateContentPack(video: OutlierVideo, forceRefresh: Boolean = false) {
    _contentPackUiState.value = ContentPackUiState.Loading
    viewModelScope.launch {
      when (val res = aiRepository.generateContentPack(video, aiLanguage.value, forceRefresh)) {
        is ContentPackResult.Success ->
          _contentPackUiState.value = ContentPackUiState.Success(res.pack, res.isCached)
        is ContentPackResult.Error ->
          _contentPackUiState.value = ContentPackUiState.Error(res.message)
      }
    }
  }

  fun resetContentPackState() {
    _contentPackUiState.value = ContentPackUiState.Idle
  }

  fun generateContentGaps(keyword: String, forceRefresh: Boolean = false) {
    _contentGapsUiState.value = ContentGapsUiState.Loading
    viewModelScope.launch {
      when (val res = aiRepository.generateContentGaps(keyword, aiLanguage.value, forceRefresh)) {
        is ContentGapsResult.Success ->
          _contentGapsUiState.value = ContentGapsUiState.Success(res.report, res.isCached)
        is ContentGapsResult.Error ->
          _contentGapsUiState.value = ContentGapsUiState.Error(res.message)
      }
    }
  }

  fun resetContentGapsState() {
    _contentGapsUiState.value = ContentGapsUiState.Idle
  }

  fun setGeminiModel(model: String) {
    viewModelScope.launch {
      settingsStore.setGeminiModel(model)
    }
  }

  fun setAiProvider(provider: String) {
    viewModelScope.launch {
      settingsStore.setAiProvider(provider)
    }
  }

  fun setCoarenaModel(model: String) {
    viewModelScope.launch {
      settingsStore.setCoarenaModel(model)
    }
  }

  fun setCoarenaApiKey(key: String) {
    viewModelScope.launch {
      settingsStore.setCoarenaApiKey(key)
    }
  }

  /** Runs a live Coarena /models check; null means success. */
  suspend fun testCoarenaConnection(key: String?): String? = aiRepository.testCoarenaConnection(key)

  fun setAiLanguage(lang: String) {
    viewModelScope.launch {
      settingsStore.setAiLanguage(lang)
    }
  }

  fun setUiLanguage(lang: String) {
    viewModelScope.launch {
      settingsStore.setUiLanguage(lang)
    }
  }

  fun setThemeMode(mode: String) {
    viewModelScope.launch {
      settingsStore.setThemeMode(mode)
    }
  }

  fun clearCache() {
    viewModelScope.launch { clearCacheInternal() }
  }

  private suspend fun clearCacheInternal() {
    database.searchCacheDao().clearSearchCache()
    database.channelCacheDao().clearChannelCache()
    database.videoStatsDao().clearVideoStats()
    database.shortVerificationDao().clearShortVerificationCache()
    database.aiCacheDao().clearAiCache()
    database.nicheCacheDao().clearNicheCache()
  }

  fun clearAllData() {
    viewModelScope.launch {
      clearCacheInternal()
      runCatching { database.searchHistoryDao().clearAll() }
      savedRepository.clearAll()
      velocityRepository.clearVelocityData()
      database.quotaDao().clearQuota()
      settingsStore.clearAll()
      refreshMaskedKey()
    }
  }

  class Factory(
    private val keyRepository: KeyRepository,
    private val outlierRepository: OutlierRepository,
    private val savedRepository: SavedRepository,
    private val aiRepository: AiRepository,
    private val velocityRepository: VelocityRepository,
    private val quotaTracker: QuotaTracker,
    private val apiKeyManager: ApiKeyManager,
    private val settingsStore: SettingsStore,
    private val database: AppDatabase,
    private val searchHistoryRepository: SearchHistoryRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return MainViewModel(
        keyRepository = keyRepository,
        outlierRepository = outlierRepository,
        savedRepository = savedRepository,
        aiRepository = aiRepository,
        velocityRepository = velocityRepository,
        quotaTracker = quotaTracker,
        apiKeyManager = apiKeyManager,
        settingsStore = settingsStore,
        database = database,
        searchHistoryRepository = searchHistoryRepository
      ) as T
    }
  }
}
