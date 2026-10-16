package com.example.hextool

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Root Layout (Hitam Solid)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            setPadding(30, 40, 30, 40)
        }

        val scroll = ScrollView(this).apply {
            addView(mainLayout)
        }

        // Header Title (Terminal Green)
        val title = TextView(this).apply {
            text = "HEX TOOL"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 20)
        }
        mainLayout.addView(title)

        // Tombol Perizinan & Floating
        val btnPerms = Button(this).apply {
            text = "[ IZIN SISTEM & NOTIFIKASI ]"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        mainLayout.addView(btnPerms)

        val btnFloat = Button(this).apply {
            text = "[ AKTIFKAN FLOATING BUBBLE ]"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startService(Intent(this@MainActivity, FloatingWidgetService::class.java))
                    Toast.makeText(this@MainActivity, "Floating Diaktifkan", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
        }
        mainLayout.addView(btnFloat)

        // Bagian Multi-Target Manager
        val targetTitle = TextView(this).apply {
            text = "\n[ MULTI-TARGET / GROUP CONFIG ]"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        mainLayout.addView(targetTitle)

        val inputName = EditText(this).apply {
            hint = "Nama Kontak / Grup"
            setHintTextColor(android.graphics.Color.parseColor("#555555"))
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        mainLayout.addView(inputName)

        val inputId = EditText(this).apply {
            hint = "Nomor / Identifier"
            setHintTextColor(android.graphics.Color.parseColor("#555555"))
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        mainLayout.addView(inputId)

        val btnAddTarget = Button(this).apply {
            text = "[ TAMBAH TARGET ]"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
            setOnClickListener {
                val name = inputName.text.toString()
                val id = inputId.text.toString()
                if (name.isNotEmpty()) {
                    HexLogDatabase.saveTarget(this@MainActivity, TargetModel(name, id))
                    Toast.makeText(this@MainActivity, "Target Ditambahkan!", Toast.LENGTH_SHORT).show()
                    inputName.text.clear()
                    inputId.text.clear()
                }
            }
        }
        mainLayout.addView(btnAddTarget)

        // Bagian Log History
        val logTitle = TextView(this).apply {
            text = "\n[ RIWAYAT LOG PESAN TERJEMAH ]"
            setTextColor(android.graphics.Color.parseColor("#00FF00"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        mainLayout.addView(logTitle)

        val logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainLayout.addView(logContainer)

        // Muat log tersimpan
        val logs = HexLogDatabase.getLogs(this)
        for (log in logs) {
            val tvLog = TextView(this).apply {
                text = "> [${log.timestamp}] ${log.sender}: ${log.message}"
                setTextColor(android.graphics.Color.parseColor("#00FF00"))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setPadding(0, 5, 0, 5)
            }
            logContainer.addView(tvLog)
        }

        // Copyright Footer
        val footer = TextView(this).apply {
            text = "\nCopyright © Zyn / SIK Dev"
            setTextColor(android.graphics.Color.parseColor("#555555"))
            gravity = android.view.Gravity.CENTER
            textSize = 12f
        }
        mainLayout.addView(footer)

        setContentView(scroll)
    }
}