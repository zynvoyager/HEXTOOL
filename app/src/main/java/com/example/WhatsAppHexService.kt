package com.example.hextool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * NotificationListener lokal:
 * 1) Silent mode — batalkan notifikasi dari paket / kata kunci yang di-mute
 * 2) WhatsApp HEX — decode pesan HEX dari target dan tampilkan terjemahan
 *
 * Tidak ada data yang dikirim keluar perangkat.
 */
class WhatsAppHexService : NotificationListenerService() {

    companion object {
        private const val CHANNEL_ID = "hex_translation_channel"
        private const val MIN_HEX_CHARS = 6
        private const val DUPLICATE_WINDOW_MS = 5000L
    }

    private var lastKey: String = ""
    private var lastKeyTime: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // ---- 1) Notifikasi Silent (semua app) ----
        if (trySilentCancel(sbn)) return

        // ---- 2) WhatsApp HEX translate ----
        if (sbn.packageName != "com.whatsapp") return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!HexSettings.isNotifEnabled(this)) return

        val extras = sbn.notification.extras
        val sender = extras.getCharSequence("android.title")?.toString().orEmpty()
        val message = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (sender.isBlank() || message.isBlank()) return

        val hexCandidate = message.trim()
        if (hexCandidate.length < MIN_HEX_CHARS) return

        var decodedMessage =
            (HexConverter.decode(
                hexCandidate,
                allowWhitespace = false,
                rejectControlChars = true
            ) as? HexConverter.DecodeResult.Success)?.text

        var member = ""
        if (decodedMessage == null) {
            val sep = hexCandidate.indexOf(": ")
            if (sep > 0) {
                val rest = hexCandidate.substring(sep + 2).trim()
                if (rest.length >= MIN_HEX_CHARS) {
                    decodedMessage =
                        (HexConverter.decode(
                            rest,
                            allowWhitespace = false,
                            rejectControlChars = true
                        ) as? HexConverter.DecodeResult.Success)?.text
                    if (decodedMessage != null) {
                        member = hexCandidate.substring(0, sep).trim()
                    }
                }
            }
        }
        if (decodedMessage == null) return

        val targets = HexLogDatabase.getTargets(this)
        val matchText = "$sender $member"
        val isMatched = targets.any { target ->
            (target.name.isNotBlank() &&
                matchText.contains(target.name, ignoreCase = true)) ||
                (target.identifier.isNotBlank() &&
                    matchText.contains(target.identifier, ignoreCase = true))
        }
        if (!isMatched) return

        val label = if (member.isNotEmpty()) "$sender ($member)" else sender
        val now = System.currentTimeMillis()
        val key = "$label\u0000$decodedMessage"
        if (key == lastKey && now - lastKeyTime < DUPLICATE_WINDOW_MS) return
        lastKey = key
        lastKeyTime = now

        HexLogDatabase.addLog(this, label, decodedMessage)
        showTranslationNotification(label, decodedMessage)
    }

    /**
     * Batalkan notifikasi jika silent aktif dan cocok paket / kata kunci.
     * @return true jika notifikasi dibatalkan (hentikan pemrosesan lain)
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

    private fun showTranslationNotification(
        sender: String,
        translatedText: String
    ) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terjemahan Hex WhatsApp",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HEXTOOL - $sender")
            .setContentText(translatedText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(translatedText)
            )
            .setAutoCancel(true)
            .build()

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notification
        )
    }
}
