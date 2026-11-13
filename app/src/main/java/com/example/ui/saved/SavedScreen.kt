package com.example.ui.saved

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.repository.SavedRepository
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.ui.components.OutlierCard
import com.example.ui.theme.AccentRed
import com.example.ui.theme.LocalAppStrings
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SavedSort(val label: String) {
  SAVED_NEWEST("Saved newest"),
  SAVED_OLDEST("Saved oldest"),
  SCORE("Score"),
  MULTIPLIER("Multiplier"),
  VIEWS("Views"),
  NEWEST_UPLOAD("Newest upload"),
  LOWEST_SUBS("Lowest subs")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SavedScreen(
  savedList: List<OutlierVideo>,
  themeMode: String = "DARK",
  onThemeModeChange: (String) -> Unit = {},
  onRemove: (String) -> Unit,
  onUpdateFolder: (String, String) -> Unit = { _, _ -> },
  onUpdateTags: (String, List<String>) -> Unit = { _, _ -> },
  onAiClick: (OutlierVideo) -> Unit,
  onCardClick: (OutlierVideo) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appStrings = LocalAppStrings.current
  val coroutineScope = rememberCoroutineScope()
  var searchQuery by remember { mutableStateOf("") }
  var selectedFolder by remember { mutableStateOf("All") }
  var selectedSort by remember { mutableStateOf(SavedSort.SAVED_NEWEST) }
  var folderEditFor by remember { mutableStateOf<OutlierVideo?>(null) }
  var tagEditFor by remember { mutableStateOf<OutlierVideo?>(null) }

  val presetFolders = listOf("Ideas to shoot", "Done")
  val availableFolders = remember(savedList) {
    (presetFolders + savedList.map { it.savedFolder.ifBlank { "Ideas to shoot" } })
      .filter { it.isNotBlank() }
      .distinct()
  }

  val filteredList = remember(savedList, searchQuery, selectedFolder, selectedSort) {
    val q = searchQuery.trim()
    var list = savedList
    if (selectedFolder != "All") {
      list = list.filter { it.savedFolder.equals(selectedFolder, ignoreCase = true) }
    }
    if (q.isNotBlank()) {
      list = list.filter { v ->
        v.title.contains(q, true) ||
          v.channelTitle.contains(q, true) ||
          v.tags.any { it.contains(q, true) } ||
          v.savedFolder.contains(q, true) ||
          v.savedUserTags.any { it.contains(q, true) }
      }
    }
    when (selectedSort) {
      SavedSort.SAVED_NEWEST -> list.sortedByDescending { it.savedAt }
      SavedSort.SAVED_OLDEST -> list.sortedBy { it.savedAt }
      SavedSort.SCORE -> list.sortedByDescending { OutlierCalculator.calculateOutlierScore(it) }
      SavedSort.MULTIPLIER -> list.sortedByDescending { it.outlierMultiplier }
      SavedSort.VIEWS -> list.sortedByDescending { it.views }
      SavedSort.NEWEST_UPLOAD -> list.sortedByDescending { it.publishedAt }
      SavedSort.LOWEST_SUBS -> list.sortedBy { it.subscriberCount }
    }
  }

  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv")
  ) { uri: Uri? ->
    if (uri != null) {
      coroutineScope.launch {
        try {
          val csvBytes = withContext(Dispatchers.Default) {
            SavedRepository.generateSavedCsvBytesWithBom(filteredList)
          }
          withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
              out.write(csvBytes); out.flush()
            }
          }
          Toast.makeText(context, "✓ CSV exported successfully (UTF-8 with BOM)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
          Toast.makeText(context, "CSV export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  fun shareCsvViaFileProvider() {
    if (filteredList.isEmpty()) {
      Toast.makeText(context, "Export/share ke liye saved items chahiye", Toast.LENGTH_SHORT).show()
      return
    }
    coroutineScope.launch {
      try {
        val csvBytes = withContext(Dispatchers.Default) {
          SavedRepository.generateSavedCsvBytesWithBom(filteredList)
        }
        val fileUri = withContext(Dispatchers.IO) {
          val exportDir = File(context.cacheDir, "exports")
          if (!exportDir.exists()) exportDir.mkdirs()
          val csvFile = File(exportDir, "outlier_radar_saved.csv")
          csvFile.writeBytes(csvBytes)
          FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
          type = "text/csv"
          putExtra(Intent.EXTRA_STREAM, fileUri)
          putExtra(Intent.EXTRA_SUBJECT, "Outlier Radar - Saved Shorts CSV")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Saved Outliers CSV"))
      } catch (e: Exception) {
        Toast.makeText(context, "Share failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  PullToRefreshBox(
    isRefreshing = false,
    onRefresh = { /* local data; no-op refresh */ },
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      item {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(appStrings.savedTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("${filteredList.size} / ${savedList.size} items", fontSize = 12.sp, color = SecondaryCyan)
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onThemeModeChange(if (themeMode.equals("LIGHT", true)) "DARK" else "LIGHT") }) {
              Icon(
                if (themeMode.equals("LIGHT", true)) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = "Toggle theme",
                tint = MaterialTheme.colorScheme.onBackground
              )
            }
            OutlinedButton(
              onClick = {
                if (filteredList.isEmpty()) Toast.makeText(context, "No saved outliers to export", Toast.LENGTH_SHORT).show()
                else createDocumentLauncher.launch("outlier_radar_saved.csv")
              },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("export_csv_button")
            ) {
              Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("CSV", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Button(
              onClick = { shareCsvViaFileProvider() },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
              modifier = Modifier.testTag("share_csv_button")
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Share", fontSize = 12.sp, color = Color.White)
            }
          }
        }

        if (savedList.isNotEmpty()) {
          Spacer(modifier = Modifier.height(10.dp))
          val avgMultiplier = filteredList.map { it.outlierMultiplier }.average().takeIf { !it.isNaN() } ?: 0.0
          val maxViews = filteredList.maxOfOrNull { it.views } ?: 0L
          val minSubs = filteredList.minOfOrNull { it.subscriberCount } ?: 0L
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${OutlierCalculator.formatMultiplier(avgMultiplier)}x", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryViolet)
                Text("Avg Multiplier", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(OutlierCalculator.formatCompactNumber(maxViews), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SecondaryCyan)
                Text("Peak Views", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("~${OutlierCalculator.formatCompactNumber(minSubs)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentRed)
                Text("Smallest Subs", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(appStrings.savedSearchHint) },
            singleLine = true,
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { searchQuery = "" }) { Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search") }
              }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = MaterialTheme.colorScheme.surface,
              unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.fillMaxWidth().testTag("search_saved_input")
          )

          Spacer(modifier = Modifier.height(8.dp))
          Text("Folders", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(modifier = Modifier.height(4.dp))
          Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All") + availableFolders).distinct().forEach { folder ->
              FilterChip(
                selected = selectedFolder.equals(folder, ignoreCase = true),
                onClick = { selectedFolder = folder },
                label = { Text(folder, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = PrimaryViolet.copy(alpha = 0.22f),
                  selectedLabelColor = MaterialTheme.colorScheme.onSurface
                )
              )
            }
          }

          Spacer(modifier = Modifier.height(6.dp))
          Text("Sort", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(modifier = Modifier.height(4.dp))
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SavedSort.entries.forEach { opt ->
              FilterChip(selected = selectedSort == opt, onClick = { selectedSort = opt }, label = { Text(opt.label, fontSize = 11.sp) })
            }
          }
        }
      }

      if (savedList.isEmpty()) {
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
          ) {
            Column(modifier = Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = PrimaryViolet, modifier = Modifier.size(54.dp))
              Spacer(modifier = Modifier.height(14.dp))
              Text(appStrings.savedEmptyTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
              Spacer(modifier = Modifier.height(6.dp))
              Text(appStrings.savedEmptySubtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
          }
        }
      } else if (filteredList.isEmpty()) {
        item {
          Text("Is filter/search me koi saved item nahi mila.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        }
      } else {
        items(items = filteredList, key = { it.videoId }) { video ->
          val dismissState = rememberSwipeToDismissBoxState()
          LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart || dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
              onRemove(video.videoId)
            }
          }

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              FilterChip(
                selected = false,
                onClick = { folderEditFor = video },
                label = { Text("📁 ${video.savedFolder.ifBlank { "Ideas to shoot" }}", fontSize = 11.sp) }
              )
              FilterChip(
                selected = false,
                onClick = { tagEditFor = video },
                label = {
                  val tagsText = if (video.savedUserTags.isEmpty()) "Add tags" else video.savedUserTags.joinToString(", ").take(24)
                  Text("🏷 $tagsText", fontSize = 11.sp, maxLines = 1)
                }
              )
            }

            SwipeToDismissBox(
              state = dismissState,
              modifier = Modifier.animateItem(),
              backgroundContent = {
                val bgColor by animateColorAsState(
                  targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) MaterialTheme.colorScheme.surfaceVariant else AccentRed.copy(alpha = 0.85f),
                  label = "swipe_bg"
                )
                Box(
                  modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(bgColor).padding(horizontal = 24.dp),
                  contentAlignment = Alignment.CenterEnd
                ) {
                  Icon(Icons.Default.Delete, contentDescription = "Remove saved item", tint = Color.White, modifier = Modifier.size(28.dp))
                }
              }
            ) {
              OutlierCard(
                video = video,
                onOpenClick = { vid ->
                  val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts/$vid"))
                  try { context.startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
                  }
                },
                onSaveToggle = { onRemove(video.videoId) },
                onCopyClick = { label, content ->
                  val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                  clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, content))
                  Toast.makeText(context, "✓ $label copied", Toast.LENGTH_SHORT).show()
                },
                onAiClick = onAiClick,
                onCardClick = onCardClick
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(30.dp)) }
    }
  }

  val folderTarget = folderEditFor
  if (folderTarget != null) {
    FolderPickerDialog(
      current = folderTarget.savedFolder.ifBlank { "Ideas to shoot" },
      existing = availableFolders,
      onDismiss = { folderEditFor = null },
      onSelect = { folder ->
        onUpdateFolder(folderTarget.videoId, folder)
        folderEditFor = null
      }
    )
  }

  val tagTarget = tagEditFor
  if (tagTarget != null) {
    TagEditorDialog(
      current = tagTarget.savedUserTags,
      onDismiss = { tagEditFor = null },
      onSave = { tags ->
        onUpdateTags(tagTarget.videoId, tags)
        tagEditFor = null
      }
    )
  }
}

@Composable
private fun FolderPickerDialog(
  current: String,
  existing: List<String>,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit
) {
  var custom by remember(current) { mutableStateOf("") }
  val options = (listOf("Ideas to shoot", "Done") + existing).distinct()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Folder choose karo", fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          options.forEach { folder ->
            FilterChip(selected = folder.equals(current, true), onClick = { onSelect(folder) }, label = { Text(folder, fontSize = 12.sp) })
          }
        }
        OutlinedTextField(
          value = custom,
          onValueChange = { custom = it },
          label = { Text("Naya folder name") },
          singleLine = true
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { if (custom.trim().isNotBlank()) onSelect(custom.trim()) }, enabled = custom.trim().isNotBlank()) {
        Text("Create/Set")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}

@Composable
private fun TagEditorDialog(
  current: List<String>,
  onDismiss: () -> Unit,
  onSave: (List<String>) -> Unit
) {
  var input by remember(current) { mutableStateOf(current.joinToString(", ")) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Saved tags", fontWeight = FontWeight.Bold) },
    text = {
      Column {
        Text("Comma se alag tags daalo (e.g. hook, tutorial, remake)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          label = { Text("Tags") },
          singleLine = false
        )
      }
    },
    confirmButton = {
      TextButton(onClick = {
        val tags = input.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
        onSave(tags)
      }) { Text("Save") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
  )
}
