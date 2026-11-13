package com.outlierradar.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.outlierradar.app.R
import com.outlierradar.app.data.repository.KeyRepository
import com.outlierradar.app.data.repository.KeyValidationResult
import com.outlierradar.app.ui.theme.AccentRed
import com.outlierradar.app.ui.theme.DarkBackground
import com.outlierradar.app.ui.theme.DarkSurface
import com.outlierradar.app.ui.theme.LinkBlue
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
  keyRepository: KeyRepository,
  onConnected: () -> Unit,
  initialErrorMessage: String? = null,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val coroutineScope = rememberCoroutineScope()

  var apiKeyInput by remember { mutableStateOf("") }
  var isPasswordVisible by remember { mutableStateOf(false) }
  var isLoading by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf(initialErrorMessage) }
  var isGuideExpanded by remember { mutableStateOf(false) }

  fun executeValidation() {
    val trimmed = apiKeyInput.trim()
    val formatError = keyRepository.validateFormat(trimmed)
    if (formatError != null) {
      errorMessage = formatError
      return
    }

    isLoading = true
    errorMessage = null

    coroutineScope.launch {
      when (val result = keyRepository.testAndSaveKey(trimmed)) {
        is KeyValidationResult.Success -> {
          isLoading = false
          Toast.makeText(context, "✓ Key connected", Toast.LENGTH_SHORT).show()
          onConnected()
        }
        is KeyValidationResult.QuotaExceededSaved -> {
          isLoading = false
          Toast.makeText(context, "Key sahi hai, par aaj ka quota khatam. Kal reset hoga.", Toast.LENGTH_LONG).show()
          onConnected()
        }
        is KeyValidationResult.LimitReached -> {
          isLoading = false
          errorMessage = "Maximum 5 API keys allowed"
        }
        is KeyValidationResult.Error -> {
          isLoading = false
          errorMessage = result.userMessage
        }
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .imePadding()
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Spacer(modifier = Modifier.height(30.dp))

      // Satellite Dish Icon / Illustration
      Image(
        painter = painterResource(id = R.drawable.satellite_dish),
        contentDescription = "Outlier Radar Satellite Dish",
        modifier = Modifier
          .size(130.dp)
          .testTag("onboarding_satellite_icon"),
        contentScale = ContentScale.Fit
      )

      Spacer(modifier = Modifier.height(20.dp))

      // Title
      Text(
        text = "Outlier Radar",
        fontSize = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        color = Color.White,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Subtitle
      Text(
        text = "Viral Growth Engine",
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = AccentRed,
        textAlign = TextAlign.Center
      )

      Spacer(modifier = Modifier.height(28.dp))

      // Description
      Text(
        text = "Paste your YouTube Data API v3 key to start finding outlier videos. Your key stays on this device.",
        fontSize = 15.sp,
        color = Color(0xFF9CA3AF),
        textAlign = TextAlign.Center,
        lineHeight = 22.sp,
        modifier = Modifier.padding(horizontal = 12.dp)
      )

      Spacer(modifier = Modifier.height(36.dp))

      // YouTube API Key Input Field
      OutlinedTextField(
        value = apiKeyInput,
        onValueChange = {
          apiKeyInput = it.trim()
          if (errorMessage != null) errorMessage = null
        },
        label = { Text("YouTube API Key") },
        singleLine = true,
        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Password,
          imeAction = ImeAction.Done,
          autoCorrectEnabled = false
        ),
        keyboardActions = KeyboardActions(
          onDone = { executeValidation() }
        ),
        isError = errorMessage != null,
        trailingIcon = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 4.dp)
          ) {
            // Paste from clipboard button
            IconButton(
              onClick = {
                val clipText = clipboardManager.getText()?.text
                if (!clipText.isNullOrBlank()) {
                  apiKeyInput = clipText.trim()
                  if (errorMessage != null) errorMessage = null
                }
              },
              modifier = Modifier.testTag("paste_key_button")
            ) {
              Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = "Paste from clipboard",
                tint = Color(0xFF9CA3AF)
              )
            }

            // Eye toggle button
            IconButton(
              onClick = { isPasswordVisible = !isPasswordVisible },
              modifier = Modifier.testTag("toggle_password_visibility")
            ) {
              Icon(
                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (isPasswordVisible) "Hide key" else "Show key",
                tint = Color(0xFF9CA3AF)
              )
            }
          }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = DarkSurface,
          unfocusedContainerColor = DarkSurface,
          focusedBorderColor = AccentRed,
          unfocusedBorderColor = Color(0xFF2D2D3D),
          focusedLabelColor = AccentRed,
          unfocusedLabelColor = Color(0xFF9CA3AF),
          focusedTextColor = Color.White,
          unfocusedTextColor = Color.White
        ),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("api_key_input_field")
      )

      // Inline Error Message
      if (errorMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = errorMessage ?: "",
          color = AccentRed,
          fontSize = 13.sp,
          textAlign = TextAlign.Start,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("api_key_error_text")
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // 🚀 Save & Start Button
      Button(
        onClick = { executeValidation() },
        enabled = !isLoading,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = AccentRed,
          disabledContainerColor = AccentRed.copy(alpha = 0.5f)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .testTag("save_and_start_button")
      ) {
        if (isLoading) {
          CircularProgressIndicator(
            color = Color.White,
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.5.dp
          )
        } else {
          Text(
            text = "🚀 Save & Start",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Privacy Note
      Text(
        text = "Aapki key sirf is phone me encrypted save hoti hai. Kisi server pe nahi jaati.",
        fontSize = 12.sp,
        color = Color(0xFF6B7280),
        textAlign = TextAlign.Center,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 8.dp)
      )

      Spacer(modifier = Modifier.height(24.dp))

      // 🔗 Help Link: Get a free API key (Google Cloud Console)
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
          .clickable {
            val intent = Intent(
              Intent.ACTION_VIEW,
              Uri.parse("https://console.cloud.google.com/apis/library/youtube.googleapis.com")
            )
            context.startActivity(intent)
          }
          .padding(8.dp)
          .testTag("get_free_key_link")
      ) {
        Text(
          text = "🔗 Get a free API key (Google Cloud Console)",
          color = LinkBlue,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Expandable "Key kaise banaye? (2 min)" section
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("how_to_create_key_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
          containerColor = DarkSurface.copy(alpha = 0.8f)
        )
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { isGuideExpanded = !isGuideExpanded },
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.HelpOutline,
              contentDescription = null,
              tint = LinkBlue,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Key kaise banaye? (2 min)",
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold,
              color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
              imageVector = if (isGuideExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = if (isGuideExpanded) "Collapse" else "Expand",
              tint = Color(0xFF9CA3AF)
            )
          }

          AnimatedVisibility(visible = isGuideExpanded) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
            ) {
              val steps = listOf(
                "1) Google Cloud Console kholo aur ek project banao",
                "2) \"YouTube Data API v3\" search karke Enable dabao",
                "3) APIs & Services → Credentials → Create Credentials → API key",
                "4) Key pe click karo → API restrictions → sirf \"YouTube Data API v3\" select karo → Save",
                "5) Key copy karke yaha paste karo"
              )

              steps.forEach { step ->
                Text(
                  text = step,
                  fontSize = 13.sp,
                  color = Color(0xFFD1D5DB),
                  lineHeight = 18.sp,
                  modifier = Modifier.padding(vertical = 4.dp)
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}
