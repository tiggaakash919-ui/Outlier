package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.FilterOptions
import com.example.domain.OutlierCalculator
import com.example.domain.PresetType
import com.example.domain.Presets
import com.example.domain.QuotaStatus
import com.example.ui.components.QuotaBadge
import com.example.ui.theme.AccentRed
import com.example.ui.theme.PrimaryViolet
import com.example.ui.theme.SecondaryCyan

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  filters: FilterOptions,
  quotaStatus: QuotaStatus,
  isSearching: Boolean,
  progressMessage: String?,
  recentSearches: List<String>,
  onFiltersChange: (FilterOptions) -> Unit,
  onSearchClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showAdvancedFilters by remember { mutableStateOf(false) }

  val nicheSuggestions = listOf("Tech", "Finance", "Motivation", "Facts", "Comedy", "Gaming", "Cooking")

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
  ) {
    // Header Row with App Title and Quota Badge
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "Outlier Radar",
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
        Text(
          text = "Viral Shorts Discovery",
          fontSize = 12.sp,
          color = SecondaryCyan
        )
      }
      QuotaBadge(quotaStatus = quotaStatus)
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Search Input Field
    OutlinedTextField(
      value = filters.keyword,
      onValueChange = { onFiltersChange(filters.copy(keyword = it)) },
      label = { Text("Niche ya keyword daalo (e.g. motivation, cooking)") },
      placeholder = { Text("Keywords...") },
      singleLine = true,
      leadingIcon = {
        Icon(
          imageVector = Icons.Default.Search,
          contentDescription = null,
          tint = PrimaryViolet
        )
      },
      trailingIcon = {
        if (filters.keyword.isNotEmpty()) {
          IconButton(onClick = { onFiltersChange(filters.copy(keyword = "")) }) {
            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
          }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { if (filters.keyword.isNotBlank() && !isSearching) onSearchClick() }),
      shape = RoundedCornerShape(16.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedBorderColor = PrimaryViolet,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline
      ),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("home_search_input")
    )

    Spacer(modifier = Modifier.height(10.dp))

    // Niche Filter Chips
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      nicheSuggestions.forEach { niche ->
        FilterChip(
          selected = filters.keyword.equals(niche, ignoreCase = true),
          onClick = { onFiltersChange(filters.copy(keyword = niche)) },
          label = { Text(niche, fontSize = 12.sp) },
          colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = PrimaryViolet.copy(alpha = 0.25f),
            selectedLabelColor = Color.White
          ),
          modifier = Modifier.testTag("chip_niche_$niche")
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Presets Row
    Text(
      text = "Preset Strategies",
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      PresetChip(
        title = "🎯 Tiny gems",
        subtitle = "≤1K subs, 100x",
        onClick = { onFiltersChange(Presets.applyPreset(PresetType.TINY_GEMS, filters.keyword)) },
        modifier = Modifier.weight(1f)
      )
      PresetChip(
        title = "⚖️ Balanced",
        subtitle = "≤10K subs, 50x",
        onClick = { onFiltersChange(Presets.applyPreset(PresetType.BALANCED, filters.keyword)) },
        modifier = Modifier.weight(1f)
      )
      PresetChip(
        title = "🌊 Loose",
        subtitle = "≤100K subs, 20x",
        onClick = { onFiltersChange(Presets.applyPreset(PresetType.LOOSE, filters.keyword)) },
        modifier = Modifier.weight(1f)
      )
    }

    Spacer(modifier = Modifier.height(18.dp))

    // Advanced Filters Expandable Card
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .testTag("advanced_filters_card"),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
      )
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable { showAdvancedFilters = !showAdvancedFilters },
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Tune,
            contentDescription = null,
            tint = PrimaryViolet,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "Advanced Filters",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
          )
          Spacer(modifier = Modifier.weight(1f))
          Icon(
            imageVector = if (showAdvancedFilters) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        AnimatedVisibility(visible = showAdvancedFilters) {
          Column(modifier = Modifier.padding(top = 14.dp)) {
            // Subscriber RangeSlider (0 to 100K)
            Text(
              text = "Channel Subscribers: ${OutlierCalculator.formatCompactNumber(filters.minSubs)} - ${OutlierCalculator.formatCompactNumber(filters.maxSubs)}",
              fontSize = 13.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
            RangeSlider(
              value = (filters.minSubs.toFloat() / 1000f)..(filters.maxSubs.toFloat() / 1000f),
              onValueChange = { range ->
                onFiltersChange(
                  filters.copy(
                    minSubs = (range.start * 1000).toLong(),
                    maxSubs = (range.endInclusive * 1000).toLong()
                  )
                )
              },
              valueRange = 0f..100f,
              steps = 19,
              colors = SliderDefaults.colors(
                thumbColor = PrimaryViolet,
                activeTrackColor = PrimaryViolet
              ),
              modifier = Modifier.testTag("slider_subscribers")
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Min Viral Views [100K, 500K, 1M, 5M]
            Text(
              text = "Min Viral Views: ${OutlierCalculator.formatCompactNumber(filters.minViralViews)}",
              fontSize = 13.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf(100_000L to "100K", 500_000L to "500K", 1_000_000L to "1M", 5_000_000L to "5M").forEach { (v, label) ->
                ElevatedFilterChip(
                  selected = filters.minViralViews == v,
                  onClick = { onFiltersChange(filters.copy(minViralViews = v)) },
                  label = { Text(label, fontSize = 12.sp) }
                )
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Min Multiplier [20x, 50x, 100x, 500x]
            Text(
              text = "Min Outlier Multiplier: ${filters.minMultiplier}x",
              fontSize = 13.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf(20, 50, 100, 500).forEach { mul ->
                ElevatedFilterChip(
                  selected = filters.minMultiplier == mul,
                  onClick = { onFiltersChange(filters.copy(minMultiplier = mul)) },
                  label = { Text("${mul}x", fontSize = 12.sp) }
                )
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Recency [7d, 30d, 90d, 6mo, 1y, any]
            Text(
              text = "Uploaded Within (Recency)",
              fontSize = 13.sp,
              color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf("7d", "30d", "90d", "6mo", "1y", "any").forEach { rec ->
                ElevatedFilterChip(
                  selected = filters.recency.equals(rec, ignoreCase = true),
                  onClick = { onFiltersChange(filters.copy(recency = rec)) },
                  label = { Text(rec.uppercase(), fontSize = 12.sp) }
                )
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Region [IN, US, Any] & Language [Hindi, English, Any]
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Region", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  listOf("IN", "US", "Any").forEach { reg ->
                    FilterChip(
                      selected = filters.regionCode.equals(reg, ignoreCase = true),
                      onClick = { onFiltersChange(filters.copy(regionCode = reg)) },
                      label = { Text(reg, fontSize = 11.sp) }
                    )
                  }
                }
              }

              Column(modifier = Modifier.weight(1f)) {
                Text("Language", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  listOf("Hindi", "English", "Any").forEach { lang ->
                    FilterChip(
                      selected = filters.language.equals(lang, ignoreCase = true),
                      onClick = { onFiltersChange(filters.copy(language = lang)) },
                      label = { Text(lang.take(4), fontSize = 11.sp) }
                    )
                  }
                }
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Max Duration [60s, 180s] & Pages per search [1, 2, 3]
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Max Duration", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  listOf(60L to "60s", 180L to "180s").forEach { (dur, l) ->
                    FilterChip(
                      selected = filters.maxDurationSeconds == dur,
                      onClick = { onFiltersChange(filters.copy(maxDurationSeconds = dur)) },
                      label = { Text(l, fontSize = 11.sp) }
                    )
                  }
                }
              }

              Column(modifier = Modifier.weight(1f)) {
                Text("Pages (Search Calls)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  listOf(1, 2, 3).forEach { pg ->
                    FilterChip(
                      selected = filters.pagesPerSearch == pg,
                      onClick = { onFiltersChange(filters.copy(pagesPerSearch = pg)) },
                      label = { Text("$pg", fontSize = 11.sp) }
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Find Outliers 🔥 Button
    Button(
      onClick = { onSearchClick() },
      enabled = filters.keyword.isNotBlank() && !isSearching,
      shape = RoundedCornerShape(24.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = AccentRed,
        disabledContainerColor = AccentRed.copy(alpha = 0.4f)
      ),
      modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)
        .testTag("find_outliers_button")
    ) {
      if (isSearching) {
        CircularProgressIndicator(
          color = Color.White,
          modifier = Modifier.size(24.dp),
          strokeWidth = 2.5.dp
        )
      } else {
        Text(
          text = "Find Outliers 🔥",
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold,
          color = Color.White
        )
      }
    }

    // Live Progress Message
    if (progressMessage != null) {
      Spacer(modifier = Modifier.height(12.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(SecondaryCyan.copy(alpha = 0.12f))
          .padding(horizontal = 14.dp, vertical = 10.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(
            color = SecondaryCyan,
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = progressMessage,
            fontSize = 13.sp,
            color = SecondaryCyan,
            fontWeight = FontWeight.Medium
          )
        }
      }
    }

    // Recent Searches
    if (recentSearches.isNotEmpty()) {
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text = "Recent Searches",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(8.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recentSearches.forEach { query ->
          SuggestionChip(
            onClick = {
              onFiltersChange(filters.copy(keyword = query))
              onSearchClick()
            },
            label = { Text(query, fontSize = 12.sp) },
            icon = {
              Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
              )
            }
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(30.dp))
  }
}

@Composable
private fun PresetChip(
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable { onClick() },
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    )
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
