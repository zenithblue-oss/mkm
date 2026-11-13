package com.ivarna.mkm.service

import android.content.Context
import com.ivarna.mkm.shell.SysfsTuningExecutor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export/import of saved boot + tuning settings. Only whitelisted prefs files,
 * keys and value types survive import; every string that can later reach a
 * shell must be a safe sysfs path or a safe sysfs value.
 */
object TuningProfile {
    const val FORMAT = "mkm-tuning-profile"
    const val VERSION = 1
    const val MEMORY_BOOT_PREFS = "mkm_memory_boot"

    val FILES = listOf(BootSettingsManager.PREFS_FILE, GameBoostAutoBoost.PREFS, MEMORY_BOOT_PREFS)

    private val BOOT_BOOLEAN = Regex("boot_(cpu|gpu|ram|storage|game_boost)_enabled")
    private val BOOT_STRING = Regex(
        "boot_cpu_(governor|max_freq|min_freq)_\\d{1,3}|boot_gpu_(governor|max_freq|min_freq|target_freq)|" +
            "boot_ram_devfreq_(path|governor|freq)|boot_storage_ufs_(path|governor|min_freq|max_freq)"
    )
    private val GENERIC_KEY = Regex("[a-z][a-z0-9_]{0,63}")

    data class Summary(val counts: Map<String, Int>, val rejected: Int) {
        val total: Int get() = counts.values.sum()
    }

    /** Validated content, ready to apply. */
    data class Profile(val files: Map<String, Map<String, Any>>, val rejected: Int) {
        fun summary() = Summary(files.mapValues { it.value.size }, rejected)
    }

    fun isAllowed(file: String, key: String, value: Any): Boolean = when (file) {
        BootSettingsManager.PREFS_FILE -> when {
            BOOT_BOOLEAN.matches(key) -> value is Boolean
            BOOT_STRING.matches(key) -> value is String && safeString(value)
            else -> false // safe-mode guard keys and anything unknown
        }
        GameBoostAutoBoost.PREFS -> when (key) {
            GameBoostAutoBoost.KEY_ENABLED -> value is Boolean
            GameBoostAutoBoost.KEY_PACKAGES -> value is Set<*> && value.all { it is String && GameBoostAutoBoost.isValidPackage(it) }
            else -> false
        }
        // Owned by the RAM/storage agent: accept simple keys with primitive, shell-safe values.
        MEMORY_BOOT_PREFS -> GENERIC_KEY.matches(key) && when (value) {
            is Boolean, is Int, is Long, is Float -> true
            is String -> safeString(value)
            else -> false
        }
        else -> false
    }

    private fun safeString(value: String): Boolean =
        value.isEmpty() || SysfsTuningExecutor.isSafeValue(value) || SysfsTuningExecutor.isSafeSysfsPath(value)

    /** Filters raw prefs maps down to whitelisted entries. */
    fun validate(raw: Map<String, Map<String, Any?>>): Profile {
        var rejected = 0
        val files = FILES.associateWith { file ->
            raw[file].orEmpty().mapNotNull { (key, value) ->
                if (value != null && isAllowed(file, key, value)) key to value else { rejected++; null }
            }.toMap()
        }.filterValues { it.isNotEmpty() }
        rejected += raw.keys.filterNot { it in FILES }.sumOf { raw[it].orEmpty().size }
        return Profile(files, rejected)
    }

    fun read(context: Context): Profile = validate(
        FILES.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE).all }
    )

    fun apply(context: Context, profile: Profile) {
        profile.files.forEach { (file, entries) ->
            val editor = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            entries.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value as Set<String>)
                }
            }
            editor.commit()
        }
    }

    // ---- JSON (thin org.json layer; typed so import cannot coerce types) ----

    fun toJson(profile: Profile): String {
        val files = JSONObject()
        profile.files.forEach { (file, entries) ->
            val obj = JSONObject()
            entries.forEach { (key, value) ->
                val (type, encoded) = when (value) {
                    is Boolean -> "bool" to value
                    is Int -> "int" to value
                    is Long -> "long" to value
                    is Float -> "float" to value.toDouble()
                    is Set<*> -> "set" to JSONArray(value.map { it.toString() })
                    else -> "string" to value.toString()
                }
                obj.put(key, JSONObject().put("t", type).put("v", encoded))
            }
            files.put(file, obj)
        }
        return JSONObject().put("format", FORMAT).put("version", VERSION).put("files", files).toString(2)
    }

    /** @throws IllegalArgumentException for a file that is not an MKM profile. */
    fun fromJson(text: String): Profile {
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "Not an MKM tuning profile" }
        require(root.optInt("version") in 1..VERSION) { "Unsupported profile version" }
        val files = root.getJSONObject("files")
        val raw = mutableMapOf<String, Map<String, Any?>>()
        files.keys().forEach { file ->
            val obj = files.getJSONObject(file)
            raw[file] = obj.keys().asSequence().associateWith { key -> decode(obj.optJSONObject(key)) }
        }
        return validate(raw)
    }

    private fun decode(entry: JSONObject?): Any? {
        entry ?: return null
        val v = entry.opt("v")
        return when (entry.optString("t")) {
            "bool" -> v as? Boolean
            "int" -> (v as? Number)?.takeIf { it.toDouble() % 1.0 == 0.0 }?.toInt()
            "long" -> (v as? Number)?.toLong()
            "float" -> (v as? Number)?.toFloat()
            "string" -> v as? String
            "set" -> (v as? JSONArray)?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            else -> null
        }
    }
}
