package com.example.hextool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class WhatsAppHexService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        if (sbn?.packageName == "com.whatsapp") {
            val extras = sbn.notification.extras
            val sender = extras.getString("android.title") ?: ""
            val message = extras.getString("android.text") ?: ""

            // Cek apakah pengirim cocok dengan daftar multi-target yang disimpan
            val targets = HexLogDatabase.getTargets(this)
            val isMatched = targets.any { 
                sender.contains(it.name, ignoreCase = true) || sender.contains(it.identifier, ignoreCase = true) 
            }

            if (isMatched && isHexCode(message)) {
                val decodedMessage = decodeHexToPlaintext(message)
                
                // Simpan ke Log History
                HexLogDatabase.addLog(this, sender, decodedMessage)
                
                // Munculkan notifikasi lokal
                showTranslationNotification(sender, decodedMessage)
            }
        }
    }

    private fun isHexCode(input: String): Boolean {
        val cleanInput = input.trim()
        val hexRegex = Regex("^[0-9a-fA-F]+$")
        return cleanInput.length % 2 == 0 && hexRegex.matches(cleanInput) && cleanInput.length > 4
    }

    private fun decodeHexToPlaintext(hexStr: String): String {
        return try {
            val output = StringBuilder()
            for (i in hexStr.indices step 2) {
                val str = hexStr.substring(i, i + 2)
                output.append(str.toInt(16).toChar())
            }
            output.toString()
        } catch (e: Exception) {
            "Gagal menerjemahkan hex code."
        }
    }

    private fun showTranslationNotification(sender: String, translatedText: String) {
        val channelId = "hex_translation_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Terjemahan Hex WhatsApp",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hex Tool - $sender")
            .setContentText(translatedText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}