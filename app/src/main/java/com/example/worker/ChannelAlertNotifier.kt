package com.example.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object ChannelAlertNotifier {
  private const val CHANNEL_ID = "channel_outliers"
  private const val CHANNEL_NAME = "Channel outliers"

  fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        CHANNEL_NAME,
        NotificationManager.IMPORTANCE_DEFAULT
      ).apply {
        description = "Alerts when watched channels post breakout Shorts"
      }
      manager.createNotificationChannel(channel)
    }
  }

  fun notifyNewOutlier(
    context: Context,
    channelId: String,
    channelTitle: String,
    videoId: String,
    videoTitle: String,
    multiplier: Double
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    ensureChannel(context)

    val intent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra("open_video_id", videoId)
    }
    val pendingIntent = PendingIntent.getActivity(
      context,
      videoId.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val multText = if (multiplier >= 10.0) String.format("%.0fx", multiplier) else String.format("%.1fx", multiplier)
    val text = "$channelTitle ka naya Short apne median se $multText upar gaya"

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("New channel outlier")
      .setContentText(text)
      .setStyle(
        NotificationCompat.BigTextStyle().bigText("$text\n$videoTitle")
      )
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()

    try {
      NotificationManagerCompat.from(context).notify(videoId.hashCode(), notification)
    } catch (_: SecurityException) {
      // Permission may be revoked at runtime.
    }
  }
}
