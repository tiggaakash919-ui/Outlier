package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.DebugStats
import com.example.ui.theme.SecondaryCyan

@Composable
fun DebugCard(
  debugStats: DebugStats,
  defaultExpanded: Boolean = debugStats.finalOutliers == 0,
  modifier: Modifier = Modifier
) {
  var isExpanded by remember(debugStats.finalOutliers, defaultExpanded) {
    mutableStateOf(defaultExpanded)
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("debug_stats_card"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded }
          .testTag("debug_stats_header"),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Default.BugReport,
          contentDescription = null,
          tint = SecondaryCyan,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Search Pipeline & Quota Debug",
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
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
            .padding(top = 10.dp)
            .testTag("debug_stats_content")
        ) {
          Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
          Spacer(modifier = Modifier.height(8.dp))

          val debugLines = listOf(
            "search.list returned: ${debugStats.searchReturned} (pages: ${debugStats.pages})",
            "→ after duration filter: ${debugStats.afterDurationFilter}",
            "→ after minViews filter: ${debugStats.afterMinViewsFilter}",
            "→ unique channels: ${debugStats.uniqueChannels}",
            "→ after subs filter (incl. hidden-sub skip): ${debugStats.afterSubsFilter}",
            "→ channels <5 Shorts (skipped): ${debugStats.channelsWithTooFewShorts}",
            "→ channels median > max (skipped): ${debugStats.channelsMedianTooHigh}",
            "→ channels with >=5 Shorts & median<=max: ${debugStats.channelsPassingMedian}   (UUSH: ${debugStats.uushCount} / uploads fallback: ${debugStats.uploadsFallbackCount})",
            "→ negative cache hits (skipped): ${debugStats.negativeCacheHits}   (cost-skipped medians: ${debugStats.medianSkippedByCost})",
            "→ final outliers (>= minMultiplier): ${debugStats.finalOutliers}   (verified Shorts: ${debugStats.verifiedShorts})",
            "Cost: search calls ${debugStats.pages} | videos.list ${debugStats.videosListCost} | channels.list ${debugStats.channelsListCost} | per-channel ${debugStats.perChannelCost} | main total ${debugStats.mainTotalCost} | channel cache hits ${debugStats.cacheHits} | video cache hits ${debugStats.videoStatsCacheHits}"
          )

          debugLines.forEach { line ->
            Text(
              text = line,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = if (line.startsWith("Cost:")) SecondaryCyan else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 2.dp)
            )
          }
        }
      }
    }
  }
}
