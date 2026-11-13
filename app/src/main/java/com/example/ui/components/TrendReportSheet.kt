package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.TrendIdea
import com.example.domain.TrendReport
import com.example.domain.TrendReportUiState
import com.example.ui.theme.AccentRed
import com.example.ui.theme.BadgeGreen
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendReportSheet(
  uiState: TrendReportUiState,
  selectedCount: Int,
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

  fun shareText(subject: String, text: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, subject)
      putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share as text"))
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = PrimaryViolet)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Text(
            "Trend Report",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            "$selectedCount selected outliers • common patterns + 5 ideas",
            fontSize = 12.sp,
            color = SecondaryCyan
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      when (uiState) {
        is TrendReportUiState.Idle, is TrendReportUiState.Loading -> {
          Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            CircularProgressIndicator(color = PrimaryViolet)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              "Gemini selected outliers ka trend report bana raha hai...",
              fontSize = 14.sp,
              color = SecondaryCyan
            )
          }
        }

        is TrendReportUiState.Error -> {
          Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AccentRed.copy(alpha = 0.14f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = AccentRed)
              Spacer(modifier = Modifier.height(8.dp))
              Text("Trend report fail ho gaya", fontWeight = FontWeight.Bold, color = AccentRed)
              Spacer(modifier = Modifier.height(4.dp))
              Text(uiState.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(modifier = Modifier.height(12.dp))
              Button(onClick = onRegenerate, colors = ButtonDefaults.buttonColors(containerColor = AccentRed)) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Try Again", color = Color.White)
              }
            }
          }
        }

        is TrendReportUiState.Success -> {
          val report = uiState.report
          if (uiState.isCached) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(BadgeGreen.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text("⚡ Loaded from cache", fontSize = 11.sp, color = BadgeGreen, fontWeight = FontWeight.Medium)
            }
            Spacer(modifier = Modifier.height(10.dp))
          }

          Section("📌 Title Formulas", report.patterns.titleFormulas)
          Section("🎯 Topics", report.patterns.topics)
          Section("🎣 Hook Styles", report.patterns.hookStyles)
          Section("⏱️ Durations", report.patterns.durations)

          Spacer(modifier = Modifier.height(8.dp))
          Text("🚀 5 Ready-to-shoot Ideas", fontWeight = FontWeight.Bold, color = PrimaryViolet, fontSize = 15.sp)
          Spacer(modifier = Modifier.height(6.dp))
          report.ideas.forEachIndexed { idx, idea ->
            IdeaCard(idx + 1, idea)
          }

          val fullText = buildTrendReportText(report)
          Spacer(modifier = Modifier.height(14.dp))
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = { copyText("Trend Report", fullText) },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Copy All", fontSize = 12.sp)
            }
            OutlinedButton(
              onClick = { shareText("Trend Report", fullText) },
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Share as text", fontSize = 12.sp)
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

      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

@Composable
private fun Section(title: String, items: List<String>) {
  if (items.isEmpty()) return
  Text(title, fontWeight = FontWeight.Bold, color = SecondaryCyan, fontSize = 14.sp)
  Spacer(modifier = Modifier.height(4.dp))
  items.forEach { item ->
    Card(
      shape = RoundedCornerShape(10.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
      modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
      Text(item, modifier = Modifier.padding(10.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
  }
  Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun IdeaCard(index: Int, idea: TrendIdea) {
  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)),
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("$index. ${idea.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
      if (idea.hook.isNotBlank()) Text("Hook: ${idea.hook}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
      if (idea.concept.isNotBlank()) Text("Concept: ${idea.concept}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
      if (idea.whyItCanWork.isNotBlank()) Text("Why it can work: ${idea.whyItCanWork}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

fun buildTrendReportText(report: TrendReport): String = buildString {
  appendLine("Trend Report (common patterns + 5 ready-to-shoot ideas)")
  fun section(name: String, items: List<String>) {
    if (items.isEmpty()) return@section
    appendLine("\n$name:")
    items.forEach { appendLine("- $it") }
  }
  section("Title Formulas", report.patterns.titleFormulas)
  section("Topics", report.patterns.topics)
  section("Hook Styles", report.patterns.hookStyles)
  section("Durations", report.patterns.durations)
  if (report.ideas.isNotEmpty()) {
    appendLine("\n5 Ready-to-shoot Ideas:")
    report.ideas.forEachIndexed { i, idea ->
      appendLine("\n${i + 1}. ${idea.title}")
      if (idea.hook.isNotBlank()) appendLine("Hook: ${idea.hook}")
      if (idea.concept.isNotBlank()) appendLine("Concept: ${idea.concept}")
      if (idea.whyItCanWork.isNotBlank()) appendLine("Why it can work: ${idea.whyItCanWork}")
    }
  }
}.trim()
