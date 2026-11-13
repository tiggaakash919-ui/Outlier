package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.CostEstimate
import com.example.domain.OutlierVideo
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.example.ui.components.AiBottomSheet
import com.example.ui.detail.DetailScreen
import com.example.ui.home.HomeScreen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.results.ResultsScreen
import com.example.ui.saved.SavedScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.LocalAppStrings
import com.example.ui.theme.OutlierRadarTheme
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.rememberAppStrings
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

fun configureAppCheck(context: Context, intent: Intent?) {
  try {
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(context)
    }

    if (BuildConfig.DEBUG) {
      val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

      if (!debugToken.isNullOrEmpty()) {
        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
          "$DEBUG_STORE_PREFIX$persistenceKey",
          Context.MODE_PRIVATE
        )
        // Synchronous commit() is required before DebugAppCheckProviderFactory reads it
        prefs.edit()
          .putString(DEBUG_SECRET_KEY, debugToken)
          .commit()
        Log.d(TAG, "App Check debug secret configured successfully.")
      } else {
        Log.w(TAG, "No debug token found in Intent extra.")
      }

      FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
      )
      Log.d(TAG, "DebugAppCheckProviderFactory installed.")
    } else {
      val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
      if (resId != 0) {
        val siteKey = context.getString(resId)
        if (siteKey.isNotBlank()) {
          FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
          )
          Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
        } else {
          Log.e(TAG, "recaptcha_site_key resource string is blank.")
        }
      } else {
        Log.e(TAG, "recaptcha_site_key string resource not found in package.")
      }
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to initialize Firebase App Check", e)
  }
}

class MainActivity : ComponentActivity() {

  private val appContainer: AppContainer
    get() = (application as OutlierRadarApp).container

  private val viewModel: MainViewModel by viewModels {
    MainViewModel.Factory(
      keyRepository = appContainer.keyRepository,
      outlierRepository = appContainer.outlierRepository,
      savedRepository = appContainer.savedRepository,
      aiRepository = appContainer.aiRepository,
      velocityRepository = appContainer.velocityRepository,
      quotaTracker = appContainer.quotaTracker,
      apiKeyManager = appContainer.apiKeyManager,
      settingsStore = appContainer.settingsStore,
      database = appContainer.database,
      searchHistoryRepository = appContainer.searchHistoryRepository
    )
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // STEP 1: Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
    configureAppCheck(this, intent)

    enableEdgeToEdge()

    // STEP 2: Initialize Compose UI
    setContent {
      val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
      val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
      val uiLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
      val onboardingError by viewModel.onboardingError.collectAsStateWithLifecycle()

      val systemDark = isSystemInDarkTheme()
      val isDark = when (themeMode.uppercase()) {
        "LIGHT" -> false
        "SYSTEM" -> systemDark
        else -> true // Dark default
      }

      val appStrings = rememberAppStrings(uiLanguage)

      CompositionLocalProvider(LocalAppStrings provides appStrings) {
        OutlierRadarTheme(darkTheme = isDark) {
          val context = LocalContext.current

          LaunchedEffect(Unit) {
            viewModel.userMessages.collect { msg ->
              Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
          }

          if (!hasApiKey) {
            OnboardingScreen(
              keyRepository = appContainer.keyRepository,
              initialErrorMessage = onboardingError,
              onConnected = {
                viewModel.clearOnboardingError()
                viewModel.refreshMaskedKey()
              }
            )
          } else {
            MainAppContent(
              viewModel = viewModel,
              keyRepository = appContainer.keyRepository
            )
          }
        }
      }
    }
  }
}

const val ROUTE_HOME = "home"
const val ROUTE_RESULTS = "results"
const val ROUTE_SAVED = "saved"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_DETAIL = "detail"

@Composable
fun MainAppContent(
  viewModel: MainViewModel,
  keyRepository: com.example.data.repository.KeyRepository
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_HOME

  val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
  val filters by viewModel.filters.collectAsStateWithLifecycle()
  val quotaStatus by viewModel.quotaStatus.collectAsStateWithLifecycle()
  val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
  val progressMessage by viewModel.progressMessage.collectAsStateWithLifecycle()
  val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
  val searchHistory by viewModel.searchHistory.collectAsStateWithLifecycle()
  val expandedKeywords by viewModel.expandedKeywords.collectAsStateWithLifecycle()
  val isExpandingKeywords by viewModel.isExpandingKeywords.collectAsStateWithLifecycle()
  val outliers by viewModel.outliers.collectAsStateWithLifecycle()
  val velocityLabels by viewModel.velocityLabels.collectAsStateWithLifecycle()
  val nearMisses by viewModel.nearMisses.collectAsStateWithLifecycle()
  val debugStats by viewModel.debugStats.collectAsStateWithLifecycle()
  val nextPageToken by viewModel.nextPageToken.collectAsStateWithLifecycle()
  val savedOutliers by viewModel.savedOutliers.collectAsStateWithLifecycle()
  val maskedKey by viewModel.maskedKey.collectAsStateWithLifecycle()
  val apiKeys by viewModel.apiKeys.collectAsStateWithLifecycle()
  val costEstimate by viewModel.costEstimate.collectAsStateWithLifecycle()
  val geminiModel by viewModel.geminiModel.collectAsStateWithLifecycle()
  val aiLanguage by viewModel.aiLanguage.collectAsStateWithLifecycle()
  val uiLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
  val aiUiState by viewModel.aiUiState.collectAsStateWithLifecycle()
  val trendReportUiState by viewModel.trendReportUiState.collectAsStateWithLifecycle()
  val selectedDetailVideo by viewModel.selectedDetailVideo.collectAsStateWithLifecycle()
  val detailSnapshots by viewModel.detailSnapshots.collectAsStateWithLifecycle()
  val detailTracked by viewModel.detailTracked.collectAsStateWithLifecycle()
  val watchedChannels by viewModel.watchedChannels.collectAsStateWithLifecycle()

  var selectedAiVideo by remember { mutableStateOf<OutlierVideo?>(null) }
  var detailReturnScreen by remember { mutableStateOf(Screen.RESULTS) }

  // Sync ViewModel screen state changes (e.g. when search starts -> RESULTS) with NavController
  LaunchedEffect(currentScreen) {
    val targetRoute = when (currentScreen) {
      Screen.HOME -> ROUTE_HOME
      Screen.RESULTS -> ROUTE_RESULTS
      Screen.SAVED -> ROUTE_SAVED
      Screen.SETTINGS -> ROUTE_SETTINGS
      Screen.DETAIL -> ROUTE_DETAIL
    }
    if (currentRoute != targetRoute) {
      navController.navigate(targetRoute) {
        if (targetRoute == ROUTE_DETAIL) {
          launchSingleTop = true
          restoreState = false
        } else {
          popUpTo(navController.graph.findStartDestination().id) {
            saveState = true
          }
          launchSingleTop = true
          restoreState = true
        }
      }
    }
  }

  BackHandler(enabled = currentRoute != ROUTE_HOME) {
    if (currentRoute == ROUTE_DETAIL) {
      viewModel.closeVideoDetail()
      viewModel.navigateTo(detailReturnScreen)
    } else {
      viewModel.navigateTo(Screen.HOME)
    }
  }

  fun openAiSheetFor(video: OutlierVideo) {
    selectedAiVideo = video
    viewModel.analyzeWithAi(video, forceRefresh = false)
  }

  Scaffold(
    bottomBar = {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("bottom_nav_bar")
      ) {
        NavigationBarItem(
          icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
          label = { Text("Home", fontSize = 11.sp) },
          selected = currentRoute == ROUTE_HOME,
          onClick = { viewModel.navigateTo(Screen.HOME) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryViolet,
            indicatorColor = PrimaryViolet.copy(alpha = 0.2f)
          ),
          modifier = Modifier.testTag("nav_home")
        )

        NavigationBarItem(
          icon = { Icon(Icons.Default.TrendingUp, contentDescription = "Results") },
          label = { Text("Results", fontSize = 11.sp) },
          selected = currentRoute == ROUTE_RESULTS,
          onClick = { viewModel.navigateTo(Screen.RESULTS) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryViolet,
            indicatorColor = PrimaryViolet.copy(alpha = 0.2f)
          ),
          modifier = Modifier.testTag("nav_results")
        )

        NavigationBarItem(
          icon = { Icon(Icons.Default.Bookmark, contentDescription = "Saved") },
          label = { Text("Saved", fontSize = 11.sp) },
          selected = currentRoute == ROUTE_SAVED,
          onClick = { viewModel.navigateTo(Screen.SAVED) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryViolet,
            indicatorColor = PrimaryViolet.copy(alpha = 0.2f)
          ),
          modifier = Modifier.testTag("nav_saved")
        )

        NavigationBarItem(
          icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
          label = { Text("Settings", fontSize = 11.sp) },
          selected = currentRoute == ROUTE_SETTINGS,
          onClick = { viewModel.navigateTo(Screen.SETTINGS) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryViolet,
            indicatorColor = PrimaryViolet.copy(alpha = 0.2f)
          ),
          modifier = Modifier.testTag("nav_settings")
        )
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
      ) {
        composable(ROUTE_HOME) {
          HomeScreen(
            filters = filters,
            quotaStatus = quotaStatus,
            isSearching = isSearching,
            progressMessage = progressMessage,
            recentSearches = recentSearches,
            searchHistory = searchHistory,
            expandedKeywords = expandedKeywords,
            isExpandingKeywords = isExpandingKeywords,
            themeMode = themeMode,
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onExpandKeywords = { viewModel.expandKeywordsNow() },
            onFiltersChange = { viewModel.updateFilters(it) },
            onSearchClick = { viewModel.startSearch(forceRefresh = false) },
            onHistoryRerun = { viewModel.rerunSearchHistory(it) }
          )
        }
        composable(ROUTE_RESULTS) {
          ResultsScreen(
            outliers = outliers,
            nearMisses = nearMisses,
            keyword = when (filters.mode) {
              com.example.domain.DiscoveryMode.MULTI_KEYWORD -> filters.multiKeywords
              com.example.domain.DiscoveryMode.CHANNEL_SCAN -> filters.channelInput
              com.example.domain.DiscoveryMode.SIMILAR_TO_OUTLIER -> filters.similarSeedTitle
              else -> filters.keyword
            },
            debugStats = debugStats,
            isLoading = isSearching,
            progressMessage = progressMessage,
            nextPageToken = nextPageToken,
            quotaStatus = quotaStatus,
            velocityLabels = velocityLabels,
            themeMode = themeMode,
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onRefresh = { viewModel.startSearch(forceRefresh = true) },
            onLoadMore = { viewModel.loadMore() },
            onTryMore = { viewModel.tryMore(it) },
            onLoosenFilters = { viewModel.loosenFilters() },
            onApplyEmptyStageFix = { viewModel.applyEmptyStageFix(it) },
            onSaveToggle = { viewModel.toggleSave(it) },
            onAiClick = { openAiSheetFor(it) },
            mode = filters.mode,
            similarSeedTitle = filters.similarSeedTitle,
            onFindSimilar = { viewModel.findSimilar(it) },
            onCardClick = {
              detailReturnScreen = Screen.RESULTS
              viewModel.openVideoDetail(it)
            },
            onVerifyShort = { viewModel.requestLazyVerification(it) },
            trendReportUiState = trendReportUiState,
            onGenerateTrendReport = { selected -> viewModel.generateTrendReport(selected) },
            onResetTrendReport = { viewModel.resetTrendReportState() }
          )
        }
        composable(ROUTE_SAVED) {
          SavedScreen(
            savedList = savedOutliers,
            themeMode = themeMode,
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onRemove = { viewModel.removeSaved(it) },
            onUpdateFolder = { id, folder -> viewModel.updateSavedFolder(id, folder) },
            onUpdateTags = { id, tags -> viewModel.updateSavedTags(id, tags) },
            onAiClick = { openAiSheetFor(it) },
            onCardClick = {
              detailReturnScreen = Screen.SAVED
              viewModel.openVideoDetail(it)
            }
          )
        }
        composable(ROUTE_DETAIL) {
          DetailScreen(
            video = selectedDetailVideo,
            snapshots = detailSnapshots,
            tracked = detailTracked,
            watchedChannels = watchedChannels,
            aiUiState = aiUiState,
            aiLanguage = aiLanguage,
            onBack = {
              viewModel.closeVideoDetail()
              viewModel.navigateTo(detailReturnScreen)
            },
            onSaveToggle = { viewModel.toggleSave(it) },
            onWatchlistToggle = { viewModel.toggleWatchlist(it) },
            onFollowChannel = { video ->
              viewModel.followWatchedChannel(
                channelId = video.channelId,
                channelTitle = video.channelTitle,
                uploadsPlaylistId = null,
                subscriberCount = video.subscriberCount,
                baselineMedian = video.channelMedianViews
              )
            },
            onUnfollowChannel = { viewModel.unfollowWatchedChannel(it) },
            onFindSimilar = { viewModel.findSimilar(it) },
            onAnalyzeAi = { video, force -> viewModel.analyzeWithAi(video, force) }
          )
        }
        composable(ROUTE_SETTINGS) {
          SettingsScreen(
            maskedKey = maskedKey,
            apiKeys = apiKeys,
            quotaStatus = quotaStatus,
            geminiModel = geminiModel,
            aiLanguage = aiLanguage,
            uiLanguage = uiLanguage,
            themeMode = themeMode,
            keyRepository = keyRepository,
            onModelChange = { viewModel.setGeminiModel(it) },
            onAiLanguageChange = { viewModel.setAiLanguage(it) },
            onUiLanguageChange = { viewModel.setUiLanguage(it) },
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onClearCache = { viewModel.clearCache() },
            onClearAllData = { viewModel.clearAllData() },
            onKeysChanged = { viewModel.refreshKeys() }
          )
        }
      }
    }

    // Gemini AI Breakdown ModalBottomSheet (Phase 2)
    val activeVideo = selectedAiVideo
    if (activeVideo != null) {
      AiBottomSheet(
        video = activeVideo,
        aiUiState = aiUiState,
        aiLanguage = aiLanguage,
        onRegenerate = { viewModel.analyzeWithAi(activeVideo, forceRefresh = true) },
        onDismiss = {
          selectedAiVideo = null
          viewModel.resetAiState()
        }
      )
    }

    // Estimated-cost confirmation before the per-channel stage.
    val estimate = costEstimate
    if (estimate != null) {
      CostConfirmationDialog(
        estimate = estimate,
        onDecision = { viewModel.confirmCost(it) }
      )
    }
  }
}

@Composable
private fun CostConfirmationDialog(
  estimate: CostEstimate,
  onDecision: (Boolean) -> Unit
) {
  AlertDialog(
    onDismissRequest = { onDecision(false) },
    title = { Text("Zyada API units lag sakte hain") },
    text = {
      Text(
        "Is search me ~${estimate.estimatedUnits} units lag sakte hain " +
          "(${estimate.uncachedChannels} naye channels). " +
          "Bache hue main units: ${estimate.remainingUnits}.\n\nAage badhein?"
      )
    },
    confirmButton = {
      Button(onClick = { onDecision(true) }, modifier = Modifier.testTag("cost_confirm_button")) {
        Text("Haan, continue")
      }
    },
    dismissButton = {
      TextButton(
        onClick = { onDecision(false) },
        modifier = Modifier.testTag("cost_cancel_button")
      ) {
        Text("Nahi, cached results")
      }
    },
    modifier = Modifier.testTag("cost_confirm_dialog")
  )
}
