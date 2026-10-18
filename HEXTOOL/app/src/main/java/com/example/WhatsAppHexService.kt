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
 * 1) WhatsApp HEX - decode pesan HEX dari target dan tampilkan terjemahan
 * 2) Silent mode - batalkan notifikasi dari paket / kata kunci yang di-mute
 *
 * Tidak ada data yang dikirim keluar perangkat.
 * TAHAP_NOTIF_FIX_V1
 */
class WhatsAppHexService : NotificationListenerService() {

    companion object {
        private const val CHANNEL_ID = "hex_translation_channel"
        private const val MIN_HEX_CHARS = 6
        private const val DUPLICATE_WINDOW_MS = 10000L

        const val KEY_DEBUG = "notif_debug"

        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

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

    private var lastKey: String = ""
    private var lastKeyTime: Long = 0L

    // ---------------------------------------------------------------- lifecycle

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
            handleTranslate(sbn)
        } catch (e: Exception) {
            saveDebug(this, "Error: ${e.javaClass.simpleName}")
        }

        // 2) Baru jalankan Silent Mode (semua app).
        trySilentCancel(sbn)
    }

    // ---------------------------------------------------------------- terjemah

    private fun decodeHex(s: String): String? =
        (HexConverter.decode(
            s,
            allowWhitespace = false,
            rejectControlChars = true
        ) as? HexConverter.DecodeResult.Success)?.text

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
        if (sbn.packageName !in WA_PACKAGES) return

        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val sender = extras.getCharSequence("android.title")?.toString().orEmpty()
        if (sender.isBlank()) return

        if (!HexSettings.isNotifEnabled(this)) {
            saveDebug(this, "WA dari \"$sender\": terjemah notifikasi MATI di Settings")
            return
        }

        var decoded: String? = null
        var memberName = ""

        for (c in candidateTexts(extras)) {
            var text = decodeHex(c.text)
            var member = c.member

            // Pesan grup dari teks notifikasi berbentuk "Nama Pengirim: isi pesan".
            if (text == null) {
                val sep = c.text.indexOf(": ")
                if (sep > 0) {
                    val rest = c.text.substring(sep + 2).trim()
                    if (rest.length >= MIN_HEX_CHARS) {
                        text = decodeHex(rest)
                        if (text != null) member = c.text.substring(0, sep).trim()
                    }
                }
            }

            if (text != null) {
                decoded = text
                memberName = member
                break
            }
        }

        if (decoded == null) {
            saveDebug(this, "WA dari \"$sender\": teks bukan HEX valid")
            return
        }

        val targets = HexLogDatabase.getTargets(this)
        val matchText = "$sender $memberName"
        val isMatched = targets.any { target ->
            (target.name.isNotBlank() &&
                matchText.contains(target.name, ignoreCase = true)) ||
                (target.identifier.isNotBlank() &&
                    matchText.contains(target.identifier, ignoreCase = true))
        }
        if (!isMatched) {
            saveDebug(this, "WA dari \"$sender\": HEX valid tapi tidak cocok dengan target")
            return
        }

        val label = if (memberName.isNotEmpty()) "$sender ($memberName)" else sender
        val now = System.currentTimeMillis()
        val key = "$label\u0000$decoded"
        if (key == lastKey && now - lastKeyTime < DUPLICATE_WINDOW_MS) return
        lastKey = key
        lastKeyTime = now

        HexLogDatabase.addLog(this, label, decoded)
        postTranslation(this, label, decoded)
        saveDebug(this, "OK: pesan dari \"$label\" diterjemahkan")
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