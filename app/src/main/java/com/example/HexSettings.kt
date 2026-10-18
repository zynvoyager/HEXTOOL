package com.example.hextool

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HexSettings {

    const val PREF_NAME = "HexPanelPrefs"
    const val KEY_PANEL_SIZE = "panel_size_index"
    const val KEY_BUBBLE_DP = "bubble_size_dp"
    const val KEY_NOTIF_ENABLED = "notif_translate_enabled"

    // Notifikasi Silent (lokal)
    private const val KEY_SILENT_ENABLED = "silent_notif_enabled"
    private const val KEY_MUTED_PACKAGES = "silent_muted_packages"
    private const val KEY_MUTE_KEYWORDS = "silent_mute_keywords"

    const val BUBBLE_MIN_DP = 40
    const val BUBBLE_MAX_DP = 120
    const val BUBBLE_DEFAULT_DP = 68

    const val PANEL_COUNT = 3          // 0 = S, 1 = M, 2 = L
    const val PANEL_DEFAULT_INDEX = 0

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getBubbleDp(context: Context): Int =
        prefs(context)
            .getInt(KEY_BUBBLE_DP, BUBBLE_DEFAULT_DP)
            .coerceIn(BUBBLE_MIN_DP, BUBBLE_MAX_DP)

    fun setBubbleDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_BUBBLE_DP, value.coerceIn(BUBBLE_MIN_DP, BUBBLE_MAX_DP))
            .apply()
    }

    fun getPanelIndex(context: Context): Int =
        prefs(context)
            .getInt(KEY_PANEL_SIZE, PANEL_DEFAULT_INDEX)
            .coerceIn(0, PANEL_COUNT - 1)

    fun setPanelIndex(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_PANEL_SIZE, value.coerceIn(0, PANEL_COUNT - 1))
            .apply()
    }

    // Terjemah otomatis dari notifikasi WhatsApp. Default MATI.
    fun isNotifEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_ENABLED, false)

    fun setNotifEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_ENABLED, value).apply()
    }

    // ---------- Notifikasi Silent (lokal) ----------

    fun isSilentEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SILENT_ENABLED, false)

    fun setSilentEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SILENT_ENABLED, value).apply()
    }

    fun getMutedPackages(context: Context): MutableSet<String> {
        val json = prefs(context).getString(KEY_MUTED_PACKAGES, "[]")
        return try {
            val type = object : TypeToken<MutableList<String>>() {}.type
            val list = Gson().fromJson<MutableList<String>>(json, type) ?: mutableListOf()
            list.toMutableSet()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    fun setMutedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_MUTED_PACKAGES, Gson().toJson(packages.toList()))
            .apply()
    }

    fun addMutedPackage(context: Context, pkg: String) {
        val set = getMutedPackages(context)
        set.add(pkg)
        setMutedPackages(context, set)
    }

    fun removeMutedPackage(context: Context, pkg: String) {
        val set = getMutedPackages(context)
        set.remove(pkg)
        setMutedPackages(context, set)
    }

    fun getMuteKeywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_MUTE_KEYWORDS, "") ?: ""
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun setMuteKeywords(context: Context, keywords: List<String>) {
        val cleaned = keywords.map { it.trim() }.filter { it.isNotEmpty() }
        prefs(context).edit()
            .putString(KEY_MUTE_KEYWORDS, cleaned.joinToString(","))
            .apply()
    }
}
