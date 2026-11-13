package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.DurationParser
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.ShortStatus
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGray
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import kotlin.math.max
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutlierCard(
  video: OutlierVideo,
  onOpenClick: (String) -> Unit,
  onSaveToggle: (OutlierVideo) -> Unit,
  onCopyClick: (String, String) -> Unit, // type, content
  onAiClick: (OutlierVideo) -> Unit,
  onFindSimilar: ((OutlierVideo) -> Unit)? = null,
  onCardClick: ((OutlierVideo) -> Unit)? = null,
  velocityLabel: String? = null,
  onVerifyVisible: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var showCopyMenu by remember { mutableStateOf(false) }

  // Lazy verification: trigger when an UNVERIFIED card becomes visible.
  LaunchedEffect(video.videoId, video.shortStatus) {
    if (video.shortStatus == ShortStatus.UNVERIFIED) {
      onVerifyVisible?.invoke(video.videoId)
    }
  }

  val score = remember(video) { OutlierCalculator.calculateOutlierScore(video) }
  val videoAge = remember(video.publishedAt) { OutlierCalculator.formatVideoAge(video.publishedAt) }
  val shownVelocity = velocityLabel ?: OutlierCalculator.fallbackVelocityLabel(video)

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("outlier_card_${video.videoId}")
      .clickable { onCardClick?.invoke(video) ?: onOpenClick(video.videoId) },
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // Thumbnail
        Box(
          modifier = Modifier
            .width(108.dp)
            .aspectRatio(9f / 14f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
          AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = "Short thumbnail",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
          )
          Box(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(4.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(Color.Black.copy(alpha = 0.75f))
              .padding(horizontal = 4.dp, vertical = 2.dp)
          ) {
            Text(
              text = DurationParser.formatSeconds(video.durationSeconds),
              color = Color.White,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
          Box(
            modifier = Modifier
              .align(Alignment.Center)
              .size(32.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = "Open details",
              tint = Color.White,
              modifier = Modifier.size(20.dp)
            )
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          // Multiplier badge + OutlierScore ring
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            val badgeColor = OutlierCalculator.getBadgeColor(video.outlierMultiplier)
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(badgeColor.copy(alpha = 0.18f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = "${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x 🔥",
                color = badgeColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
            OutlierScoreRing(
              score = score,
              modifier = Modifier.testTag("outlier_score_${video.videoId}")
            )
          }

          Spacer(modifier = Modifier.height(6.dp))

          Text(
            text = video.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
              fontWeight = FontWeight.SemiBold,
              fontSize = 14.sp,
              lineHeight = 18.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
          )

          Spacer(modifier = Modifier.height(6.dp))

          Text(
            text = "${video.channelTitle} • ${OutlierCalculator.formatSubscribers(video.subscriberCount, video.hiddenSubscriberCount)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )

          Spacer(modifier = Modifier.height(4.dp))

          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "${OutlierCalculator.formatCompactNumber(video.views)} views",
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "· median ${OutlierCalculator.formatCompactNumber(video.channelMedianViews)}",
              fontSize = 12.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          Spacer(modifier = Modifier.height(4.dp))

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = videoAge,
              fontSize = 11.sp,
              color = SecondaryCyan,
              fontWeight = FontWeight.Medium
            )
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PrimaryViolet.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
              Text(
                text = shownVelocity,
                fontSize = 10.sp,
                color = PrimaryViolet,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Mini bar: viral views vs channel median
      ViewsVsMedianBar(
        views = video.views,
        median = video.channelMedianViews,
        modifier = Modifier.fillMaxWidth()
      )

      Spacer(modifier = Modifier.height(10.dp))

      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        when (video.shortStatus) {
          ShortStatus.CONFIRMED_SHORT -> {
            SuggestionChip(
              onClick = {},
              label = { Text("✓ Short", fontSize = 11.sp, color = BadgeGreen) },
              icon = {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BadgeGreen, modifier = Modifier.size(14.dp))
              },
              colors = SuggestionChipDefaults.suggestionChipColors(containerColor = BadgeGreen.copy(alpha = 0.12f)),
              border = null
            )
          }
          ShortStatus.NOT_A_SHORT -> {
            SuggestionChip(
              onClick = {},
              label = { Text("Not a Short", fontSize = 11.sp, color = AccentRed) },
              icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRed, modifier = Modifier.size(14.dp))
              },
              colors = SuggestionChipDefaults.suggestionChipColors(containerColor = AccentRed.copy(alpha = 0.12f)),
              border = null
            )
          }
          ShortStatus.UNVERIFIED -> {
            SuggestionChip(
              onClick = {},
              label = { Text("Unverified", fontSize = 11.sp, color = BadgeGray) },
              icon = {
                Icon(Icons.Default.HelpOutline, contentDescription = null, tint = BadgeGray, modifier = Modifier.size(14.dp))
              },
              colors = SuggestionChipDefaults.suggestionChipColors(containerColor = BadgeGray.copy(alpha = 0.12f)),
              border = null
            )
          }
        }

        if (video.viewsPerSub > 0) {
          SuggestionChip(
            onClick = {},
            label = { Text(String.format(Locale.US, "%.1fx subs", video.viewsPerSub), fontSize = 11.sp, color = SecondaryCyan) },
            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = SecondaryCyan.copy(alpha = 0.12f)),
            border = null
          )
        }

        if (video.isOneHitWonder) {
          SuggestionChip(
            onClick = {},
            label = { Text("🎯 One-off spike", fontSize = 11.sp, color = PrimaryViolet) },
            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = PrimaryViolet.copy(alpha = 0.12f)),
            border = null
          )
        }

        if (video.matchedKeyword.isNotBlank()) {
          SuggestionChip(
            onClick = {},
            label = {
              Text("🔑 ${video.matchedKeyword}", fontSize = 11.sp, color = SecondaryCyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = SecondaryCyan.copy(alpha = 0.12f)),
            border = null
          )
        }

        if (video.isPossibleReupload) {
          SuggestionChip(
            onClick = {},
            label = { Text("♻️ Possible re-upload", fontSize = 11.sp, color = BadgeYellow) },
            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = BadgeYellow.copy(alpha = 0.14f)),
            border = null,
            modifier = Modifier.testTag("reupload_flag_${video.videoId}")
          )
        }

        if (video.likes > 0) {
          val engRate = OutlierCalculator.calculateEngagementRate(video.views, video.likes)
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Icon(Icons.Default.ThumbUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (engRate != null) "${OutlierCalculator.formatCompactNumber(video.likes)} ($engRate)" else OutlierCalculator.formatCompactNumber(video.likes),
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        FilledTonalButton(
          onClick = { onOpenClick(video.videoId) },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("open_short_${video.videoId}")
        ) {
          Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Open", fontSize = 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          FilledTonalIconButton(
            onClick = { onSaveToggle(video) },
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = if (video.isSaved) AccentRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.testTag("save_short_${video.videoId}")
          ) {
            Icon(
              imageVector = if (video.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
              contentDescription = "Save outlier",
              tint = if (video.isSaved) AccentRed else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(18.dp)
            )
          }

          Box {
            FilledTonalIconButton(
              onClick = { showCopyMenu = true },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("copy_button_${video.videoId}")
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = "Copy details", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showCopyMenu, onDismissRequest = { showCopyMenu = false }) {
              DropdownMenuItem(text = { Text("Copy Title") }, onClick = { showCopyMenu = false; onCopyClick("Title", video.title) })
              DropdownMenuItem(text = { Text("Copy Link") }, onClick = { showCopyMenu = false; onCopyClick("Link", "https://www.youtube.com/shorts/${video.videoId}") })
              DropdownMenuItem(text = { Text("Copy Tags") }, onClick = { showCopyMenu = false; onCopyClick("Tags", video.tags.joinToString(", ")) })
              DropdownMenuItem(text = { Text("Copy Description") }, onClick = { showCopyMenu = false; onCopyClick("Description", video.description) })
              DropdownMenuItem(text = { Text("Copy Channel URL") }, onClick = { showCopyMenu = false; onCopyClick("Channel URL", "https://www.youtube.com/channel/${video.channelId}/shorts") })
            }
          }

          if (onFindSimilar != null) {
            FilledTonalIconButton(
              onClick = { onFindSimilar.invoke(video) },
              shape = RoundedCornerShape(12.dp),
              colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SecondaryCyan.copy(alpha = 0.16f)),
              modifier = Modifier.testTag("find_similar_${video.videoId}")
            ) {
              Icon(Icons.Default.Search, contentDescription = "Find similar Shorts", tint = SecondaryCyan, modifier = Modifier.size(18.dp))
            }
          }

          FilledTonalIconButton(
            onClick = { onAiClick(video) },
            shape = RoundedCornerShape(12.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = PrimaryViolet.copy(alpha = 0.2f)),
            modifier = Modifier.testTag("ai_breakdown_${video.videoId}")
          ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "AI Analysis", tint = PrimaryViolet, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
  }
}

@Composable
fun OutlierScoreRing(
  score: Int,
  modifier: Modifier = Modifier,
  sizeDp: Int = 44
) {
  val clamped = score.coerceIn(0, 100)
  val color = when {
    clamped >= 75 -> BadgeGreen
    clamped >= 45 -> BadgeYellow
    else -> AccentRed
  }
  val track = MaterialTheme.colorScheme.surfaceVariant
  Box(
    modifier = modifier.size(sizeDp.dp),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val stroke = 5.dp.toPx()
      val diameter = minOf(size.width, size.height) - stroke
      val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
      val arcSize = Size(diameter, diameter)
      drawArc(
        color = track,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round)
      )
      drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * (clamped / 100f),
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = stroke, cap = StrokeCap.Round)
      )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = clamped.toString(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        lineHeight = 11.sp
      )
    }
  }
}

@Composable
fun ViewsVsMedianBar(
  views: Long,
  median: Long,
  modifier: Modifier = Modifier
) {
  val safeMedian = max(1L, median)
  val maxVal = max(views.toFloat(), safeMedian.toFloat() * 1.2f).coerceAtLeast(1f)
  val viewsRatio = (views.toFloat() / maxVal).coerceIn(0f, 1f)
  val medianRatio = (safeMedian.toFloat() / maxVal).coerceIn(0f, 1f)
  val barTrack = MaterialTheme.colorScheme.surfaceVariant

  Column(modifier = modifier) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text("Viral vs median", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        "${OutlierCalculator.formatCompactNumber(views)} / ${OutlierCalculator.formatCompactNumber(safeMedian)}",
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(10.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(barTrack)
    ) {
      Box(
        modifier = Modifier
          .fillMaxHeight()
          .fillMaxWidth(viewsRatio)
          .clip(RoundedCornerShape(6.dp))
          .background(BadgeGreen.copy(alpha = 0.85f))
      )
      // Median marker line
      Box(
        modifier = Modifier
          .fillMaxHeight()
          .fillMaxWidth(medianRatio)
      ) {
        Box(
          modifier = Modifier
            .align(Alignment.CenterEnd)
            .width(2.dp)
            .fillMaxHeight()
            .background(BadgeYellow)
        )
      }
    }
  }
}
