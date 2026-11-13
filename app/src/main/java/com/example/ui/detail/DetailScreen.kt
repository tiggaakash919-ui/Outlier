package com.example.ui.detail

import android.Manifest
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.TrackedVideoEntity
import com.example.data.local.ViewSnapshotEntity
import com.example.data.local.WatchedChannelEntity
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.domain.VelocityCalculator
import com.example.domain.VelocityState
import com.example.domain.VelocityTrend
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.PrimaryViolet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
  video: OutlierVideo?,
  snapshots: List<ViewSnapshotEntity>,
  tracked: TrackedVideoEntity?,
  watchedChannels: List<WatchedChannelEntity>,
  onBack: () -> Unit,
  onSaveToggle: (OutlierVideo) -> Unit,
  onWatchlistToggle: (OutlierVideo) -> Unit,
  onFollowChannel: (OutlierVideo) -> Unit,
  onUnfollowChannel: (String) -> Unit,
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
    if (pendingFollow && v != null) {
      // Follow regardless of permission result; notification delivery may be gated.
      onFollowChannel(v)
    }
    pendingFollow = false
    if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Toast.makeText(context, "Notification permission denied (alerts may not show)", Toast.LENGTH_SHORT).show()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Velocity & Details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
          }
        }
      )
    },
    modifier = modifier
  ) { innerPadding ->
    if (video == null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.Center
      ) {
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
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            modifier = Modifier
              .fillMaxWidth()
              .height(190.dp)
              .background(Color(0xFF222222), RoundedCornerShape(12.dp))
          )
          Spacer(modifier = Modifier.height(10.dp))
          Text(video.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "${video.channelTitle} • ${OutlierCalculator.formatNumber(video.views)} views • ${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(10.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onSaveToggle(video) }) {
              Icon(
                if (video.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(Modifier.width(6.dp))
              Text(if (video.isSaved) "Saved" else "Save")
            }
            FilledTonalButton(onClick = { onWatchlistToggle(video) }) {
              Icon(
                if (isWatchlisted) Icons.Default.Visibility else Icons.Default.Visibility,
                contentDescription = null,
                tint = if (isWatchlisted) PrimaryViolet else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
              )
              Spacer(Modifier.width(6.dp))
              Text(if (isWatchlisted) "Watchlisted" else "Watchlist")
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/shorts/${video.videoId}"))
              try { context.startActivity(intent) } catch (_: Exception) {
                Toast.makeText(context, "Link open nahi ho paya", Toast.LENGTH_SHORT).show()
              }
            }) {
              Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Open on YouTube")
            }
          }
        }
      }

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
private fun VelocityCard(velocity: VelocityState, snapshots: List<ViewSnapshotEntity>) {
  val color = when (velocity.trend) {
    VelocityTrend.RISING -> BadgeGreen
    VelocityTrend.PEAKED -> BadgeYellow
    VelocityTrend.SLOW -> Color.Gray
  }
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Real velocity", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Text(velocity.label, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      Text(
        "Views gained in last 24h: ${OutlierCalculator.formatNumber(velocity.viewsGained24h)}",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      val accelText = when {
        snapshots.size < 2 -> "Acceleration: data collect ho raha hai"
        velocity.accelerationPerHour > 20 -> "Acceleration: growth speed up ho rahi hai"
        velocity.accelerationPerHour < -20 -> "Acceleration: growth slow ho rahi hai"
        else -> "Acceleration: stable"
      }
      Text(accelText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(
        "Latest: ${OutlierCalculator.formatNumber(velocity.latestViews)} views • " +
          "${OutlierCalculator.formatNumber(velocity.latestLikes)} likes • " +
          "${OutlierCalculator.formatNumber(velocity.latestComments)} comments",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      if (snapshots.isEmpty()) {
        Text(
          "Snapshots har 6 ghante me collect honge (network + quota available hone par).",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun ViewsChartCard(snapshots: List<ViewSnapshotEntity>) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Views over time", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Spacer(Modifier.height(8.dp))
      if (snapshots.size < 2) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            "Chart ke liye kam se kam 2 snapshots chahiye.\nWatchlist/save karke thodi der baad check karo.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      } else {
        ViewsLineChart(
          snapshots = snapshots.sortedBy { it.timestamp },
          modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
        )
        val df = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
        Spacer(Modifier.height(6.dp))
        Text(
          "From ${df.format(Date(snapshots.minOf { it.timestamp }))} to ${df.format(Date(snapshots.maxOf { it.timestamp }))} • ${snapshots.size} points",
          fontSize = 11.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun ViewsLineChart(
  snapshots: List<ViewSnapshotEntity>,
  modifier: Modifier = Modifier
) {
  val lineColor = PrimaryViolet
  val gridColor = Color.Gray.copy(alpha = 0.25f)
  val pointColor = AccentRed

  Canvas(modifier = modifier) {
    if (snapshots.size < 2) return@Canvas
    val minT = snapshots.minOf { it.timestamp }.toFloat()
    val maxT = snapshots.maxOf { it.timestamp }.toFloat().coerceAtLeast(minT + 1f)
    val minV = snapshots.minOf { it.views }.toFloat()
    val maxV = snapshots.maxOf { it.views }.toFloat().coerceAtLeast(minV + 1f)

    val leftPad = 12f
    val rightPad = 12f
    val topPad = 12f
    val bottomPad = 12f
    val w = size.width - leftPad - rightPad
    val h = size.height - topPad - bottomPad

    fun xFor(t: Long): Float = leftPad + ((t.toFloat() - minT) / (maxT - minT)) * w
    fun yFor(v: Long): Float = topPad + h - ((v.toFloat() - minV) / (maxV - minV)) * h

    // grid lines
    for (i in 0..3) {
      val y = topPad + (h / 3f) * i
      drawLine(
        color = gridColor,
        start = Offset(leftPad, y),
        end = Offset(leftPad + w, y),
        strokeWidth = 1f
      )
    }

    val path = Path()
    snapshots.forEachIndexed { index, s ->
      val x = xFor(s.timestamp)
      val y = yFor(s.views)
      if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = lineColor, style = Stroke(width = 4f))

    snapshots.forEach { s ->
      drawCircle(color = pointColor, radius = 5f, center = Offset(xFor(s.timestamp), yFor(s.views)))
    }
  }
}

@Composable
private fun WatchedChannelCard(
  video: OutlierVideo,
  isWatched: Boolean,
  onFollow: () -> Unit,
  onUnfollow: () -> Unit
) {
  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Watch channel", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
      Text(
        "Is channel ko follow karo: daily check hoga aur koi naya Short channel median ke 10x upar gaya to local notification milega.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
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
