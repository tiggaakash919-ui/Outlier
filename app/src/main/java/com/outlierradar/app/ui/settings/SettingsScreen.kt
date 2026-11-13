package com.outlierradar.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.outlierradar.app.data.local.SettingsStore
import com.outlierradar.app.data.repository.KeyRepository
import com.outlierradar.app.data.repository.KeyValidationResult
import com.outlierradar.app.domain.ApiKeyUi
import com.outlierradar.app.domain.QuotaStatus
import com.outlierradar.app.ui.theme.AccentRed
import com.outlierradar.app.ui.theme.BadgeGreen
import com.outlierradar.app.ui.theme.LocalAppStrings
import com.outlierradar.app.ui.theme.PrimaryViolet
import com.outlierradar.app.ui.theme.SecondaryCyan
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
  maskedKey: String?,
  apiKeys: List<ApiKeyUi> = emptyList(),
  quotaStatus: QuotaStatus,
  geminiModel: String,
  aiLanguage: String,
  uiLanguage: String,
  themeMode: String,
  keyRepository: KeyRepository,
  onModelChange: (String) -> Unit,
  onAiLanguageChange: (String) -> Unit,
  onUiLanguageChange: (String) -> Unit,
  onThemeModeChange: (String) -> Unit,
  onClearCache: () -> Unit,
  onClearAllData: () -> Unit,
  onKeysChanged: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appStrings = LocalAppStrings.current
  val coroutineScope = rememberCoroutineScope()

  var isTestingKey by remember { mutableStateOf(false) }
  var keyStatusText by remember { mutableStateOf("✓ Connected") }
  var isKeyValid by remember { mutableStateOf(true) }

  var keyDialogMode by remember { mutableStateOf<KeyDialogMode?>(null) }
  var removeKeyIndex by remember { mutableStateOf<Int?>(null) }
  var showClearDataDialog by remember { mutableStateOf(false) }

  var newKeyInput by remember { mutableStateOf("") }
  var newKeyError by remember { mutableStateOf<String?>(null) }
  var isSavingNewKey by remember { mutableStateOf(false) }

  val activeKeyIndex = apiKeys.firstOrNull { it.isActive }?.index ?: 0

  var modelInput by remember { mutableStateOf(geminiModel) }
  LaunchedEffect(geminiModel) {
    if (modelInput != geminiModel) {
      modelInput = geminiModel
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = appStrings.settingsTitle,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground
    )

    // SECTION 1: YouTube API Key
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth().testTag("settings_api_key_card")
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Key, contentDescription = null, tint = PrimaryViolet)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            "YouTube API Key",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Masked Key display
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            text = maskedKey ?: "No key saved",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
          )
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = if (isKeyValid) Icons.Default.CheckCircle else Icons.Default.Info,
              contentDescription = null,
              tint = if (isKeyValid) BadgeGreen else AccentRed,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = keyStatusText,
              fontSize = 12.sp,
              color = if (isKeyValid) BadgeGreen else AccentRed,
              fontWeight = FontWeight.Medium
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Buttons: Test Key, Change Key, Remove Key
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = {
              isTestingKey = true
              coroutineScope.launch {
                when (val res = keyRepository.testActiveKey()) {
                  is KeyValidationResult.Success -> {
                    isTestingKey = false
                    isKeyValid = true
                    keyStatusText = "✓ Connected"
                    Toast.makeText(context, "✓ Key connected (1 unit used)", Toast.LENGTH_SHORT).show()
                  }
                  is KeyValidationResult.QuotaExceededSaved -> {
                    isTestingKey = false
                    isKeyValid = true
                    keyStatusText = "✓ Connected (Quota full)"
                    Toast.makeText(context, "Key sahi hai, par aaj ka quota khatam.", Toast.LENGTH_SHORT).show()
                  }
                  is KeyValidationResult.LimitReached -> {
                    isTestingKey = false
                    Toast.makeText(context, "Maximum keys reached", Toast.LENGTH_SHORT).show()
                  }
                  is KeyValidationResult.Error -> {
                    isTestingKey = false
                    isKeyValid = false
                    keyStatusText = "✗ Invalid"
                    Toast.makeText(context, res.userMessage, Toast.LENGTH_SHORT).show()
                  }
                }
              }
            },
            enabled = !isTestingKey,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("test_key_button")
          ) {
            if (isTestingKey) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Test key", fontSize = 12.sp)
            }
          }

          OutlinedButton(
            onClick = {
              newKeyInput = ""
              newKeyError = null
              keyDialogMode = KeyDialogMode.REPLACE_ACTIVE
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("change_key_button")
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Change key", fontSize = 12.sp)
          }

          Button(
            onClick = { removeKeyIndex = activeKeyIndex },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed.copy(alpha = 0.2f)),
            modifier = Modifier.weight(1f).testTag("remove_key_button")
          ) {
            Icon(Icons.Default.Delete, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Remove", fontSize = 12.sp, color = AccentRed)
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(10.dp))

        // Saved keys. Quota is per key; rotation picks the next usable one.
        if (apiKeys.isNotEmpty()) {
          Text(
            "Saved keys (${apiKeys.size}/${SettingsStore.MAX_API_KEYS})",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(4.dp))
          apiKeys.forEach { savedKey ->
            val usage = quotaStatus.keyUsages.firstOrNull { it.index == savedKey.index }
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Key ${savedKey.index + 1}${if (savedKey.isActive) " · Active" else ""}",
                  fontSize = 12.sp,
                  fontWeight = if (savedKey.isActive) FontWeight.Bold else FontWeight.Normal,
                  color = if (savedKey.isActive) BadgeGreen else MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = savedKey.masked,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (usage != null) {
                  Text(
                    text = "${usage.mainUnits}/${quotaStatus.mainLimit} units · " +
                      "${usage.searchCalls}/${quotaStatus.searchLimit} searches" +
                      if (usage.isExhausted) " · Khatam" else "",
                    fontSize = 11.sp,
                    color = if (usage.isExhausted) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              if (!savedKey.isActive) {
                TextButton(
                  onClick = {
                    coroutineScope.launch {
                      keyRepository.setActiveKey(savedKey.index)
                      onKeysChanged()
                    }
                  }
                ) {
                  Text("Use", fontSize = 12.sp, color = SecondaryCyan)
                }
              }
              IconButton(onClick = { removeKeyIndex = savedKey.index }) {
                Icon(
                  Icons.Default.Delete,
                  contentDescription = "Remove key ${savedKey.index + 1}",
                  tint = AccentRed,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))
        if (apiKeys.size < SettingsStore.MAX_API_KEYS) {
          OutlinedButton(
            onClick = {
              newKeyInput = ""
              newKeyError = null
              keyDialogMode = KeyDialogMode.ADD
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("add_key_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add another key", fontSize = 12.sp)
          }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(10.dp))

        // Quota usage below key
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Speed, contentDescription = null, tint = SecondaryCyan, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            "Quota Usage (Resets midnight Pacific / ${quotaStatus.resetTimeLocal}):",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "• search.list bucket: ${quotaStatus.searchCalls} / ${quotaStatus.searchLimit} calls",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "• main units (videos/channels/playlists): ${quotaStatus.mainUnits} / ${quotaStatus.mainLimit} units",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurface
        )
      }
    }

    // SECTION 2: Gemini AI Configuration
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          "Gemini AI Analysis (Firebase AI Logic)",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
          value = modelInput,
          onValueChange = {
            modelInput = it
            onModelChange(it)
          },
          label = { Text("Gemini Model Name") },
          singleLine = true,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().testTag("gemini_model_input")
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text("AI Response Language:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("Hinglish", "Hindi (Devanagari)", "English").forEach { lang ->
            val selected = when (lang) {
              "Hindi (Devanagari)" -> aiLanguage.equals("Hindi", true) ||
                aiLanguage.equals("Hindi (Devanagari)", true) ||
                aiLanguage.equals("Devanagari", true)
              else -> aiLanguage.equals(lang, ignoreCase = true)
            }
            FilterChip(
              selected = selected,
              onClick = { onAiLanguageChange(lang) },
              label = { Text(lang, fontSize = 12.sp) }
            )
          }
        }
      }
    }

    // SECTION 3: Appearance & Language
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          "Appearance & App Language",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))

        Text("UI Language:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("Hinglish", "English").forEach { lang ->
            FilterChip(
              selected = uiLanguage.equals(lang, ignoreCase = true),
              onClick = { onUiLanguageChange(lang) },
              label = { Text(lang, fontSize = 12.sp) },
              modifier = Modifier.testTag("ui_lang_$lang")
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text("Theme Mode:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf("Dark", "Light", "System").forEach { theme ->
            FilterChip(
              selected = themeMode.equals(theme, ignoreCase = true),
              onClick = { onThemeModeChange(theme.uppercase()) },
              label = { Text(theme, fontSize = 12.sp) },
              modifier = Modifier.testTag("theme_mode_$theme")
            )
          }
        }
      }
    }

    // SECTION 4: Data Management & Cache
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          "Data Management",
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = {
              onClearCache()
              Toast.makeText(context, "Search, Channel & AI caches cleared", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("clear_cache_button")
          ) {
            Text("Clear Cache", fontSize = 12.sp)
          }

          Button(
            onClick = { showClearDataDialog = true },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
            modifier = Modifier.weight(1f).testTag("clear_all_data_button")
          ) {
            Text("Clear All Data", fontSize = 12.sp, color = Color.White)
          }
        }
      }
    }

    // SECTION 5: Help & About
    Card(
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          "Help & Notes",
          fontSize = 15.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = appStrings.settingsHelpText,
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "About: Outlier Radar v1.0",
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = SecondaryCyan
        )
      }
    }

    Spacer(modifier = Modifier.height(30.dp))
  }

  // Add / Change Key Dialog
  val dialogMode = keyDialogMode
  if (dialogMode != null) {
    val isAddMode = dialogMode == KeyDialogMode.ADD
    AlertDialog(
      onDismissRequest = { if (!isSavingNewKey) keyDialogMode = null },
      title = { Text(if (isAddMode) "Add YouTube API Key" else "Change YouTube API Key") },
      text = {
        Column {
          OutlinedTextField(
            value = newKeyInput,
            onValueChange = {
              newKeyInput = it.trim()
              newKeyError = null
            },
            label = { Text("New YouTube API Key") },
            isError = newKeyError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )
          if (newKeyError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(newKeyError ?: "", color = AccentRed, fontSize = 12.sp)
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val formatErr = keyRepository.validateFormat(newKeyInput)
            if (formatErr != null) {
              newKeyError = formatErr
              return@Button
            }
            isSavingNewKey = true
            coroutineScope.launch {
              val result = if (isAddMode) {
                keyRepository.addKey(newKeyInput)
              } else {
                keyRepository.replaceActiveKey(newKeyInput)
              }
              when (result) {
                is KeyValidationResult.Success -> {
                  isSavingNewKey = false
                  keyDialogMode = null
                  isKeyValid = true
                  keyStatusText = "✓ Connected"
                  onKeysChanged()
                  Toast.makeText(
                    context,
                    if (isAddMode) "✓ Key added and verified" else "✓ Key updated and verified",
                    Toast.LENGTH_SHORT
                  ).show()
                }
                is KeyValidationResult.QuotaExceededSaved -> {
                  isSavingNewKey = false
                  keyDialogMode = null
                  isKeyValid = true
                  keyStatusText = "✓ Connected (Quota full)"
                  onKeysChanged()
                  Toast.makeText(context, "Key saved (quota reset pending)", Toast.LENGTH_SHORT).show()
                }
                is KeyValidationResult.LimitReached -> {
                  isSavingNewKey = false
                  newKeyError = "Maximum ${SettingsStore.MAX_API_KEYS} keys allowed"
                }
                is KeyValidationResult.Error -> {
                  isSavingNewKey = false
                  newKeyError = result.userMessage
                }
              }
            }
          },
          enabled = !isSavingNewKey
        ) {
          if (isSavingNewKey) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
          } else {
            Text(if (isAddMode) "Add & Verify" else "Save & Verify")
          }
        }
      },
      dismissButton = {
        TextButton(onClick = { keyDialogMode = null }, enabled = !isSavingNewKey) {
          Text("Cancel")
        }
      }
    )
  }

  // Remove Key Dialog
  val indexToRemove = removeKeyIndex
  if (indexToRemove != null) {
    AlertDialog(
      onDismissRequest = { removeKeyIndex = null },
      title = { Text("Remove API Key?") },
      text = {
        Text(
          "Key ${indexToRemove + 1} phone se delete ho jayegi. " +
            if (apiKeys.size <= 1) {
              "Yeh aakhri key hai, isliye app Onboarding screen par chala jayega."
            } else {
              "Baaki keys use hoti rahengi."
            }
        )
      },
      confirmButton = {
        Button(
          onClick = {
            removeKeyIndex = null
            coroutineScope.launch {
              keyRepository.removeKeyAt(indexToRemove)
              Toast.makeText(context, "Key removed", Toast.LENGTH_SHORT).show()
              onKeysChanged()
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
          Text("Remove", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { removeKeyIndex = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // Clear All Data Confirmation Dialog
  if (showClearDataDialog) {
    AlertDialog(
      onDismissRequest = { showClearDataDialog = false },
      title = { Text("Clear All Data?") },
      text = {
        Text("Saved outliers, cached searches, and app settings sab delete ho jayenge.")
      },
      confirmButton = {
        Button(
          onClick = {
            showClearDataDialog = false
            onClearAllData()
            Toast.makeText(context, "All data cleared", Toast.LENGTH_SHORT).show()
          },
          colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
        ) {
          Text("Clear All", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearDataDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

private enum class KeyDialogMode { ADD, REPLACE_ACTIVE }
