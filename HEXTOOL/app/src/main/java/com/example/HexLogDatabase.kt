package com.example.hextool

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// TARGETS_V2: "disabled" (bukan "enabled") supaya data lama tanpa field ini otomatis dianggap aktif.
data class TargetModel(
    val name: String,
    val identifier: String,
    val aesKey: String? = null, // AES_TARGET_KEY_V1
    val disabled: Boolean = false
) {
    fun isEnabled(): Boolean = !disabled

    // Cocok dengan nama pengirim/grup (sebagian teks, tanpa peduli huruf besar-kecil).
    fun matches(text: String): Boolean =
        (name.isNotBlank() && text.contains(name, ignoreCase = true)) ||
            (identifier.isNotBlank() && text.contains(identifier, ignoreCase = true))
}
data class LogModel(val timestamp: String, val sender: String, val message: String)

object HexLogDatabase {
    private const val PREF_NAME = "HexToolPrefs"
    private const val KEY_TARGETS = "saved_targets"
    private const val KEY_LOGS = "saved_logs"

    const val MAX_LOGS = 200

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ---------------- Target ----------------

    fun getTargets(context: Context): MutableList<TargetModel> {
        val json = getPrefs(context).getString(KEY_TARGETS, "[]")
        val type = object : TypeToken<MutableList<TargetModel>>() {}.type
        return try {
            Gson().fromJson<MutableList<TargetModel>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            // Data rusak: jangan crash, mulai dari daftar kosong.
            mutableListOf()
        }
    }

    private fun writeTargets(context: Context, targets: List<TargetModel>) {
        getPrefs(context).edit()
            .putString(KEY_TARGETS, Gson().toJson(targets))
            .apply()
    }

    @Synchronized
    fun saveTarget(context: Context, target: TargetModel) {
        val targets = getTargets(context)
        targets.add(target)
        writeTargets(context, targets)
    }

    @Synchronized
    fun updateTargetAt(context: Context, index: Int, target: TargetModel) {
        val targets = getTargets(context)
        if (index in targets.indices) {
            targets[index] = target
            writeTargets(context, targets)
        }
    }

    @Synchronized
    fun removeTargetAt(context: Context, index: Int) {
        val targets = getTargets(context)
        if (index in targets.indices) {
            targets.removeAt(index)
            writeTargets(context, targets)
        }
    }

    @Synchronized
    fun setTargetEnabled(context: Context, index: Int, enabled: Boolean) {
        val targets = getTargets(context)
        if (index in targets.indices) {
            targets[index] = targets[index].copy(disabled = !enabled)
            writeTargets(context, targets)
        }
    }

    // TARGETS_V2: true jika pengirim cocok dengan target yang dimatikan
    // dan tidak cocok dengan target aktif mana pun -> notifikasinya diabaikan.
    fun isSenderIgnored(context: Context, vararg names: String): Boolean {
        val targets = getTargets(context)
        if (targets.none { !it.isEnabled() }) return false
        val hay = names.filter { it.isNotBlank() }
        if (hay.isEmpty()) return false
        val matchesEnabled = targets.any { t -> t.isEnabled() && hay.any { t.matches(it) } }
        if (matchesEnabled) return false
        return targets.any { t -> !t.isEnabled() && hay.any { t.matches(it) } }
    }

    // ---------------- Log ----------------

    fun getLogs(context: Context): MutableList<LogModel> {
        val json = getPrefs(context).getString(KEY_LOGS, "[]")
        val type = object : TypeToken<MutableList<LogModel>>() {}.type
        return try {
            Gson().fromJson<MutableList<LogModel>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun addLog(context: Context, sender: String, message: String) {
        val logs = getLogs(context)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logs.add(0, LogModel(time, sender, message)) // Urutan terbaru di atas

        // Batasi jumlah log: buang yang paling lama.
        val trimmed = if (logs.size > MAX_LOGS) logs.subList(0, MAX_LOGS).toList() else logs

        getPrefs(context).edit()
            .putString(KEY_LOGS, Gson().toJson(trimmed))
            .apply()
    }

    @Synchronized
    fun clearLogs(context: Context) {
        getPrefs(context).edit().putString(KEY_LOGS, "[]").apply()
    }
}