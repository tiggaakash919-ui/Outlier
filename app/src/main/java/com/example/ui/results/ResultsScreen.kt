package com.example.ui.results

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.DebugStats
import com.example.domain.EmptyStageReason
import com.example.domain.NearMissVideo
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import com.example.domain.SortOption
import com.example.ui.components.DebugCard
import com.example.ui.components.OutlierCard
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.LocalAppStrings
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import kotlinx.coroutines.launch
import java.time.Year

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
  outliers: List<OutlierVideo>,
  nearMisses: List<NearMissVideo> = emptyList(),
  keyword: String,
  debugStats: DebugStats,
  isLoading: Boolean,
  progressMessage: String?,
  nextPageToken: String?,
  onLoadMore: () -> Unit,
  onTryMore: (String) -> Unit, // variant or time slice
  onLoosenFilters: () -> Unit,
  onApplyEmptyStageFix: (EmptyStageReason) -> Unit = {},
  onSaveToggle: (OutlierVideo) -> Unit,
  onAiClick: (OutlierVideo) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appStrings = LocalAppStrings.current
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()
  val trimmedKeyword = remember(keyword) { OutlierCalculator.trimKeyword(keyword) }

  var selectedSort by remember { mutableStateOf(SortOption.MULTIPLIER) }
  var showNonShorts by remember { mutableStateOf(false) }
  var showDisclaimer by remember { mutableStateOf(true) }
  var showTryMoreDialog by remember { mutableStateOf(false) }

  // Filtered and sorted list
  val displayList by remember(outliers, selectedSort, showNonShorts) {
    derivedStateOf {
      var list = outliers
      if (!showNonShorts) {
        list = list.filter { it.shortStatus != ShortStatus.NOT_A_SHORT }
      }
      when (selectedSort) {
        SortOption.MULTIPLIER -> list.sortedByDescending { it.outlierMultiplier }
        SortOption.VIEWS -> list.sortedByDescending { it.views }
        SortOption.NEWEST -> list.sortedByDescending { it.publishedAt }
        SortOption.LOWEST_SUBS -> list.sortedBy { it.subscriberCount }
        SortOption.VIEWS_PER_SUB -> list.sortedByDescending { it.viewsPerSub }
      }
    }
  }

  fun copyToClipboard(label: String, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, content)
    clipboard.setPrimaryClip(clip)
    coroutineScope.launch {
      snackbarHostState.showSnackbar("✓ $label copied to clipboard")
    }
  }

  fun openShort(videoId: String) {
    val intent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("https://www.youtube.com/shorts/$videoId")
    )
    try {
      context.startActivity(intent)
    } catch (_: Exception) {
      Toast.makeText(context, "Link open nahi ho paya", Toast.LENGTH_SHORT).show()
    }
  }

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      item {
        Spacer(modifier = Modifier.height(10.dp))

        // Header: "{N} outliers mile for '{kw}'" (trimmed keyword)
        Text(
          text = appStrings.formatResultsHeader(context, displayList.size, trimmedKeyword),
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.testTag("results_header_title")
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sort Selector
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.FilterList,
            contentDescription = null,
            tint = PrimaryViolet,
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Sort:",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Spacer(modifier = Modifier.height(6.dp))

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          SortOption.entries.forEach { option ->
            FilterChip(
              selected = selectedSort == option,
              onClick = { selectedSort = option },
              label = { Text(option.label, fontSize = 11.sp) }
            )
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Toggle: "Show non-Shorts"
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = appStrings.showNonShorts,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Switch(
            checked = showNonShorts,
            onCheckedChange = { showNonShorts = it },
            colors = SwitchDefaults.colors(
              checkedThumbColor = PrimaryViolet,
              checkedTrackColor = PrimaryViolet.copy(alpha = 0.5f)
            ),
            modifier = Modifier.testTag("toggle_show_non_shorts")
          )
        }

        // Dismissible Disclaimer
        AnimatedVisibility(visible = showDisclaimer) {
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp)
          ) {
            Row(
              modifier = Modifier.padding(10.dp),
              verticalAlignment = Alignment.Top
            ) {
              Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = SecondaryCyan,
                modifier = Modifier.size(18.dp).padding(top = 2.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = appStrings.resultsDisclaimer,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
              )
              IconButton(
                onClick = { showDisclaimer = false },
                modifier = Modifier.size(20.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Dismiss",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }
      }

      // Loading state with Progress banner + Shimmer Placeholder Cards
      if (isLoading) {
        item {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(16.dp).fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center
            ) {
              CircularProgressIndicator(
                color = PrimaryViolet,
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = progressMessage ?: "Outliers dhoonde ja rahe hain...",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = SecondaryCyan
              )
            }
          }
        }

        items(3) {
          ShimmerOutlierCardPlaceholder()
        }
      }

      // Smart Empty state (Requirement 4)
      if (!isLoading && displayList.isEmpty()) {
        val detectedStage = OutlierCalculator.detectEmptyStage(debugStats)
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
          ) {
            Column(
              modifier = Modifier.padding(22.dp).fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(44.dp)
              )
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = detectedStage.message,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("empty_state_stage_message")
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = appStrings.resultsEmptySubtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
              )
              Spacer(modifier = Modifier.height(16.dp))

              // Targeted one-tap fix button
              Button(
                onClick = { onApplyEmptyStageFix(detectedStage) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("empty_stage_fix_button")
              ) {
                Icon(
                  imageVector = Icons.Default.AutoFixHigh,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                  tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = detectedStage.fixButtonLabel,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = Color.White
                )
              }

              Spacer(modifier = Modifier.height(10.dp))

              // Existing "Loosen filters" button
              OutlinedButton(
                onClick = onLoosenFilters,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("loosen_filters_button")
              ) {
                Text(appStrings.loosenFiltersBtn, fontSize = 14.sp, color = PrimaryViolet)
              }
            }
          }
        }
      }

      // List of Outlier Cards with animated placement
      items(
        items = displayList,
        key = { it.videoId }
      ) { video ->
        OutlierCard(
          video = video,
          onOpenClick = { openShort(it) },
          onSaveToggle = onSaveToggle,
          onCopyClick = { label, content -> copyToClipboard(label, content) },
          onAiClick = onAiClick,
          modifier = Modifier.animateItem()
        )
      }

      // Near misses section when final results < 5 (Requirement 5)
      if (!isLoading && displayList.size < 5 && nearMisses.isNotEmpty()) {
        item {
          NearMissesSection(
            nearMisses = nearMisses.take(10),
            defaultExpanded = displayList.isEmpty(),
            onOpenShort = { openShort(it) }
          )
        }
      }

      // Action Buttons: Load More & Try More
      if (!isLoading && displayList.isNotEmpty()) {
        item {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            if (!nextPageToken.isNullOrBlank()) {
              Button(
                onClick = onLoadMore,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
                modifier = Modifier.weight(1f).testTag("load_more_button")
              ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Load more", fontSize = 14.sp, color = Color.White)
              }
            }

            OutlinedButton(
              onClick = { showTryMoreDialog = true },
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.weight(1f).testTag("try_more_button")
            ) {
              Text("Try more 🎯", fontSize = 14.sp, color = SecondaryCyan)
            }
          }
        }
      }

      // Expandable Debug Card (Auto-expands when finalOutliers == 0)
      if (!isLoading && (debugStats.searchReturned > 0 || debugStats.pages > 0 || displayList.isEmpty())) {
        item {
          DebugCard(
            debugStats = debugStats,
            defaultExpanded = displayList.isEmpty() || debugStats.finalOutliers == 0
          )
          Spacer(modifier = Modifier.height(20.dp))
        }
      }
    }

    // Snackbar Host for copy confirmations
    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
    )

    // "Try more" Confirmation & Options Dialog
    if (showTryMoreDialog) {
      val currentYear = Year.now().value
      AlertDialog(
        onDismissRequest = { showTryMoreDialog = false },
        title = {
          Text("Try More Angles", fontWeight = FontWeight.Bold)
        },
        text = {
          Column {
            Text(
              text = "Estimated cost: ~1-2 search calls + ~4-10 units. Channel cache will be reused to save quota.",
              fontSize = 13.sp,
              color = BadgeYellow
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text("Keyword Variants:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              listOf("$trimmedKeyword shorts", "$trimmedKeyword viral", "$trimmedKeyword $currentYear").forEach { variant ->
                ElevatedFilterChip(
                  selected = false,
                  onClick = {
                    showTryMoreDialog = false
                    onTryMore(variant)
                  },
                  label = { Text(variant, fontSize = 11.sp) }
                )
              }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Time Slices:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              listOf("Last 7d" to "7d", "8–30d ago" to "30d", "31–90d ago" to "90d", "91–180d ago" to "6mo").forEach { (label, slice) ->
                ElevatedFilterChip(
                  selected = false,
                  onClick = {
                    showTryMoreDialog = false
                    onTryMore(slice)
                  },
                  label = { Text(label, fontSize = 11.sp) }
                )
              }
            }
          }
        },
        confirmButton = {},
        dismissButton = {
          TextButton(onClick = { showTryMoreDialog = false }) {
            Text("Cancel")
          }
        }
      )
    }
  }
}

@Composable
private fun NearMissesSection(
  nearMisses: List<NearMissVideo>,
  defaultExpanded: Boolean,
  onOpenShort: (String) -> Unit
) {
  var isExpanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }

  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("near_misses_card")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded }
          .testTag("near_misses_header"),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Near misses (filters se bahar) (${nearMisses.size})",
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

          nearMisses.forEach { item ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .clickable { onOpenShort(item.videoId) }
                .padding(10.dp)
                .testTag("near_miss_item_${item.videoId}"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = item.title,
                  fontSize = 13.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "${item.channelTitle} · ${OutlierCalculator.formatCompactNumber(item.views)} views · ${OutlierCalculator.formatSubscribers(item.subscriberCount, item.hiddenSubscriberCount)} subs",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Red reason chip
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentRed.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                  Text(
                    text = item.reasonChip,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentRed
                  )
                }
              }

              Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Open Short",
                tint = SecondaryCyan,
                modifier = Modifier.size(20.dp)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ShimmerOutlierCardPlaceholder() {
  val transition = rememberInfiniteTransition(label = "shimmer")
  val translateAnim by transition.animateFloat(
    initialValue = 0f,
    targetValue = 1000f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1100, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "shimmer_translate"
  )

  val shimmerBrush = Brush.linearGradient(
    colors = listOf(
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ),
    start = Offset(translateAnim - 300f, translateAnim - 300f),
    end = Offset(translateAnim, translateAnim)
  )

  Card(
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Box(
        modifier = Modifier
          .width(105.dp)
          .height(155.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(shimmerBrush)
      )
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier = Modifier
            .width(80.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(shimmerBrush)
        )
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(shimmerBrush)
        )
        Box(
          modifier = Modifier
            .fillMaxWidth(0.75f)
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(shimmerBrush)
        )
        Box(
          modifier = Modifier
            .fillMaxWidth(0.55f)
            .height(14.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(shimmerBrush)
        )
      }
    }
  }
}
