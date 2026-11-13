package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsStore
import com.example.data.repository.AiRepository
import com.example.data.repository.AiResult
import com.example.data.repository.ApiKeyManager
import com.example.data.repository.KeyRepository
import com.example.data.repository.OutlierRepository
import com.example.data.repository.QuotaTracker
import com.example.data.repository.SavedRepository
import com.example.data.repository.SearchResult
import com.example.domain.AiUiState
import com.example.domain.ApiKeyUi
import com.example.domain.ChannelInputParser
import com.example.domain.CostEstimate
import com.example.domain.DebugStats
import com.example.domain.DiscoveryHelpers
import com.example.domain.DiscoveryMode
import com.example.domain.EmptyStageReason
import com.example.domain.FilterOptions
import com.example.domain.NearMissVideo
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.PresetType
import com.example.domain.Presets
import com.example.domain.QuotaStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen {
  HOME,
  RESULTS,
  SAVED,
  SETTINGS
}

class MainViewModel(
  private val keyRepository: KeyRepository,
  private val outlierRepository: OutlierRepository,
  private val savedRepository: SavedRepository,
  private val aiRepository: AiRepository,
  private val quotaTracker: QuotaTracker,
  private val apiKeyManager: ApiKeyManager,
  private val settingsStore: SettingsStore,
  private val database: AppDatabase
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

  private val _recentSearches = MutableStateFlow<List<String>>(
    listOf("motivation", "cooking", "tech facts", "crypto", "comedy")
  )
  val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

  /** AUTO_EXPAND chips generated on Home (Gemini or offline fallback). */
  private val _expandedKeywords = MutableStateFlow<List<String>>(emptyList())
  val expandedKeywords: StateFlow<List<String>> = _expandedKeywords.asStateFlow()

  private val _isExpandingKeywords = MutableStateFlow(false)
  val isExpandingKeywords: StateFlow<Boolean> = _isExpandingKeywords.asStateFlow()

  private val _aiUiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
  val aiUiState: StateFlow<AiUiState> = _aiUiState.asStateFlow()

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

    val recentLabel = when (trimmedFilters.mode) {
      DiscoveryMode.MULTI_KEYWORD ->
        DiscoveryHelpers.parseKeywordList(trimmedFilters.multiKeywords).firstOrNull()
      DiscoveryMode.CHANNEL_SCAN, DiscoveryMode.SIMILAR_TO_OUTLIER -> null
      else -> trimmedFilters.keyword.ifBlank { null }
    }
    if (recentLabel != null && !_recentSearches.value.contains(recentLabel)) {
      _recentSearches.value = listOf(recentLabel) + _recentSearches.value.take(5)
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
          _outliers.value = _outliers.value + newItems
          _nearMisses.value = result.nearMisses
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
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
          _outliers.value = existingMap.values.toList()
          _nearMisses.value = result.nearMisses
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
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
      _userMessages.emit(if (isNowSaved) "✓ Video bookmarked in Saved" else "Removed from Saved")
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

  fun setGeminiModel(model: String) {
    viewModelScope.launch {
      settingsStore.setGeminiModel(model)
    }
  }

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
      savedRepository.clearAll()
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
    private val quotaTracker: QuotaTracker,
    private val apiKeyManager: ApiKeyManager,
    private val settingsStore: SettingsStore,
    private val database: AppDatabase
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return MainViewModel(
        keyRepository,
        outlierRepository,
        savedRepository,
        aiRepository,
        quotaTracker,
        apiKeyManager,
        settingsStore,
        database
      ) as T
    }
  }
}
