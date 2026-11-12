package com.example.ui.saved

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
  savedList: List<OutlierVideo>,
  onRemove: (String) -> Unit,
  onAiClick: (OutlierVideo) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appStrings = LocalAppStrings.current
  val coroutineScope = rememberCoroutineScope()
  var searchQuery by remember { mutableStateOf("") }

  // Storage Access Framework (CreateDocument) for UTF-8 BOM CSV export
  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/csv")
  ) { uri: Uri? ->
    if (uri != null) {
      coroutineScope.launch {
        try {
          val csvBytes = withContext(Dispatchers.Default) {
            SavedRepository.generateCsvBytesWithBom(savedList)
          }
          withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
              out.write(csvBytes)
              out.flush()
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
    if (savedList.isEmpty()) {
      Toast.makeText(context, "Save at least one outlier to export CSV", Toast.LENGTH_SHORT).show()
      return
    }
    coroutineScope.launch {
      try {
        val csvBytes = withContext(Dispatchers.Default) {
          SavedRepository.generateCsvBytesWithBom(savedList)
        }
        val fileUri = withContext(Dispatchers.IO) {
          val exportDir = File(context.cacheDir, "exports")
          if (!exportDir.exists()) exportDir.mkdirs()
          val csvFile = File(exportDir, "outlier_radar_saved.csv")
          csvFile.writeBytes(csvBytes)
          FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            csvFile
          )
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

  val filteredList = if (searchQuery.isBlank()) {
    savedList
  } else {
    savedList.filter {
      it.title.contains(searchQuery, ignoreCase = true) ||
        it.channelTitle.contains(searchQuery, ignoreCase = true) ||
        it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      item {
        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column {
            Text(
              text = appStrings.savedTitle,
              fontSize = 22.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onBackground
            )
            Text(
              text = "${savedList.size} items bookmarked",
              fontSize = 12.sp,
              color = SecondaryCyan
            )
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = {
                if (savedList.isEmpty()) {
                  Toast.makeText(context, "No saved outliers to export", Toast.LENGTH_SHORT).show()
                } else {
                  createDocumentLauncher.launch("outlier_radar_saved.csv")
                }
              },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("export_csv_button")
            ) {
              Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("CSV", fontSize = 12.sp)
            }

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

        Spacer(modifier = Modifier.height(12.dp))

        // Search within saved items & Summary Insights Bar
        if (savedList.isNotEmpty()) {
          val avgMultiplier = savedList.map { it.outlierMultiplier }.average().toInt()
          val maxViews = savedList.maxOfOrNull { it.views } ?: 0L
          val minSubs = savedList.minOfOrNull { it.subscriberCount } ?: 0L

          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
              horizontalArrangement = Arrangement.SpaceEvenly
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${avgMultiplier}x", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PrimaryViolet)
                Text("Avg Multiplier", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(com.example.domain.OutlierCalculator.formatCompactNumber(maxViews), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SecondaryCyan)
                Text("Peak Views", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("~${com.example.domain.OutlierCalculator.formatCompactNumber(minSubs)}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentRed)
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
            leadingIcon = {
              Icon(imageVector = Icons.Default.Search, contentDescription = null)
            },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { searchQuery = "" }) {
                  Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search")
                }
              }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedContainerColor = MaterialTheme.colorScheme.surface,
              unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("search_saved_input")
          )
        }
      }

      if (savedList.isEmpty()) {
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 32.dp)
          ) {
            Column(
              modifier = Modifier
                .padding(28.dp)
                .fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = PrimaryViolet,
                modifier = Modifier.size(54.dp)
              )
              Spacer(modifier = Modifier.height(14.dp))
              Text(
                text = appStrings.savedEmptyTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = appStrings.savedEmptySubtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
            }
          }
        }
      } else {
        items(
          items = filteredList,
          key = { it.videoId }
        ) { video ->
          val dismissState = rememberSwipeToDismissBoxState()

          LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart ||
              dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd
            ) {
              onRemove(video.videoId)
            }
          }

          SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.animateItem(),
            backgroundContent = {
              val bgColor by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) {
                  MaterialTheme.colorScheme.surfaceVariant
                } else {
                  AccentRed.copy(alpha = 0.85f)
                },
                label = "swipe_bg"
              )
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .clip(RoundedCornerShape(20.dp))
                  .background(bgColor)
                  .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd
              ) {
                Icon(
                  imageVector = Icons.Default.Delete,
                  contentDescription = "Remove saved item",
                  tint = Color.White,
                  modifier = Modifier.size(28.dp)
                )
              }
            }
          ) {
            OutlierCard(
              video = video,
              onOpenClick = { vid ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts/$vid"))
                try {
                  context.startActivity(intent)
                } catch (_: Exception) {
                  Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
                }
              },
              onSaveToggle = { onRemove(video.videoId) },
              onCopyClick = { label, content ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, content))
                Toast.makeText(context, "✓ $label copied", Toast.LENGTH_SHORT).show()
              },
              onAiClick = onAiClick
            )
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(30.dp))
      }
    }
  }
}
