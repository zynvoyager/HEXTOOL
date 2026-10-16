package com.example.hextool

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TargetModel(val name: String, val identifier: String)
data class LogModel(val timestamp: String, val sender: String, val message: String)

object HexLogDatabase {
    private const val PREF_NAME = "HexToolPrefs"
    private const val KEY_TARGETS = "saved_targets"
    private const val KEY_LOGS = "saved_logs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getTargets(context: Context): MutableList<TargetModel> {
        val json = getPrefs(context).getString(KEY_TARGETS, "[]")
        val type = object : TypeToken<MutableList<TargetModel>>() {}.type
        return Gson().fromJson(json, type) ?: mutableListOf()
    }

    fun saveTarget(context: Context, target: TargetModel) {
        val targets = getTargets(context)
        targets.add(target)
        val json = Gson().toJson(targets)
        getPrefs(context).edit().putString(KEY_TARGETS, json).apply()
    }

    fun getLogs(context: Context): MutableList<LogModel> {
        val json = getPrefs(context).getString(KEY_LOGS, "[]")
        val type = object : TypeToken<MutableList<LogModel>>() {}.type
        return Gson().fromJson(json, type) ?: mutableListOf()
    }

    fun addLog(context: Context, sender: String, message: String) {
        val logs = getLogs(context)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, LogModel(time, sender, message)) // Urutan terbaru di atas
        val json = Gson().toJson(logs)
        getPrefs(context).edit().putString(KEY_LOGS, json).apply()
    }
}