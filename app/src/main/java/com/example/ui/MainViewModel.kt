package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsStore
import com.example.data.repository.KeyRepository
import com.example.data.repository.OutlierRepository
import com.example.data.repository.QuotaTracker
import com.example.data.repository.SavedRepository
import com.example.data.repository.SearchResult
import com.example.domain.DebugStats
import com.example.domain.FilterOptions
import com.example.domain.OutlierVideo
import com.example.domain.PresetType
import com.example.domain.Presets
import com.example.domain.QuotaStatus
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
  private val quotaTracker: QuotaTracker,
  private val settingsStore: SettingsStore,
  private val database: AppDatabase
) : ViewModel() {

  val hasApiKey: StateFlow<Boolean> = settingsStore.hasApiKey
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val quotaStatus: StateFlow<QuotaStatus> = quotaTracker.getQuotaStatusFlow()
    .stateIn(viewModelScope, SharingStarted.Eagerly, QuotaStatus())

  val savedOutliers: StateFlow<List<OutlierVideo>> = savedRepository.savedOutliersFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val geminiModel: StateFlow<String> = settingsStore.geminiModel
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "gemini-2.5-flash")

  val aiLanguage: StateFlow<String> = settingsStore.aiLanguage
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hinglish")

  val uiLanguage: StateFlow<String> = settingsStore.uiLanguage
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Hinglish")

  val themeMode: StateFlow<String> = settingsStore.themeMode
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DARK")

  private val _maskedKey = MutableStateFlow<String?>(null)
  val maskedKey: StateFlow<String?> = _maskedKey.asStateFlow()

  private val _currentScreen = MutableStateFlow(Screen.HOME)
  val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

  private val _filters = MutableStateFlow(FilterOptions())
  val filters: StateFlow<FilterOptions> = _filters.asStateFlow()

  private val _outliers = MutableStateFlow<List<OutlierVideo>>(emptyList())
  val outliers: StateFlow<List<OutlierVideo>> = _outliers.asStateFlow()

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

  private val _userMessages = MutableSharedFlow<String>()
  val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

  init {
    refreshMaskedKey()
  }

  fun refreshMaskedKey() {
    viewModelScope.launch {
      _maskedKey.value = keyRepository.getMaskedKey()
    }
  }

  fun navigateTo(screen: Screen) {
    _currentScreen.value = screen
  }

  fun updateFilters(newFilters: FilterOptions) {
    _filters.value = newFilters
  }

  fun startSearch(forceRefresh: Boolean = false) {
    val kw = _filters.value.keyword.trim()
    if (kw.isBlank()) return

    // Add to recent searches
    if (!_recentSearches.value.contains(kw)) {
      _recentSearches.value = listOf(kw) + _recentSearches.value.take(5)
    }

    _isSearching.value = true
    _currentScreen.value = Screen.RESULTS

    viewModelScope.launch {
      when (val result = outlierRepository.searchOutliers(_filters.value, forceRefresh = forceRefresh)) {
        is SearchResult.Success -> {
          _outliers.value = result.outliers
          _debugStats.value = result.debugStats
          _nextPageToken.value = result.nextPageToken
          _isSearching.value = false
        }
        is SearchResult.Error -> {
          _isSearching.value = false
          _userMessages.emit(result.message)
          if (result.isKeyInvalid) {
            refreshMaskedKey()
          }
        }
      }
    }
  }

  fun loadMore() {
    val token = _nextPageToken.value ?: return
    _isSearching.value = true

    viewModelScope.launch {
      when (val result = outlierRepository.searchOutliers(_filters.value, pageToken = token)) {
        is SearchResult.Success -> {
          // Merge and deduplicate by videoId
          val existingIds = _outliers.value.map { it.videoId }.toSet()
          val newItems = result.outliers.filter { !existingIds.contains(it.videoId) }
          _outliers.value = _outliers.value + newItems
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

  fun tryMore(variantOrSlice: String) {
    // Check if it's a recency slice
    val isSlice = variantOrSlice in listOf("7d", "30d", "90d", "6mo")
    val updatedFilters = if (isSlice) {
      _filters.value.copy(recency = variantOrSlice)
    } else {
      _filters.value.copy(keyword = variantOrSlice)
    }
    _filters.value = updatedFilters
    startSearch(forceRefresh = true)
  }

  fun loosenFilters() {
    _filters.value = Presets.applyPreset(PresetType.LOOSE, _filters.value.keyword)
    startSearch(forceRefresh = true)
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
    viewModelScope.launch {
      database.searchCacheDao().clearSearchCache()
      database.channelCacheDao().clearChannelCache()
      database.shortVerificationDao().clearShortVerificationCache()
    }
  }

  fun clearAllData() {
    viewModelScope.launch {
      clearCache()
      savedRepository.clearAll()
      settingsStore.clearAll()
      refreshMaskedKey()
    }
  }

  class Factory(
    private val keyRepository: KeyRepository,
    private val outlierRepository: OutlierRepository,
    private val savedRepository: SavedRepository,
    private val quotaTracker: QuotaTracker,
    private val settingsStore: SettingsStore,
    private val database: AppDatabase
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return MainViewModel(
        keyRepository,
        outlierRepository,
        savedRepository,
        quotaTracker,
        settingsStore,
        database
      ) as T
    }
  }
}
