package com.ivarna.mkm.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.edit

/** Per-app auto boost: config plus the pure foreground-package parsing/decision logic. */
object GameBoostAutoBoost {
    /** Separate from the session snapshot prefs so config can be backed up and exported. */
    const val PREFS = "game_boost_config"
    const val KEY_ENABLED = "auto_boost_enabled"
    const val KEY_PACKAGES = "auto_boost_packages"
    const val FOREGROUND_COMMAND = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val RESUMED = Regex("(?:topResumedActivity|mResumedActivity)\\S*\\s*[=:]?\\s*ActivityRecord\\{\\S+ u\\d+ ([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/")

    data class App(val packageName: String, val label: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }

    fun packages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().filter(::isValidPackage).toSet()

    fun setPackages(context: Context, packages: Set<String>) =
        prefs(context).edit { putStringSet(KEY_PACKAGES, packages.filter(::isValidPackage).toSet()) }

    fun isValidPackage(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)

    /** First resumed activity's package from `dumpsys activity activities` output, or null. */
    fun parseForegroundPackage(dumpsys: String): String? =
        dumpsys.lineSequence().firstNotNullOfOrNull { RESUMED.find(it)?.groupValues?.get(1) }
            ?.takeIf(::isValidPackage)

    enum class Action { ENABLE, DISABLE, NONE }

    /**
     * Edge-triggered: boosts only on entering a selected game (so a manual
     * disable inside the game sticks) and only restores sessions it started.
     */
    fun decide(
        foreground: String?,
        previousForeground: String?,
        selected: Set<String>,
        boostOff: Boolean,
        autoOwned: Boolean
    ): Action {
        val inGame = foreground != null && foreground in selected
        return when {
            inGame && foreground != previousForeground && boostOff -> Action.ENABLE
            !inGame && autoOwned && !boostOff -> Action.DISABLE
            else -> Action.NONE
        }
    }

    /** Launcher apps (minus MKM itself), sorted by label. */
    fun launcherApps(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return infos.map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.packageName != context.packageName && isValidPackage(it.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
