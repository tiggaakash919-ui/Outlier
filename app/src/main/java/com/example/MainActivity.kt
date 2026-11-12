package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.OutlierVideo
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.example.ui.home.HomeScreen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.results.ResultsScreen
import com.example.ui.saved.SavedScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.OutlierRadarTheme
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan

class MainActivity : ComponentActivity() {

  private val appContainer by lazy { AppContainer(applicationContext) }

  private val viewModel: MainViewModel by viewModels {
    MainViewModel.Factory(
      keyRepository = appContainer.keyRepository,
      outlierRepository = appContainer.outlierRepository,
      savedRepository = appContainer.savedRepository,
      quotaTracker = appContainer.quotaTracker,
      settingsStore = appContainer.settingsStore,
      database = appContainer.database
    )
  }

  @OptIn(ExperimentalMaterial3Api::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
      val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
      val isDark = themeMode == "DARK"

      OutlierRadarTheme(darkTheme = isDark) {
        val context = LocalContext.current

        // Listen for user messages / alerts
        LaunchedEffect(Unit) {
          viewModel.userMessages.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
          }
        }

        if (!hasApiKey) {
          // Onboarding / Key Input screen
          OnboardingScreen(
            keyRepository = appContainer.keyRepository,
            onConnected = {
              viewModel.refreshMaskedKey()
            }
          )
        } else {
          MainAppContent(viewModel = viewModel)
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: MainViewModel) {
  val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
  val filters by viewModel.filters.collectAsStateWithLifecycle()
  val quotaStatus by viewModel.quotaStatus.collectAsStateWithLifecycle()
  val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
  val progressMessage by viewModel.progressMessage.collectAsStateWithLifecycle()
  val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
  val outliers by viewModel.outliers.collectAsStateWithLifecycle()
  val debugStats by viewModel.debugStats.collectAsStateWithLifecycle()
  val nextPageToken by viewModel.nextPageToken.collectAsStateWithLifecycle()
  val savedOutliers by viewModel.savedOutliers.collectAsStateWithLifecycle()
  val maskedKey by viewModel.maskedKey.collectAsStateWithLifecycle()
  val geminiModel by viewModel.geminiModel.collectAsStateWithLifecycle()
  val aiLanguage by viewModel.aiLanguage.collectAsStateWithLifecycle()
  val uiLanguage by viewModel.uiLanguage.collectAsStateWithLifecycle()
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

  var selectedAiVideo by remember { mutableStateOf<OutlierVideo?>(null) }
  val sheetState = rememberModalBottomSheetState()

  // Handle Android system back press
  BackHandler(enabled = currentScreen != Screen.HOME) {
    viewModel.navigateTo(Screen.HOME)
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
          selected = currentScreen == Screen.HOME,
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
          selected = currentScreen == Screen.RESULTS,
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
          selected = currentScreen == Screen.SAVED,
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
          selected = currentScreen == Screen.SETTINGS,
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
      when (currentScreen) {
        Screen.HOME -> {
          HomeScreen(
            filters = filters,
            quotaStatus = quotaStatus,
            isSearching = isSearching,
            progressMessage = progressMessage,
            recentSearches = recentSearches,
            onFiltersChange = { viewModel.updateFilters(it) },
            onSearchClick = { viewModel.startSearch(forceRefresh = true) }
          )
        }
        Screen.RESULTS -> {
          ResultsScreen(
            outliers = outliers,
            keyword = filters.keyword,
            debugStats = debugStats,
            isLoading = isSearching,
            progressMessage = progressMessage,
            nextPageToken = nextPageToken,
            onLoadMore = { viewModel.loadMore() },
            onTryMore = { viewModel.tryMore(it) },
            onLoosenFilters = { viewModel.loosenFilters() },
            onSaveToggle = { viewModel.toggleSave(it) },
            onAiClick = { selectedAiVideo = it }
          )
        }
        Screen.SAVED -> {
          SavedScreen(
            savedList = savedOutliers,
            onRemove = { viewModel.removeSaved(it) },
            onAiClick = { selectedAiVideo = it }
          )
        }
        Screen.SETTINGS -> {
          val appContainer = (LocalContext.current.applicationContext as? ComponentActivity)
          SettingsScreen(
            maskedKey = maskedKey,
            quotaStatus = quotaStatus,
            geminiModel = geminiModel,
            aiLanguage = aiLanguage,
            uiLanguage = uiLanguage,
            themeMode = themeMode,
            keyRepository = MainViewModelHolder.getKeyRepo(LocalContext.current),
            onModelChange = { viewModel.setGeminiModel(it) },
            onAiLanguageChange = { viewModel.setAiLanguage(it) },
            onUiLanguageChange = { viewModel.setUiLanguage(it) },
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onClearCache = { viewModel.clearCache() },
            onClearAllData = { viewModel.clearAllData() },
            onKeyRemoved = { viewModel.refreshMaskedKey() }
          )
        }
      }
    }

    // Modal Bottom Sheet placeholder for Phase 2 AI breakdown
    if (selectedAiVideo != null) {
      val video = selectedAiVideo!!
      ModalBottomSheet(
        onDismissRequest = { selectedAiVideo = null },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.AutoAwesome,
              contentDescription = null,
              tint = PrimaryViolet,
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Gemini AI Analysis",
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = video.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryCyan
          )

          Spacer(modifier = Modifier.height(14.dp))

          Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Text(
                text = "✨ Phase 2 Ready:",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Phase 2 will integrate Firebase AI Logic to break down why this Short blew up (${video.outlierMultiplier}x outlier with ${video.views} views vs ${video.channelMedianViews} median), generating viral hook lines, title ideas, and customized 3-step script outlines in Hinglish/Hindi/English.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
              )
            }
          }

          Spacer(modifier = Modifier.height(20.dp))

          Button(
            onClick = { selectedAiVideo = null },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Done")
          }

          Spacer(modifier = Modifier.height(24.dp))
        }
      }
    }
  }
}

object MainViewModelHolder {
  private var container: AppContainer? = null

  fun getKeyRepo(context: android.content.Context): com.example.data.repository.KeyRepository {
    if (container == null) {
      container = AppContainer(context.applicationContext)
    }
    return container!!.keyRepository
  }
}
