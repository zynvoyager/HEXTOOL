package com.example.hextool

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** THEME_PLUGIN_V1: tema unduhan dari GitHub (hanya JSON + gambar, tanpa kode). */
object ThemeManager {

    // GANTI <USER> dengan username GitHub kamu
    const val BASE_URL = "https://raw.githubusercontent.com/zynvoyager/hextool-themes/main/"

    private const val HOST = "raw.githubusercontent.com"
    private const val MAX_JSON = 64 * 1024
    private const val MAX_IMG = 2 * 1024 * 1024
    private val ID_RE = Regex("^[a-z0-9_-]{1,32}$")
    private val FILE_RE = Regex("^[A-Za-z0-9_-]{1,40}\\.(png|jpg|jpeg|webp)$")
    private val COLOR_RE = Regex("^#[0-9A-Fa-f]{6}$")

    val COLOR_KEYS = listOf(
        "bg", "panelBg", "panelBgAlt", "surface",
        "accent", "accentSoft", "accentDim", "accentGlow",
        "textPrimary", "textSecondary", "textMuted"
    )
    val IMAGE_KEYS = listOf("background", "mascot", "splash")

    data class Entry(val id: String, val name: String, val version: Int)

    fun themesDir(ctx: Context) = File(ctx.filesDir, "themes")
    fun themeDir(ctx: Context, id: String) = File(themesDir(ctx), id)

    fun readTheme(ctx: Context, id: String): JSONObject? {
        if (!ID_RE.matches(id)) return null
        return try {
            JSONObject(File(themeDir(ctx, id), "theme.json").readText())
        } catch (e: Exception) { null }
    }

    fun installedVersion(ctx: Context, id: String): Int =
        readTheme(ctx, id)?.optInt("version", 0) ?: -1

    fun installed(ctx: Context): List<Entry> =
        (themesDir(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && ID_RE.matches(it.name) }
            .mapNotNull { d ->
                readTheme(ctx, d.name)?.let { Entry(d.name, it.optString("name", d.name), it.optInt("version", 0)) }
            }

    fun delete(ctx: Context, id: String) {
        if (ID_RE.matches(id)) themeDir(ctx, id).deleteRecursively()
    }

    /** Blocking: panggil dari thread background. */
    fun fetchIndex(): List<Entry> {
        val arr = JSONArray(String(httpGet(BASE_URL + "index.json", MAX_JSON)))
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            if (!ID_RE.matches(id)) continue
            out.add(Entry(id, o.optString("name", id).take(40), o.optInt("version", 1)))
        }
        return out
    }

    /** Blocking. Return null jika sukses, atau pesan error. */
    fun download(ctx: Context, id: String): String? {
        if (!ID_RE.matches(id)) return "ID tema tidak valid"
        val tmp = File(themesDir(ctx), ".tmp_$id")
        return try {
            tmp.deleteRecursively(); tmp.mkdirs()
            val base = "${BASE_URL}themes/$id/"
            val jsonBytes = httpGet(base + "theme.json", MAX_JSON)
            val json = JSONObject(String(jsonBytes))
            if (json.optString("id") != id) return "ID di theme.json tidak cocok"
            val colors = json.getJSONObject("colors")
            for (k in COLOR_KEYS) {
                if (!COLOR_RE.matches(colors.optString(k))) return "Warna '$k' tidak valid"
            }
            val images = json.optJSONObject("images")
            if (images != null) {
                for (k in IMAGE_KEYS) {
                    val f = images.optString(k, "")
                    if (f.isEmpty()) continue
                    if (!FILE_RE.matches(f)) return "Nama file '$f' tidak valid"
                    val bytes = httpGet(base + f, MAX_IMG)
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    if (o.outWidth <= 0 || o.outHeight <= 0) return "Gambar '$f' rusak"
                    File(tmp, f).writeBytes(bytes)
                }
            }
            File(tmp, "theme.json").writeBytes(jsonBytes)
            val dest = themeDir(ctx, id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) return "Gagal menyimpan tema"
            null
        } catch (e: Exception) {
            "Gagal: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun httpGet(url: String, maxBytes: Int): ByteArray {
        val u = URL(url)
        require(u.protocol == "https" && u.host == HOST) { "Host tidak diizinkan" }
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IllegalStateException("File terlalu besar")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }
}
