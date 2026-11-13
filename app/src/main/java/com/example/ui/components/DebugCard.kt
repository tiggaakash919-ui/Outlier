package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.DebugStats
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import kotlin.math.max

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
          Spacer(modifier = Modifier.height(10.dp))

          Text(
            text = "Pipeline Funnel",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(8.dp))

          FunnelChart(stats = debugStats)

          Spacer(modifier = Modifier.height(10.dp))
          Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
          Spacer(modifier = Modifier.height(8.dp))

          val detailLines = listOf(
            "unique channels: ${debugStats.uniqueChannels}",
            "after exclude: ${debugStats.afterExcludeFilter} | likes ratio: ${debugStats.afterLikesRatioFilter} | viral age: ${debugStats.afterViralAgeFilter}",
            "channels <5 Shorts (skipped): ${debugStats.channelsWithTooFewShorts}",
            "channels median > max (skipped): ${debugStats.channelsMedianTooHigh}",
            "channels with baseline: ${debugStats.channelsPassingMedian} (UUSH: ${debugStats.uushCount}, uploads fallback: ${debugStats.uploadsFallbackCount})",
            "negative cache hits: ${debugStats.negativeCacheHits} | median skipped by cost: ${debugStats.medianSkippedByCost}",
            "verified Shorts: ${debugStats.verifiedShorts} | reupload flagged: ${debugStats.reuploadFlagged}",
            "Cost: search calls ${debugStats.searchCallsCost} | videos.list ${debugStats.videosListCost} | channels.list ${debugStats.channelsListCost} | per-channel ${debugStats.perChannelCost} | main total ${debugStats.mainTotalCost}",
            "Cache hits: channel ${debugStats.cacheHits} | video stats ${debugStats.videoStatsCacheHits}"
          )

          detailLines.forEach { line ->
            Text(
              text = line,
              fontFamily = FontFamily.Monospace,
              fontSize = 11.sp,
              color = if (line.startsWith("Cost:") || line.startsWith("Cache")) SecondaryCyan
              else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 2.dp)
            )
          }
        }
      }
    }
  }
}

private data class FunnelStage(
  val label: String,
  val value: Int,
  val color: Color
)

@Composable
private fun FunnelChart(stats: DebugStats) {
  val stages = listOf(
    FunnelStage("searchReturned", stats.searchReturned, PrimaryViolet),
    FunnelStage("duration", stats.afterDurationFilter, SecondaryCyan),
    FunnelStage("minViews", stats.afterMinViewsFilter, Color(0xFF4FC3F7)),
    FunnelStage("subs", stats.afterSubsFilter, BadgeYellow),
    FunnelStage("median", stats.channelsPassingMedian, Color(0xFFFFA726)),
    FunnelStage("multiplier", stats.finalOutliers, if (stats.finalOutliers > 0) BadgeGreen else AccentRed)
  )
  val top = max(1, stages.maxOfOrNull { it.value } ?: 1)

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    stages.forEachIndexed { index, stage ->
      val prev = if (index == 0) stage.value else stages[index - 1].value
      val drop = (prev - stage.value).coerceAtLeast(0)
      val fraction = (stage.value.toFloat() / top.toFloat()).coerceIn(if (stage.value > 0) 0.08f else 0.04f, 1f)
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stage.label,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.width(92.dp)
        )
        Box(
          modifier = Modifier
            .weight(1f)
            .height(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth(fraction)
              .height(18.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(stage.color.copy(alpha = 0.85f))
          )
          Text(
            text = stage.value.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier
              .align(Alignment.CenterStart)
              .padding(horizontal = 8.dp)
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        val dropText = if (index == 0) "" else "-$drop"
        Text(
          text = dropText,
          fontSize = 10.sp,
          color = if (drop > 0) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.width(34.dp)
        )
      }
    }
  }
}
