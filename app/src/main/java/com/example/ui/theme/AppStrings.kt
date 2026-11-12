package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.example.R

data class AppStrings(
  val isEnglish: Boolean,
  val searchFieldLabel: String,
  val presetStrategies: String,
  val advancedFilters: String,
  val findOutliersBtn: String,
  val recentSearches: String,
  val resultsDisclaimer: String,
  val resultsEmptyTitle: String,
  val resultsEmptySubtitle: String,
  val loosenFiltersBtn: String,
  val showNonShorts: String,
  val savedTitle: String,
  val savedSearchHint: String,
  val savedEmptyTitle: String,
  val savedEmptySubtitle: String,
  val settingsTitle: String,
  val settingsHelpText: String,
  val modelErrorText: String
) {
  fun formatResultsHeader(context: Context, count: Int, keyword: String): String {
    val resId = if (isEnglish) R.string.results_header_en else R.string.results_header_hi
    return context.getString(resId, count, keyword)
  }
}

val LocalAppStrings = staticCompositionLocalOf<AppStrings> {
  error("No AppStrings provided")
}

@Composable
@ReadOnlyComposable
fun rememberAppStrings(uiLanguage: String): AppStrings {
  val context = LocalContext.current
  val isEn = uiLanguage.equals("English", ignoreCase = true)
  return AppStrings(
    isEnglish = isEn,
    searchFieldLabel = context.getString(if (isEn) R.string.search_label_en else R.string.search_label_hi),
    presetStrategies = context.getString(if (isEn) R.string.preset_strategies_en else R.string.preset_strategies_hi),
    advancedFilters = context.getString(if (isEn) R.string.advanced_filters_en else R.string.advanced_filters_hi),
    findOutliersBtn = context.getString(R.string.find_outliers_btn),
    recentSearches = context.getString(if (isEn) R.string.recent_searches_en else R.string.recent_searches_hi),
    resultsDisclaimer = context.getString(if (isEn) R.string.results_disclaimer_en else R.string.results_disclaimer_hi),
    resultsEmptyTitle = context.getString(if (isEn) R.string.results_empty_title_en else R.string.results_empty_title_hi),
    resultsEmptySubtitle = context.getString(if (isEn) R.string.results_empty_sub_en else R.string.results_empty_sub_hi),
    loosenFiltersBtn = context.getString(if (isEn) R.string.loosen_filters_btn_en else R.string.loosen_filters_btn_hi),
    showNonShorts = context.getString(if (isEn) R.string.show_non_shorts_en else R.string.show_non_shorts_hi),
    savedTitle = context.getString(if (isEn) R.string.saved_title_en else R.string.saved_title_hi),
    savedSearchHint = context.getString(if (isEn) R.string.saved_search_hint_en else R.string.saved_search_hint_hi),
    savedEmptyTitle = context.getString(if (isEn) R.string.saved_empty_title_en else R.string.saved_empty_title_hi),
    savedEmptySubtitle = context.getString(if (isEn) R.string.saved_empty_sub_en else R.string.saved_empty_sub_hi),
    settingsTitle = context.getString(if (isEn) R.string.settings_title_en else R.string.settings_title_hi),
    settingsHelpText = context.getString(R.string.settings_help_text),
    modelErrorText = context.getString(if (isEn) R.string.model_error_en else R.string.model_error_hi)
  )
}
