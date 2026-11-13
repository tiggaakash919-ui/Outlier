package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import android.content.Intent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.AiAnalysis
import com.example.domain.AiUiState
import com.example.domain.OutlierCalculator
import com.example.domain.OutlierVideo
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.BadgeYellow
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBottomSheet(
  video: OutlierVideo,
  aiUiState: AiUiState,
  aiLanguage: String,
  onRegenerate: () -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  fun copyText(label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "✓ Copied $label", Toast.LENGTH_SHORT).show()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = Modifier.testTag("ai_modal_bottom_sheet")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
      // Header Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = PrimaryViolet,
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = "Gemini Growth Breakdown",
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "Language: $aiLanguage · ${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x Outlier",
              fontSize = 12.sp,
              color = SecondaryCyan
            )
          }
        }

        // Regenerate button
        OutlinedButton(
          onClick = onRegenerate,
          enabled = aiUiState !is AiUiState.Loading,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("ai_regenerate_button")
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Regenerate",
            modifier = Modifier.size(16.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("Regenerate", fontSize = 12.sp)
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Video Context Pill
      Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = video.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "${video.channelTitle} (~${OutlierCalculator.formatCompactNumber(video.subscriberCount)} subs) · ${OutlierCalculator.formatCompactNumber(video.views)} views vs ${OutlierCalculator.formatCompactNumber(video.channelMedianViews)} median",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      when (aiUiState) {
        is AiUiState.Idle, is AiUiState.Loading -> {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            CircularProgressIndicator(
              color = PrimaryViolet,
              modifier = Modifier.size(40.dp),
              strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
              text = "Gemini outlier metadata analyze kar raha hai...",
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              color = SecondaryCyan
            )
          }
        }

        is AiUiState.Error -> {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.14f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("ai_error_card")
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = AccentRed,
                modifier = Modifier.size(32.dp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "Settings me model naam check karo",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AccentRed
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = aiUiState.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Spacer(modifier = Modifier.height(12.dp))
              Button(
                onClick = onRegenerate,
                colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                shape = RoundedCornerShape(12.dp)
              ) {
                Text("Try Again", color = Color.White)
              }
            }
          }
        }

        is AiUiState.Success -> {
          val analysis: AiAnalysis = aiUiState.analysis

          if (aiUiState.isCached) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(BadgeGreen.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = "⚡ Loaded from cache (0 tokens)",
                fontSize = 11.sp,
                color = BadgeGreen,
                fontWeight = FontWeight.Medium
              )
            }
            Spacer(modifier = Modifier.height(10.dp))
          }

          // 1. Why Viral (3 points)
          AiSectionHeader(title = "🔥 Why It Went Viral", color = BadgeGreen)
          analysis.whyViral.forEachIndexed { idx, point ->
            AiCopyableItemCard(
              text = "${idx + 1}. $point",
              rawCopyText = point,
              label = "Viral reason #${idx + 1}",
              onCopy = ::copyText
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          // 2. Hook Ideas (3 punchy opening lines)
          AiSectionHeader(title = "🎣 3 Punchy Hook Ideas", color = SecondaryCyan)
          analysis.hookIdeas.forEachIndexed { idx, hook ->
            AiCopyableItemCard(
              text = "\"$hook\"",
              rawCopyText = hook,
              label = "Hook #${idx + 1}",
              onCopy = ::copyText
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          // 3. Title Ideas (3 titles, max 60 chars)
          AiSectionHeader(title = "📌 3 Viral Title Ideas (≤60 chars)", color = BadgeYellow)
          analysis.titleIdeas.forEachIndexed { idx, title ->
            AiCopyableItemCard(
              text = title,
              rawCopyText = title,
              label = "Title #${idx + 1}",
              onCopy = ::copyText
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          // 4. Content Idea with 3-step outline
          AiSectionHeader(title = "🎬 Similar Short Idea & 3-Step Outline", color = PrimaryViolet)
          AiCopyableItemCard(
            text = analysis.contentIdea,
            rawCopyText = analysis.contentIdea,
            label = "Content Outline",
            onCopy = ::copyText
          )

          Spacer(modifier = Modifier.height(16.dp))

          // Copy All, Share & Close Row
          val fullReport = buildString {
            appendLine("Outlier Breakdown: ${video.title}")
            appendLine("Channel: ${video.channelTitle} (${OutlierCalculator.formatMultiplier(video.outlierMultiplier)}x Outlier)")
            appendLine("\nWhy Viral:")
            analysis.whyViral.forEach { appendLine("- $it") }
            appendLine("\nHook Ideas:")
            analysis.hookIdeas.forEach { appendLine("- $it") }
            appendLine("\nTitle Ideas:")
            analysis.titleIdeas.forEach { appendLine("- $it") }
            appendLine("\nContent Idea & Outline:")
            appendLine(analysis.contentIdea)
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { copyText("Full AI Breakdown", fullReport) },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Copy All", fontSize = 12.sp)
            }

            OutlinedButton(
              onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_SUBJECT, "Outlier Breakdown: ${video.title}")
                  putExtra(Intent.EXTRA_TEXT, fullReport)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share AI Breakdown"))
              },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Share", fontSize = 12.sp)
            }

            Button(
              onClick = onDismiss,
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = PrimaryViolet),
              modifier = Modifier.weight(1f)
            ) {
              Text("Done", color = Color.White, fontSize = 12.sp)
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(28.dp))
    }
  }
}

@Composable
private fun AiSectionHeader(title: String, color: Color) {
  Text(
    text = title,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    color = color,
    modifier = Modifier.padding(vertical = 6.dp)
  )
}

@Composable
private fun AiCopyableItemCard(
  text: String,
  rawCopyText: String,
  label: String,
  onCopy: (String, String) -> Unit
) {
  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
    ),
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f)
      )
      IconButton(
        onClick = { onCopy(label, rawCopyText) },
        modifier = Modifier.size(48.dp) // 48dp minimum touch target
      ) {
        Icon(
          imageVector = Icons.Default.ContentCopy,
          contentDescription = "Copy $label",
          tint = SecondaryCyan,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}
