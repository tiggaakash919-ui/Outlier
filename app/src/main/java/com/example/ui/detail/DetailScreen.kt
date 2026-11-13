package com.example.ui.detail

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.TrackedVideoEntity
import com.example.data.local.ViewSnapshotEntity
import com.example.data.local.WatchedChannelEntity
import com.example.domain.AiAnalysis
import com.example.domain.AiUiState
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.VelocityCalculator
import com.example.domain.VelocityState
import com.example.domain.VelocityTrend
import com.example.ui.components.OutlierScoreRing
import com.example.ui.components.ViewsVsMedianBar
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
  video: OutlierVideo?,
  snapshots: List<ViewSnapshotEntity>,
  tracked: TrackedVideoEntity?,
  watchedChannels: List<WatchedChannelEntity>,
  aiUiState: AiUiState = AiUiState.Idle,
  aiLanguage: String = "Hinglish",
  onBack: () -> Unit,
  onSaveToggle: (OutlierVideo) -> Unit,
  onWatchlistToggle: (OutlierVideo) -> Unit,
  onFollowChannel: (OutlierVideo) -> Unit,
  onUnfollowChannel: (String) -> Unit,
  onFindSimilar: (OutlierVideo) -> Unit = {},
  onAnalyzeAi: (OutlierVideo, Boolean) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val velocity = remember(snapshots) { VelocityCalculator.compute(snapshots) }
  val isWatchlisted = tracked?.isWatchlisted == true
  val isChannelWatched = remember(video?.channelId, watchedChannels) {
    watchedChannels.any { it.channelId == video?.channelId }
  }

  var pendingFollow by remember { mutableStateOf(false) }
  val notifPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { granted ->
    val v = video
    if (pendingFollow && v != null) onFollowChannel(v)
    pendingFollow = false
    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Toast.makeText(context, "Notification permission denied (alerts may not show)", Toast.LENGTH_SHORT).show()
    }
  }

  LaunchedEffect(video?.videoId) {
    val v = video ?: return@LaunchedEffect
    // Auto-load embedded AI analysis for the selected video.
    onAnalyzeAi(v, false)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Outlier Detail", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
        }
      )
    },
    modifier = modifier
  ) { innerPadding ->
    if (video == null) {
      Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
        Text("No video selected")
      }
      return@Scaffold
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Embedded thumbnail + header
      Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
          AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .fillMaxWidth()
              .height(220.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(Color(0xFF222222))
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(video.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "${video.channelTitle} • ${OutlierCalculator.formatSubscribers(video.subscriberCount, video.hiddenSubscriberCount)}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlierScoreRing(score = OutlierCalculator.calculateOutlierScore(video))
            Column {
              Text("${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x outlier", fontWeight = FontWeight.Bold, color = OutlierCalculator.getBadgeColor(video.outlierMultiplier))
              Text("${OutlierCalculator.formatCompactNumber(video.views)} views • median ${OutlierCalculator.formatCompactNumber(video.channelMedianViews)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(OutlierCalculator.formatVideoAge(video.publishedAt), fontSize = 12.sp, color = SecondaryCyan)
            }
          }
          Spacer(modifier = Modifier.height(10.dp))
          ViewsVsMedianBar(views = video.views, median = video.channelMedianViews, modifier = Modifier.fillMaxWidth())
        }
      }

      // All stats
      Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("All stats", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
          StatRow("Views", OutlierCalculator.formatCompactNumber(video.views))
          StatRow("Likes", OutlierCalculator.formatCompactNumber(video.likes))
          StatRow("Comments", OutlierCalculator.formatCompactNumber(video.comments))
          StatRow("Duration", "${video.durationSeconds}s")
          StatRow("Published", if (video.publishedAt.length >= 10) video.publishedAt.substring(0, 10) else video.publishedAt)
          StatRow("Age", OutlierCalculator.formatVideoAge(video.publishedAt))
          StatRow("Subscribers", OutlierCalculator.formatSubscribers(video.subscriberCount, video.hiddenSubscriberCount))
          StatRow("Channel median", OutlierCalculator.formatCompactNumber(video.channelMedianViews))
          StatRow("Trimmed mean", OutlierCalculator.formatCompactNumber(video.channelTrimmedMeanViews))
          StatRow("P90", OutlierCalculator.formatCompactNumber(video.channelP90Views))
          StatRow("Multiplier", "${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x")
          StatRow("Views/Sub", String.format(Locale.US, "%.2f", video.viewsPerSub))
          StatRow("Score", OutlierCalculator.calculateOutlierScore(video).toString())
          StatRow("Short status", video.shortStatus.name)
          if (video.tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("Tags: ${video.tags.take(12).joinToString(", ")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }

      // Actions: Open in YouTube (app + fallback), Open channel, Find similar, Save/Watchlist
      Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Actions", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openYouTubeVideo(context, video.videoId) }) {
              Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Open in YouTube")
            }
            OutlinedButton(onClick = { openYouTubeChannel(context, video.channelId) }) {
              Text("Open channel")
            }
            OutlinedButton(onClick = { onFindSimilar(video) }) {
              Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Find similar")
            }
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSaveToggle(video) }) {
              Icon(if (video.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(if (video.isSaved) "Saved" else "Save")
            }
            FilledTonalButton(onClick = { onWatchlistToggle(video) }) {
              Icon(Icons.Default.Visibility, contentDescription = null, tint = if (isWatchlisted) PrimaryViolet else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(if (isWatchlisted) "Watchlisted" else "Watchlist")
            }
          }
        }
      }

      // Embedded AI analysis
      AiAnalysisCard(
        video = video,
        aiUiState = aiUiState,
        aiLanguage = aiLanguage,
        onAnalyze = { force -> onAnalyzeAi(video, force) }
      )

      VelocityCard(velocity = velocity, snapshots = snapshots)
      ViewsChartCard(snapshots = snapshots)

      WatchedChannelCard(
        video = video,
        isWatched = isChannelWatched,
        onFollow = {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingFollow = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
          } else {
            onFollowChannel(video)
          }
        },
        onUnfollow = { onUnfollowChannel(video.channelId) }
      )
    }
  }
}

@Composable
private fun StatRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
  }
}

@Composable
private fun AiAnalysisCard(
  video: OutlierVideo,
  aiUiState: AiUiState,
  aiLanguage: String,
  onAnalyze: (Boolean) -> Unit
) {
  val context = LocalContext.current
  Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryViolet)
          Spacer(Modifier.width(8.dp))
          Text("AI Analysis (Gemini)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Row {
          IconButton(onClick = { onAnalyze(true) }) { Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = SecondaryCyan) }
        }
      }
      Text("Language: $aiLanguage • metadata + thumbnail only", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

      when (aiUiState) {
        is AiUiState.Idle, is AiUiState.Loading -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PrimaryViolet)
            Spacer(Modifier.width(8.dp))
            Text("AI analysis load ho raha hai...", fontSize = 13.sp, color = SecondaryCyan)
          }
        }
        is AiUiState.Error -> {
          Text(aiUiState.message, fontSize = 13.sp, color = AccentRed)
          Button(onClick = { onAnalyze(true) }, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
            Text("Try again", color = Color.White)
          }
        }
        is AiUiState.Success -> {
          val text = buildAnalysisText(video, aiUiState.analysis)
          AnalysisTextBlocks(aiUiState.analysis)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyText(context, "AI Analysis", text) }) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("Copy all", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { shareText(context, "Outlier AI Analysis", text) }) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(Modifier.width(4.dp))
              Text("Share as text", fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AnalysisTextBlocks(a: AiAnalysis) {
  if (a.thumbnailAnalysis.isNotBlank()) SectionText("Thumbnail", a.thumbnailAnalysis)
  if (a.whyViral.isNotEmpty()) SectionList("Why viral", a.whyViral)
  if (a.targetAudience.isNotBlank()) SectionText("Target audience", a.targetAudience)
  if (a.replicabilityScore > 0 || a.replicabilityReason.isNotBlank()) {
    SectionText("Replicability", "Score ${a.replicabilityScore}/10${if (a.replicabilityReason.isNotBlank()) " — ${a.replicabilityReason}" else ""}")
  }
  if (a.hookIdeas.isNotEmpty()) SectionList("Hook ideas", a.hookIdeas)
  if (a.titleIdeas.isNotEmpty()) SectionList("Title ideas", a.titleIdeas)
  val outline = a.scriptOutline
  if (outline.hook0to3s.isNotBlank() || outline.body.isNotBlank() || outline.payoffLoop.isNotBlank()) {
    SectionText("Script outline", "Hook(0-3s): ${outline.hook0to3s}\nBody: ${outline.body}\nPayoff/Loop: ${outline.payoffLoop}")
  }
  if (a.contentIdea.isNotBlank()) SectionText("Content idea", a.contentIdea)
  if (a.bestPostingTip.isNotBlank()) SectionText("Best posting tip", a.bestPostingTip)
}

@Composable
private fun SectionText(title: String, body: String) {
  if (body.isBlank()) return
  Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PrimaryViolet)
  Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SectionList(title: String, items: List<String>) {
  if (items.isEmpty()) return
  Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PrimaryViolet)
  items.forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface) }
}

private fun buildAnalysisText(video: OutlierVideo, a: AiAnalysis): String = buildString {
  appendLine("Outlier Breakdown: ${video.title}")
  appendLine("Channel: ${video.channelTitle} (${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x)")
  if (a.thumbnailAnalysis.isNotBlank()) { appendLine("\nThumbnail Analysis:"); appendLine(a.thumbnailAnalysis) }
  if (a.whyViral.isNotEmpty()) { appendLine("\nWhy Viral:"); a.whyViral.forEach { appendLine("- $it") } }
  if (a.targetAudience.isNotBlank()) { appendLine("\nTarget Audience:"); appendLine(a.targetAudience) }
  if (a.replicabilityScore > 0 || a.replicabilityReason.isNotBlank()) {
    appendLine("\nReplicability: ${a.replicabilityScore}/10")
    if (a.replicabilityReason.isNotBlank()) appendLine(a.replicabilityReason)
  }
  if (a.hookIdeas.isNotEmpty()) { appendLine("\nHook Ideas:"); a.hookIdeas.forEach { appendLine("- $it") } }
  if (a.titleIdeas.isNotEmpty()) { appendLine("\nTitle Ideas:"); a.titleIdeas.forEach { appendLine("- $it") } }
  appendLine("\nScript Outline:")
  appendLine("Hook (0-3s): ${a.scriptOutline.hook0to3s}")
  appendLine("Body: ${a.scriptOutline.body}")
  appendLine("Payoff/Loop: ${a.scriptOutline.payoffLoop}")
  if (a.contentIdea.isNotBlank()) { appendLine("\nContent Idea:"); appendLine(a.contentIdea) }
  if (a.bestPostingTip.isNotBlank()) { appendLine("\nBest Posting Tip:"); appendLine(a.bestPostingTip) }
}.trim()

private fun copyText(context: Context, label: String, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
  Toast.makeText(context, "✓ Copied $label", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, subject: String, text: String) {
  val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, text)
  }
  context.startActivity(Intent.createChooser(intent, "Share as text"))
}

private fun openYouTubeVideo(context: Context, videoId: String) {
  val appUri = Uri.parse("vnd.youtube:$videoId")
  val webUri = Uri.parse("https://www.youtube.com/shorts/$videoId")
  try {
    val appIntent = Intent(Intent.ACTION_VIEW, appUri).apply { setPackage("com.google.android.youtube") }
    context.startActivity(appIntent)
  } catch (_: Exception) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, appUri))
    } catch (_: Exception) {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
      } catch (_: Exception) {
        Toast.makeText(context, "YouTube link open nahi ho paya", Toast.LENGTH_SHORT).show()
      }
    }
  }
}

private fun openYouTubeChannel(context: Context, channelId: String) {
  val webUri = Uri.parse("https://www.youtube.com/channel/$channelId")
  val appUri = Uri.parse("vnd.youtube://channel/$channelId")
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, appUri).apply { setPackage("com.google.android.youtube") })
  } catch (_: Exception) {
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    } catch (_: Exception) {
      Toast.makeText(context, "Channel open nahi ho paya", Toast.LENGTH_SHORT).show()
    }
  }
}

@Composable
private fun VelocityCard(velocity: VelocityState, snapshots: List<ViewSnapshotEntity>) {
  val color = when (velocity.trend) {
    VelocityTrend.RISING -> BadgeGreen
    VelocityTrend.PEAKED -> BadgeYellow
    VelocityTrend.SLOW -> Color.Gray
  }
  Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Real velocity", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Text(velocity.label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Text("Views gained in last 24h: ${OutlierCalculator.formatCompactNumber(velocity.viewsGained24h)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      val accelText = when {
        snapshots.size < 2 -> "Acceleration: data collect ho raha hai"
        velocity.accelerationPerHour > 20 -> "Acceleration: growth speed up ho rahi hai"
        velocity.accelerationPerHour < -20 -> "Acceleration: growth slow ho rahi hai"
        else -> "Acceleration: stable"
      }
      Text(accelText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        "Latest: ${OutlierCalculator.formatCompactNumber(velocity.latestViews)} views • ${OutlierCalculator.formatCompactNumber(velocity.latestLikes)} likes • ${OutlierCalculator.formatCompactNumber(velocity.latestComments)} comments",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (snapshots.isEmpty()) {
        Text("Snapshots har 6 ghante me collect honge (network + quota available hone par).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun ViewsChartCard(snapshots: List<ViewSnapshotEntity>) {
  Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Views over time", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Spacer(Modifier.height(8.dp))
      if (snapshots.size < 2) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
          Text("Chart ke liye kam se kam 2 snapshots chahiye.\nWatchlist/save karke thodi der baad check karo.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      } else {
        ViewsLineChart(snapshots = snapshots.sortedBy { it.timestamp }, modifier = Modifier.fillMaxWidth().height(200.dp))
        val df = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
        Spacer(Modifier.height(6.dp))
        Text("From ${df.format(Date(snapshots.minOf { it.timestamp }))} to ${df.format(Date(snapshots.maxOf { it.timestamp }))} • ${snapshots.size} points", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

@Composable
private fun ViewsLineChart(snapshots: List<ViewSnapshotEntity>, modifier: Modifier = Modifier) {
  val lineColor = PrimaryViolet
  val gridColor = Color.Gray.copy(alpha = 0.25f)
  val pointColor = AccentRed
  Canvas(modifier = modifier) {
    if (snapshots.size < 2) return@Canvas
    val minT = snapshots.minOf { it.timestamp }.toFloat()
    val maxT = snapshots.maxOf { it.timestamp }.toFloat().coerceAtLeast(minT + 1f)
    val minV = snapshots.minOf { it.views }.toFloat()
    val maxV = snapshots.maxOf { it.views }.toFloat().coerceAtLeast(minV + 1f)
    val leftPad = 12f; val rightPad = 12f; val topPad = 12f; val bottomPad = 12f
    val w = size.width - leftPad - rightPad
    val h = size.height - topPad - bottomPad
    fun xFor(t: Long): Float = leftPad + ((t.toFloat() - minT) / (maxT - minT)) * w
    fun yFor(v: Long): Float = topPad + h - ((v.toFloat() - minV) / (maxV - minV)) * h
    for (i in 0..3) {
      val y = topPad + (h / 3f) * i
      drawLine(color = gridColor, start = Offset(leftPad, y), end = Offset(leftPad + w, y), strokeWidth = 1f)
    }
    val path = Path()
    snapshots.forEachIndexed { index, s ->
      val x = xFor(s.timestamp); val y = yFor(s.views)
      if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
    snapshots.forEach { s -> drawCircle(color = pointColor, radius = 5f, center = Offset(xFor(s.timestamp), yFor(s.views))) }
  }
}

@Composable
private fun WatchedChannelCard(
  video: OutlierVideo,
  isWatched: Boolean,
  onFollow: () -> Unit,
  onUnfollow: () -> Unit
) {
  Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Watch channel", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Text(
        "Is channel ko follow karo: daily check hoga aur koi naya Short channel median ke 10x upar gaya to local notification milega.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (isWatched) {
        FilledTonalButton(onClick = onUnfollow) {
          Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Following ${video.channelTitle} (Unfollow)")
        }
      } else {
        Button(onClick = onFollow) {
          Icon(Icons.Default.NotificationsNone, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Follow ${video.channelTitle}")
        }
      }
    }
  }
}
