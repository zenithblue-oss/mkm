package com.ivarna.mkm.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.ivarna.mkm.utils.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppTheme {
    SYSTEM, DYNAMIC, LIGHT, DARK, AMOLED, NORD, NORD_LIGHT, DRACULA, MONOKAI, GRUVBOX, GRUVBOX_LIGHT, SOLARIZED, SOLARIZED_LIGHT, SYNTHWAVE, ONE_LIGHT
}

enum class AppLocale {
    SYSTEM, EN, ZH_CN
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    // Read synchronously so the persisted theme is used on the first frame
    private val _theme = MutableStateFlow(
        runCatching { AppTheme.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(AppTheme.SYSTEM)
    )
    val theme: StateFlow<AppTheme> = _theme.asStateFlow()

    // Same persisted value MainActivity.attachBaseContext applies at startup
    private val _locale = MutableStateFlow(
        runCatching { AppLocale.valueOf(LocaleHelper.getPersistedLocale(application)) }.getOrDefault(AppLocale.SYSTEM)
    )
    val locale: StateFlow<AppLocale> = _locale.asStateFlow()

    fun setTheme(theme: AppTheme) {
        _theme.value = theme
        prefs.edit().putString("theme", theme.name).apply()
    }

    fun setLocale(locale: AppLocale) {
        _locale.value = locale
        // commit(): the caller recreates the activity right after, which re-reads it
        LocaleHelper.persistLocale(getApplication(), locale.name)
    }
}
