package com.example.hextool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NotificationListener lokal:
 * 1) HEX Notifikasi - decode pesan HEX dari target pada aplikasi yang dipantau
 *    (diatur di tab Settings > "Aplikasi Dipantau", default: WhatsApp)
 * 2) Silent mode - batalkan notifikasi dari paket / kata kunci yang di-mute
 *
 * Tidak ada data yang dikirim keluar perangkat.
 * TAHAP_NOTIF_FIX_V1 / NOTIF_LOG_APPS_V1
 */
class WhatsAppHexService : NotificationListenerService() {

    companion object {
        private const val CHANNEL_ID = "hex_translation_channel"
        private const val MIN_HEX_CHARS = 6
        private const val DUPLICATE_WINDOW_MS = 10000L

        const val KEY_DEBUG = "notif_debug"

        // NOTIF_LOG_APPS_V1: daftar paket diambil dari HexSettings (diatur user di tab Settings),
        // bukan hardcoded lagi -- supaya aplikasi yang dipantau bisa dipilih, tidak cuma WhatsApp.
        private fun watchedPackages(context: Context): Set<String> =
            HexSettings.getNotifPackages(context)

        // true selama sistem benar-benar menyambungkan listener ke proses ini.
        @Volatile
        var connected: Boolean = false

        // Catatan singkat terakhir (tanpa isi pesan) agar mudah mendiagnosis.
        fun saveDebug(context: Context, msg: String) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            HexSettings.prefs(context).edit().putString(KEY_DEBUG, "$t $msg").apply()
        }

        fun postTranslation(context: Context, sender: String, translatedText: String) {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                saveDebug(context, "Izin notifikasi HEXTOOL ditolak (cek Info Aplikasi)")
                return
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Terjemahan Hex WhatsApp",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("HEXTOOL - $sender")
                .setContentText(translatedText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(translatedText))
                .setAutoCancel(true)
                .build()

            nm.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    private class Candidate(val text: String, val member: String)

    private var senderHint: String = ""
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var lastKey: String = ""
    private var lastKeyTime: Long = 0L

    // ---------------------------------------------------------------- lifecycle

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        saveDebug(this, "Layanan tersambung")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        // Minta sistem menyambungkan ulang (kasus proses dimatikan / habis update).
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, WhatsAppHexService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // 1) Terjemahkan dulu, supaya Silent Mode tidak ikut menghalangi.
        try {
            // AES_KEYS_V1: dekripsi (PBKDF2) cukup berat, jangan di main thread.
            worker.execute {
                try {
                    handleTranslate(sbn)
                } catch (e: Exception) {
                    saveDebug(this@WhatsAppHexService, "Error: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            saveDebug(this, "Error: ${e.javaClass.simpleName}")
        }

        // 2) Baru jalankan Silent Mode (semua app).
        trySilentCancel(sbn)
    }

    // ---------------------------------------------------------------- terjemah

    private fun decodeHex(s: String): String? {
        if (s.startsWith("3302")) { // AES_NOTIF_V1
            val keys = AesCipher.candidateKeys(this, senderHint) // AES_KEYS_V1
            if (keys.isEmpty()) return null
            return AesCipher.tryDecryptWithKeys(s.substring(4), keys)?.first
        }
        return (HexConverter.decode(
            s,
            allowWhitespace = false,
            rejectControlChars = true
        ) as? HexConverter.DecodeResult.Success)?.text
    }

    @Suppress("DEPRECATION")
    private fun candidateTexts(extras: Bundle): List<Candidate> {
        val out = ArrayList<Candidate>()

        // Pesan terbaru dari MessagingStyle (paling akurat di WhatsApp modern).
        try {
            val msgs = extras.getParcelableArray("android.messages")
            if (msgs != null && msgs.isNotEmpty()) {
                val last = msgs[msgs.size - 1]
                if (last is Bundle) {
                    val txt = last.getCharSequence("text")?.toString().orEmpty()
                    val who = last.getCharSequence("sender")?.toString().orEmpty()
                    if (txt.isNotBlank()) out.add(Candidate(txt.trim(), who.trim()))
                }
            }
        } catch (_: Exception) {
        }

        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (text.isNotBlank()) out.add(Candidate(text.trim(), ""))

        val big = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        if (big.isNotBlank()) out.add(Candidate(big.trim(), ""))

        val lines = extras.getCharSequenceArray("android.textLines")
        if (lines != null && lines.isNotEmpty()) {
            val l = lines[lines.size - 1].toString()
            if (l.isNotBlank()) out.add(Candidate(l.trim(), ""))
        }

        return out.filter { it.text.length >= MIN_HEX_CHARS }
    }

    private fun handleTranslate(sbn: StatusBarNotification) {
        if (sbn.packageName !in watchedPackages(this)) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val sender = extras.getCharSequence("android.title")?.toString().orEmpty()
        if (sender.isBlank()) return
        senderHint = sender

        if (!HexSettings.isNotifEnabled(this)) {
            saveDebug(this, "$sender: pencatatan notifikasi MATI di Settings")
            return
        }

        // NOTIF_LOG_ALL_V1: catat SEMUA notifikasi dari aplikasi yang dipantau,
        // tidak lagi disyaratkan HEX/AES, tidak lagi disyaratkan cocok Target.
        // Kalau isinya kebetulan HEX/AES valid, yang dicatat hasil dekodenya;
        // kalau bukan, dicatat apa adanya.
        val candidates = candidateTexts(extras)
        if (candidates.isEmpty()) {
            saveDebug(this, "$sender: tidak ada teks notifikasi")
            return
        }

        val raw = candidates.first().text
        var label = sender
        var finalText = raw

        val sep = raw.indexOf(": ")
        if (sep > 0) {
            val maybeMember = raw.substring(0, sep).trim()
            val rest = raw.substring(sep + 2).trim()
            val decodedRest = decodeHex(rest)
            if (decodedRest != null) {
                finalText = decodedRest
                if (maybeMember.isNotEmpty()) label = "$sender ($maybeMember)"
            } else {
                val decodedWhole = decodeHex(raw)
                if (decodedWhole != null) finalText = decodedWhole
            }
        } else {
            val decodedWhole = decodeHex(raw)
            if (decodedWhole != null) finalText = decodedWhole
        }

        val now = System.currentTimeMillis()
        val key = "$label\u0000$finalText"
        if (key == lastKey && now - lastKeyTime < DUPLICATE_WINDOW_MS) return
        lastKey = key
        lastKeyTime = now

        HexLogDatabase.addLog(this, label, finalText)
        saveDebug(this, "Dicatat dari \"$label\"")
    }

    // ---------------------------------------------------------------- silent mode

    /**
     * Batalkan notifikasi jika silent aktif dan cocok paket / kata kunci.
     * @return true jika notifikasi dibatalkan
     */
    private fun trySilentCancel(sbn: StatusBarNotification): Boolean {
        if (!HexSettings.isSilentEnabled(this)) return false

        // Jangan batalkan notifikasi dari HEXTOOL sendiri
        if (sbn.packageName == packageName) return false

        val muted = HexSettings.getMutedPackages(this)
        val pkgMatch = sbn.packageName != null && muted.contains(sbn.packageName)

        val keywords = HexSettings.getMuteKeywords(this)
        var keywordMatch = false
        if (keywords.isNotEmpty()) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            val big = extras.getCharSequence("android.bigText")?.toString().orEmpty()
            val hay = "$title $text $big"
            keywordMatch = keywords.any { kw ->
                hay.contains(kw, ignoreCase = true)
            }
        }

        if (pkgMatch || keywordMatch) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
                // Beberapa OEM membatasi cancel; abaikan saja
            }
            return true
        }
        return false
    }
}