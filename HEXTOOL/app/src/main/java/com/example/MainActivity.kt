package com.example.hextool

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "extra_tab" // 0 Home, 1 Target, 2 Log, 3 Pengaturan
    }

    private lateinit var targetContainer: LinearLayout
    private lateinit var logContainer: LinearLayout
    private lateinit var logFilterRow: LinearLayout
    private var currentLogFilter = "Semua"
    private lateinit var searchInput: EditText
    private var allTargets: List<TargetModel> = emptyList()

    // Status cards (Home)
    private lateinit var cardSistem: LinearLayout
    private lateinit var cardTarget: LinearLayout
    private lateinit var cardLog: LinearLayout
    private lateinit var cardFloating: LinearLayout
    private lateinit var cardIzin: LinearLayout

    private val navButtons = ArrayList<TextView>()
    private val navIcons = ArrayList<TextView>()
    private val navItems = ArrayList<LinearLayout>()  // NAVCLICK_FIX_V1: item yang sungguh punya OnClickListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HexTheme.load(this) // THEME_TOGGLE_V1

        window.statusBarColor = HexTheme.bg
        window.navigationBarColor = HexTheme.bg

        val root = FrameLayout(this).apply {
            setBackgroundColor(HexTheme.bg)
        }

        // Background topo (tipis)
        root.addView(
            ImageView(this).apply {
                HexTheme.setImage(this, "background", R.drawable.bg_topo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.18f
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val navBarHeight = dp(64)
        val contentArea = FrameLayout(this)

        // ---- TAB CONTENTS ----
        val tabHome = buildHomeTab()
        val tabTargets = buildTargetsTab()
        val tabLogs = buildLogsTab()
        val tabSettings = buildSettingsTab()
        val tabs = listOf(tabHome, tabTargets, tabLogs, tabSettings)

        tabs.forEach { tab ->
            contentArea.addView(
                tab,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            tab.visibility = View.GONE
        }
        tabHome.visibility = View.VISIBLE

        root.addView(
            contentArea,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = navBarHeight }
        )

        root.addView(
            buildBottomNav { index -> switchTab(tabs, index) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                navBarHeight,
                Gravity.BOTTOM
            )
        )

        setContentView(root)

        refreshTargets()
        refreshLogs()
        refreshStatusCards()
        requestNotificationPermission()
        openRequestedTab(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshTargets()
        refreshLogs()
        if (pendingBubbleStart) {
            pendingBubbleStart = false
            if (Settings.canDrawOverlays(this)) activateFloatingBubble()
        }
        refreshStatusCards()
        statusHandler.removeCallbacks(statusTicker)
        statusHandler.postDelayed(statusTicker, 2000L)
    }

    // Dibuka dari menu bubble (Target / Log / Pengaturan) -> langsung ke tab yang dimaksud.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTab(intent)
    }

    private fun openRequestedTab(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_TAB, -1) ?: -1
        if (tab in 0..3) navItems.getOrNull(tab)?.performClick()
        intent?.removeExtra(EXTRA_TAB)
    }

    // =========================================================
    //  HOME / DASHBOARD
    // =========================================================

    private fun buildHomeTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        header.addView(
            ImageView(this).apply {
                HexTheme.setImage(this, "mascot", R.drawable.cat_hex)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
        )
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = "HEXTOOL"
            setTextColor(HexTheme.textPrimary)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        titleCol.addView(TextView(this).apply {
            text = "Simple Tools. Greater Control."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(header)

        // Status grid (2x2 + 1)
        val statusTitle = sectionTitle("Status Sistem")
        col.addView(statusTitle)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cardSistem = statusCard("●  Sistem", "Memuat...", HexTheme.accent)
        cardTarget = statusCard("◎  Target", "0 Aktif", HexTheme.accent)
        row1.addView(cardSistem, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row1.addView(cardTarget, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        col.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        cardLog = statusCard("☰  Log", "0 Baru", HexTheme.accent)
        cardFloating = statusCard("◉  Floating", "Nonaktif", HexTheme.textMuted)
        row2.addView(cardLog, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row2.addView(cardFloating, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        col.addView(row2)

        cardIzin = statusCard("🛡  Izin Sistem", "Periksa status", HexTheme.warning)
        cardIzin.setPadding(dp(16), dp(14), dp(16), dp(14))
        col.addView(cardIzin, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // Fitur utama
        col.addView(sectionTitle("Fitur Utama").apply {
            setPadding(0, dp(28), 0, dp(10))
        })

        col.addView(featureRow("◎", "Multi-Target Manager", "Kelola kontak & grup") {
            // switch to targets tab via bottom nav simulation
            navItems.getOrNull(1)?.performClick()
        })
        col.addView(featureRow("☰", "Log History", "Lihat riwayat pesan") {
            navItems.getOrNull(2)?.performClick()
        })
        col.addView(featureRow("◉", "Floating Bubble", "Aktifkan bubble mengambang") {
            activateFloatingBubble()
        })
        col.addView(featureRow("🔗", "Link / QR Checker", "Cek tautan secara lokal (tanpa server)") {
            showLinkCheckerDialog()
        })
        col.addView(featureRow("🔕", "Notifikasi Silent", "Senyapkan app / kata kunci tertentu") {
            navItems.getOrNull(3)?.performClick()
        })

        // Quick actions
        col.addView(sectionTitle("Aksi Cepat").apply {
            setPadding(0, dp(28), 0, dp(10))
        })

        col.addView(primaryButton("Cek Link dari Clipboard") {
            showLinkCheckerDialog(fromClipboard = true)
        })
        col.addView(secondaryButton("Cek Status Izin") {
            openPermissionSettings()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })
        col.addView(secondaryButton("Aktifkan Floating Bubble") {
            activateFloatingBubble()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        // Footer
        col.addView(TextView(this).apply {
            text = "© Zyn / Sik dev"
            setTextColor(HexTheme.textMuted)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(8))
        })

        scroll.addView(col)
        return scroll
    }





    private fun refreshStatusCards() {
        ensureListenerBound()
        updateNotifStatusText()
        val targets = HexLogDatabase.getTargets(this)
        val logs = HexLogDatabase.getLogs(this)
        val overlayOk = Settings.canDrawOverlays(this)
        val listenerOk = isListenerReady()
        val a11yOk = HexClipboardService.instance != null
        val bubbleOn = FloatingWidgetService.running

        val activeTargets = targets.count { it.isEnabled() }
        setCardAccent(cardTarget, if (activeTargets == 0) HexTheme.warning else HexTheme.success,
            "$activeTargets Aktif",
            if (targets.isEmpty()) "Belum ada target" else "dari ${targets.size} target")
        setCardAccent(cardLog, HexTheme.accent, "${logs.size} Entri", "Riwayat tersimpan")

        when {
            bubbleOn -> setCardAccent(cardFloating, HexTheme.success, "Aktif", "Bubble tampil")
            overlayOk -> setCardAccent(cardFloating, HexTheme.textMuted, "Nonaktif", "Siap diaktifkan")
            else -> setCardAccent(cardFloating, HexTheme.warning, "Nonaktif", "Butuh izin overlay")
        }

        val izinText = "Overlay " + (if (overlayOk) "✓" else "✗") +
            "  Notif " + (if (listenerOk) "✓" else "✗") +
            "  Aksesibilitas " + (if (a11yOk) "✓" else "✗")
        val izinAll = overlayOk && listenerOk && a11yOk
        setCardAccent(cardIzin, if (izinAll) HexTheme.success else HexTheme.warning,
            if (izinAll) "Lengkap" else "Belum lengkap", izinText)

        // Sistem: sehat bila pembaca notifikasi tersambung (fitur inti).
        if (listenerOk) {
            setCardAccent(cardSistem, HexTheme.success, "Berjalan normal", "Listener tersambung")
        } else {
            setCardAccent(cardSistem, HexTheme.warning, "Perlu perhatian", "Listener notifikasi belum tersambung")
        }
    }

    // Segarkan status tiap 2 dtk selama layar terbuka (bubble / izin bisa berubah di luar app).
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatusCards()
            statusHandler.postDelayed(this, 2000L)
        }
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusTicker)
        super.onPause()
    }

    // ---------- TAHAP_NOTIF_FIX_V1: status & penyambungan ulang listener ----------

    private var notifStatusView: TextView? = null
    private var lastRebindMs = 0L

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun isListenerReady(): Boolean =
        isListenerEnabled() && WhatsAppHexService.connected

    // Akses sudah dicentang tapi service belum tersambung (mis. habis install ulang).
    private fun ensureListenerBound() {
        if (!isListenerEnabled() || WhatsAppHexService.connected) return
        val now = System.currentTimeMillis()
        if (now - lastRebindMs < 10000L) return
        lastRebindMs = now
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, WhatsAppHexService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun updateNotifStatusText() {
        val v = notifStatusView ?: return
        val state = when {
            !isListenerEnabled() -> "akses notifikasi MATI (aktifkan HEXTOOL di pengaturan)"
            !WhatsAppHexService.connected -> "akses aktif tapi layanan belum tersambung (matikan lalu nyalakan lagi aksesnya)"
            else -> "layanan tersambung"
        }
        val last = HexSettings.prefs(this).getString(WhatsAppHexService.KEY_DEBUG, "") ?: ""
        v.text = "Status: " + state + (if (last.isNotEmpty()) "\nTerakhir: " + last else "")
    }

    private fun setCardSubtitle(card: LinearLayout, text: String) {
        (card.findViewWithTag<TextView>("subtitle"))?.text = text
    }



    // =========================================================
    //  TARGETS
    // =========================================================

    private fun buildTargetsTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        // Header
        col.addView(pageHeader("Multi-Target Manager", "ketuk Aktif/Mati untuk menyalakan, ketuk nama untuk mengubah"))

        // Search
        searchInput = EditText(this).apply {
            hint = "Cari target..."
            setHintTextColor(HexTheme.textMuted)
            setTextColor(HexTheme.textPrimary)
            textSize = 14f
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterTargets(s?.toString().orEmpty())
                }
            })
        }
        col.addView(searchInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        // List
        targetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(targetContainer)

        // Form tambah
        col.addView(sectionTitle("Tambah Target Baru").apply {
            setPadding(0, dp(24), 0, dp(12))
        })

        val inputName = styledInput("Nama Kontak / Grup")
        val inputId = styledInput("Nomor / Identifier")
        val inputKey = styledInput("Kunci AES (opsional)") // AES_TARGET_KEY_V1
        col.addView(inputName)
        col.addView(inputId.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })
        col.addView(inputKey.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        })

        col.addView(primaryButton("Tambah Target") {
            val name = inputName.text.toString().trim()
            val id = inputId.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Nama target wajib diisi", Toast.LENGTH_SHORT).show()
                return@primaryButton
            }
            val key = inputKey.text.toString().trim()
            HexLogDatabase.saveTarget(this, TargetModel(name, id, key.ifBlank { null }))
            inputName.text.clear()
            inputId.text.clear()
            inputKey.text.clear()
            refreshTargets()
            refreshStatusCards()
            Toast.makeText(this, "Target ditambahkan", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        })

        scroll.addView(col)
        return scroll
    }

    private fun refreshTargets() {
        allTargets = HexLogDatabase.getTargets(this)
        filterTargets(searchInput.text?.toString().orEmpty())
    }

    private fun filterTargets(query: String) {
        targetContainer.removeAllViews()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allTargets
        else allTargets.filter {
            it.name.lowercase().contains(q) || it.identifier.lowercase().contains(q)
        }

        if (filtered.isEmpty()) {
            targetContainer.addView(TextView(this).apply {
                text = if (allTargets.isEmpty()) "Belum ada target.\nTambahkan kontak atau grup di bawah."
                else "Tidak ada hasil untuk \"$query\""
                setTextColor(HexTheme.textMuted)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(16))
            })
            return
        }

        filtered.forEachIndexed { index, target ->
            // index di allTargets (untuk hapus yang benar)
            val realIndex = allTargets.indexOf(target)
            targetContainer.addView(targetRow(target, realIndex))
        }
    }

    private fun targetRow(target: TargetModel, index: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(HexTheme.panelBg, 14f, HexTheme.accentDim, 1)
            setPadding(dp(14), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp

            // Avatar circle
            addView(TextView(this@MainActivity).apply {
                text = target.name.take(1).uppercase()
                setTextColor(HexTheme.accent)
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(HexTheme.accentGlow)
                }
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(TextView(this@MainActivity).apply {
                text = target.name
                setTextColor(HexTheme.textPrimary)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            if (target.identifier.isNotBlank()) {
                textCol.addView(TextView(this@MainActivity).apply {
                    text = target.identifier
                    setTextColor(HexTheme.textSecondary)
                    textSize = 12f
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            textCol.setOnClickListener { showEditTargetDialog(target, index) } // TARGETS_V2

            // Badge Aktif/Mati -- TARGETS_V2: bisa diketuk untuk menyalakan/mematikan target
            val on = target.isEnabled()
            if (!on) alpha = 0.6f
            addView(TextView(this@MainActivity).apply {
                text = if (on) "Aktif" else "Mati"
                setTextColor(if (on) HexTheme.success else HexTheme.textMuted)
                textSize = 11f
                background = rounded(
                    if (on) Color.parseColor("#1A3A2A") else HexTheme.surface, 8f
                )
                setPadding(dp(8), dp(3), dp(8), dp(3))
                setOnClickListener {
                    HexLogDatabase.setTargetEnabled(this@MainActivity, index, !on)
                    refreshTargets()
                    refreshStatusCards()
                }
            })

            // Atur kunci AES per-target -- AES_TARGET_KEY_V1
            addView(TextView(this@MainActivity).apply {
                val hasKey = !target.aesKey.isNullOrBlank()
                text = if (hasKey) "\uD83D\uDD12" else "\uD83D\uDD13"
                setTextColor(if (hasKey) HexTheme.accent else HexTheme.textMuted)
                textSize = 15f
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    val box = EditText(this@MainActivity)
                    box.setText(target.aesKey ?: "")
                    box.hint = "Kosongkan untuk pakai passphrase global"
                    box.setTextColor(HexTheme.textPrimary)
                    box.setHintTextColor(HexTheme.textMuted)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Kunci AES untuk \"" + target.name + "\"")
                        .setView(box)
                        .setPositiveButton("Simpan") { _, _ ->
                            val newKey = box.text.toString().trim()
                            HexLogDatabase.updateTargetAt(
                                this@MainActivity,
                                index,
                                target.copy(aesKey = newKey.ifBlank { null })
                            )
                            refreshTargets()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            })

            // Hapus
            addView(TextView(this@MainActivity).apply {
                text = "✕"
                setTextColor(HexTheme.danger)
                textSize = 16f
                setPadding(dp(12), dp(4), dp(4), dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hapus target?")
                        .setMessage("\"${target.name}\" akan dihapus.")
                        .setPositiveButton("Hapus") { _, _ ->
                            HexLogDatabase.removeTargetAt(this@MainActivity, index)
                            refreshTargets()
                            refreshStatusCards()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            })
        }
    }

    // TARGETS_V2: ubah nama / identifier target.
    private fun showEditTargetDialog(target: TargetModel, index: Int) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameBox = EditText(this).apply {
            hint = "Nama Kontak / Grup"
            setText(target.name)
        }
        val idBox = EditText(this).apply {
            hint = "Nomor / Identifier"
            setText(target.identifier)
        }
        form.addView(nameBox)
        form.addView(idBox)
        AlertDialog.Builder(this)
            .setTitle("Ubah target")
            .setView(form)
            .setPositiveButton("Simpan") { _, _ ->
                val newName = nameBox.text.toString().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this, "Nama target wajib diisi", Toast.LENGTH_SHORT).show()
                } else {
                    HexLogDatabase.updateTargetAt(
                        this, index,
                        target.copy(name = newName, identifier = idBox.text.toString().trim())
                    )
                    refreshTargets()
                    refreshStatusCards()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // =========================================================
    //  LOGS
    // =========================================================

    private fun buildLogsTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        col.addView(pageHeader("Log History", "Riwayat pesan dan aktivitas"))

        // Filter chips
        logFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        listOf("Semua", "Pesan", "Sistem").forEach { label ->
            logFilterRow.addView(filterChip(label) {
                currentLogFilter = label
                refreshLogFilters()
                refreshLogs()
            })
        }
        col.addView(logFilterRow)

        // Clear
        col.addView(TextView(this).apply {
            text = "Hapus semua log"
            setTextColor(HexTheme.danger)
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
            setOnClickListener {
                if (HexLogDatabase.getLogs(this@MainActivity).isEmpty()) {
                    Toast.makeText(this@MainActivity, "Log sudah kosong", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Hapus semua log?")
                        .setMessage("Riwayat terjemahan akan dihapus permanen.")
                        .setPositiveButton("Hapus") { _, _ ->
                            HexLogDatabase.clearLogs(this@MainActivity)
                            refreshLogs()
                            refreshStatusCards()
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                }
            }
        })

        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(logContainer)

        scroll.addView(col)
        return scroll
    }

    private fun filterChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
            // style di-refresh lewat refreshLogFilters
            tag = label
        }
    }

    private fun refreshLogFilters() {
        for (i in 0 until logFilterRow.childCount) {
            val chip = logFilterRow.getChildAt(i) as TextView
            val active = chip.tag == currentLogFilter
            chip.setTextColor(if (active) HexTheme.bg else HexTheme.textSecondary)
            chip.background = rounded(
                if (active) HexTheme.accent else HexTheme.surface,
                20f,
                if (active) HexTheme.accent else HexTheme.accentDim,
                1
            )
        }
    }

    private fun refreshLogs() {
        if (!::logContainer.isInitialized) return
        logContainer.removeAllViews()
        refreshLogFilters()

        val logs = HexLogDatabase.getLogs(this)
        val filtered = when (currentLogFilter) {
            "Pesan" -> logs.filter { !it.sender.startsWith("Sistem", ignoreCase = true) }
            "Sistem" -> logs.filter { it.sender.startsWith("Sistem", ignoreCase = true) }
            else -> logs
        }

        if (filtered.isEmpty()) {
            logContainer.addView(TextView(this).apply {
                text = "Belum ada log."
                setTextColor(HexTheme.textMuted)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
            })
            return
        }

        for (log in filtered) {
            logContainer.addView(logRow(log))
        }
    }

    private fun logRow(log: LogModel): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(HexTheme.panelBg, 12f, HexTheme.accentDim, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this@MainActivity).apply {
                text = log.timestamp.takeLast(8) // HH:mm:ss
                setTextColor(HexTheme.textMuted)
                textSize = 11f
            })
            top.addView(TextView(this@MainActivity).apply {
                text = "  ·  ${log.sender}"
                setTextColor(HexTheme.accent)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(top)

            addView(TextView(this@MainActivity).apply {
                text = log.message
                setTextColor(HexTheme.textPrimary)
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    // =========================================================
    //  SETTINGS
    // =========================================================

        private fun buildSettingsTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        col.addView(pageHeader("Pengaturan", "Izin, bubble, dan preferensi"))

        // Izin (terbuka default)
        val izinBox = collapsibleSection(col, "Izin Sistem", expanded = true, topMarginDp = 0)
        izinBox.addView(settingsRow("Notifikasi Listener", "Diperlukan untuk membaca pesan WhatsApp") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        izinBox.addView(settingsRow("Overlay (Draw Over Apps)", "Untuk floating bubble") {
            openOverlayPermission(startBubbleAfterGrant = false)
        })
        izinBox.addView(settingsRow("Aksesibilitas", "Diperlukan untuk fitur terjemah layar") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // Floating
        val bubbleBox = collapsibleSection(col, "Floating Bubble")
        bubbleBox.addView(primaryButton("Aktifkan Floating Bubble") {
            activateFloatingBubble()
        })

        // Ukuran bubble
        val sizeBox = collapsibleSection(col, "Ukuran Bubble")
        val sizeLabel = TextView(this).apply {
            text = "Ukuran: ${HexSettings.getBubbleDp(this@MainActivity)} dp"
            setTextColor(HexTheme.textSecondary)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        sizeBox.addView(sizeLabel)

        val seek = SeekBar(this).apply {
            max = HexSettings.BUBBLE_MAX_DP - HexSettings.BUBBLE_MIN_DP
            progress = HexSettings.getBubbleDp(this@MainActivity) - HexSettings.BUBBLE_MIN_DP
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val dpVal = progress + HexSettings.BUBBLE_MIN_DP
                    sizeLabel.text = "Ukuran: $dpVal dp"
                    if (fromUser) HexSettings.setBubbleDp(this@MainActivity, dpVal)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sizeBox.addView(seek)

        // Notifikasi WhatsApp
        val notifBox = collapsibleSection(col, "Notifikasi WhatsApp")
        notifBox.addView(TextView(this).apply {
            text = "Terjemahkan otomatis pesan HEX dari notifikasi WhatsApp milik target. Default mati (lebih aman)."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val notifBtn = Button(this)
        fun refreshNotifBtn() {
            val on = HexSettings.isNotifEnabled(this@MainActivity)
            notifBtn.text = if (on) "Notifikasi: NYALA" else "Notifikasi: MATI"
            notifBtn.setTextColor(if (on) Color.WHITE else HexTheme.textSecondary)
            notifBtn.background = rounded(
                if (on) HexTheme.accentDim else HexTheme.surface,
                12f,
                HexTheme.accentDim,
                1
            )
        }
        notifBtn.setOnClickListener {
            HexSettings.setNotifEnabled(this, !HexSettings.isNotifEnabled(this))
            refreshNotifBtn()
            refreshStatusCards()
        }
        notifBtn.setPadding(dp(16), dp(12), dp(16), dp(12))
        notifBtn.isAllCaps = false
        refreshNotifBtn()
        notifBox.addView(notifBtn)

        val notifStatus = TextView(this)
        notifStatus.setTextColor(HexTheme.textSecondary)
        notifStatus.textSize = 12f
        notifStatus.setPadding(0, dp(10), 0, dp(4))
        notifStatusView = notifStatus
        notifBox.addView(notifStatus)

        val btnOpenListener = secondaryButton("Buka pengaturan akses notifikasi") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        notifBox.addView(btnOpenListener)

        val btnTestNotif = secondaryButton("Tes tampilkan notifikasi") {
            WhatsAppHexService.postTranslation(
                this@MainActivity,
                "Tes",
                "Halo, notifikasi HEXTOOL berfungsi"
            )
            updateNotifStatusText()
        }
        notifBox.addView(btnTestNotif)
        updateNotifStatusText()

        // ---- NOTIF_LOG_APPS_V1: aplikasi yang dipantau ----
        val appsBox = collapsibleSection(col, "Aplikasi Dipantau (Log)")
        val appsInfo = TextView(this).apply {
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        }
        fun refreshAppsInfo() {
            val pm = packageManager
            val names = HexSettings.getNotifPackages(this).map { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg }
            }.sorted()
            appsInfo.text = "Notifikasi dari aplikasi ini dicek dan dicatat ke Log:\n" +
                (if (names.isEmpty()) "(belum ada)" else names.joinToString(", "))
        }
        refreshAppsInfo()
        appsBox.addView(appsInfo)
        appsBox.addView(primaryButton("Pilih Aplikasi") { showNotifAppPicker { refreshAppsInfo() } })

        // ---- Notifikasi Silent (lokal) ----
        val silentBox = collapsibleSection(col, "Notifikasi Silent")
        silentBox.addView(TextView(this).apply {
            text = "Senyapkan notifikasi dari aplikasi atau kata kunci tertentu. Berjalan 100% lokal lewat Notification Listener -- tidak ada data yang dikirim keluar."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val silentBtn = Button(this)
        fun refreshSilentBtn() {
            val on = HexSettings.isSilentEnabled(this@MainActivity)
            silentBtn.text = if (on) "Silent Mode: NYALA" else "Silent Mode: MATI"
            silentBtn.setTextColor(if (on) Color.WHITE else HexTheme.textSecondary)
            silentBtn.background = rounded(
                if (on) HexTheme.accentDim else HexTheme.surface,
                12f, HexTheme.accentDim, 1
            )
        }
        silentBtn.setOnClickListener {
            HexSettings.setSilentEnabled(this, !HexSettings.isSilentEnabled(this))
            refreshSilentBtn()
        }
        silentBtn.setPadding(dp(16), dp(12), dp(16), dp(12))
        silentBtn.isAllCaps = false
        refreshSilentBtn()
        silentBox.addView(silentBtn)

        silentBox.addView(TextView(this).apply {
            text = "Package yang di-mute (satu per baris, contoh: com.instagram.android)"
            setTextColor(HexTheme.textMuted)
            textSize = 11f
            setPadding(0, dp(12), 0, dp(6))
        })
        val mutePkgInput = EditText(this).apply {
            setText(HexSettings.getMutedPackages(this@MainActivity).joinToString("\n"))
            setTextColor(HexTheme.textPrimary)
            setHintTextColor(HexTheme.textMuted)
            hint = "com.example.app"
            textSize = 13f
            minLines = 3
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = Typeface.MONOSPACE
        }
        silentBox.addView(mutePkgInput)

        silentBox.addView(TextView(this).apply {
            text = "Kata kunci mute (pisahkan koma)"
            setTextColor(HexTheme.textMuted)
            textSize = 11f
            setPadding(0, dp(12), 0, dp(6))
        })
        val muteKwInput = EditText(this).apply {
            setText(HexSettings.getMuteKeywords(this@MainActivity).joinToString(", "))
            setTextColor(HexTheme.textPrimary)
            setHintTextColor(HexTheme.textMuted)
            hint = "promo, diskon, flash sale"
            textSize = 13f
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        silentBox.addView(muteKwInput)

        silentBox.addView(primaryButton("Simpan Aturan Silent") {
            val pkgs = mutePkgInput.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            HexSettings.setMutedPackages(this, pkgs)
            val kws = muteKwInput.text.toString()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            HexSettings.setMuteKeywords(this, kws)
            Toast.makeText(this, "Aturan silent disimpan", Toast.LENGTH_SHORT).show()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })

        // ---------- TRANSLATE_FOUNDATION_V1: fondasi Terjemah Bahasa ----------
        val translateBox = collapsibleSection(col, "Terjemah Bahasa (Beta)")
        translateBox.addView(TextView(this).apply {
            text = "Terjemahan offline lewat ML Kit. Model diunduh sekali (lebih kurang 30MB), lalu jalan tanpa internet. Untuk terjemah layar: ketuk bubble, lalu pilih Terjemah Bahasa."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        var translateDirection = if (HexSettings.getTranslateDirection(this) == "ID_TO_EN") LanguageTranslator.Direction.ID_TO_EN else LanguageTranslator.Direction.EN_TO_ID
        val dirBtnEnId = TextView(this)
        val dirBtnIdEn = TextView(this)
        fun refreshDirButtons() {
            val activeBg = rounded(HexTheme.accentDim, 10f)
            val inactiveBg = rounded(HexTheme.surface, 10f, HexTheme.accentDim, 1)
            dirBtnEnId.background = if (translateDirection == LanguageTranslator.Direction.EN_TO_ID) activeBg else inactiveBg
            dirBtnEnId.setTextColor(if (translateDirection == LanguageTranslator.Direction.EN_TO_ID) Color.WHITE else HexTheme.textSecondary)
            dirBtnIdEn.background = if (translateDirection == LanguageTranslator.Direction.ID_TO_EN) activeBg else inactiveBg
            dirBtnIdEn.setTextColor(if (translateDirection == LanguageTranslator.Direction.ID_TO_EN) Color.WHITE else HexTheme.textSecondary)
        }
        dirBtnEnId.text = "EN -> ID"
        dirBtnEnId.gravity = Gravity.CENTER
        dirBtnEnId.textSize = 13f
        dirBtnEnId.setPadding(0, dp(10), 0, dp(10))
        dirBtnIdEn.text = "ID -> EN"
        dirBtnIdEn.gravity = Gravity.CENTER
        dirBtnIdEn.textSize = 13f
        dirBtnIdEn.setPadding(0, dp(10), 0, dp(10))
        val dirRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dirRow.addView(dirBtnEnId, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dirRow.addView(dirBtnIdEn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        dirBtnEnId.setOnClickListener { translateDirection = LanguageTranslator.Direction.EN_TO_ID; HexSettings.setTranslateDirection(this, "EN_TO_ID"); refreshDirButtons() }
        dirBtnIdEn.setOnClickListener { translateDirection = LanguageTranslator.Direction.ID_TO_EN; HexSettings.setTranslateDirection(this, "ID_TO_EN"); refreshDirButtons() }
        refreshDirButtons()
        translateBox.addView(dirRow)

        val translateStatus = TextView(this)
        translateStatus.text = "Model belum dicek"
        translateStatus.setTextColor(HexTheme.textMuted)
        translateStatus.textSize = 11f
        translateBox.addView(translateStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        translateBox.addView(secondaryButton("Unduh Model Bahasa") {
            translateStatus.text = "Mengunduh model (butuh internet)..."
            LanguageTranslator.downloadModel(translateDirection) { ok, err ->
                runOnUiThread {
                    translateStatus.text = if (ok) "Model siap, bisa dipakai offline" else "Gagal unduh: " + err
                }
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        val translateInput = EditText(this)
        translateInput.setTextColor(HexTheme.textPrimary)
        translateInput.setHintTextColor(HexTheme.textMuted)
        translateInput.hint = "Ketik atau tempel teks di sini"
        translateInput.textSize = 13f
        translateInput.minLines = 2
        translateInput.background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
        translateInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        translateBox.addView(translateInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val translateResult = TextView(this)
        translateResult.setTextColor(HexTheme.accent)
        translateResult.textSize = 13f
        translateResult.setPadding(dp(14), dp(12), dp(14), dp(12))
        translateResult.background = rounded(HexTheme.panelBg, 12f, HexTheme.accentDim, 1)
        translateResult.visibility = android.view.View.GONE

        translateBox.addView(primaryButton("Terjemahkan") {
            val input = translateInput.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "Isi teksnya dulu", Toast.LENGTH_SHORT).show()
            } else {
                translateResult.visibility = android.view.View.VISIBLE
                translateResult.text = "Menerjemahkan..."
                LanguageTranslator.translate(translateDirection, input) { result, err ->
                    runOnUiThread {
                        translateResult.text = result ?: ("Gagal: " + err + " (coba unduh model dulu)")
                    }
                }
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })

        translateBox.addView(translateResult, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // ---------- THEME_TOGGLE_V1: pemilih tema ----------
        val themeBox = collapsibleSection(col, "Tema Warna")

        val themeDotMono = TextView(this)
        val themeDotBiru = TextView(this)
        val themeLabelMono = TextView(this)
        val themeLabelBiru = TextView(this)

        fun refreshThemeRows() {
            val current = HexSettings.getThemeId(this)
            val onMono = current == HexTheme.THEME_MONO
            val onBiru = current == HexTheme.THEME_BIRU_LAUT
            themeDotMono.text = if (onMono) "\u25CF" else "\u25CB"
            themeDotBiru.text = if (onBiru) "\u25CF" else "\u25CB"
            themeDotMono.setTextColor(if (onMono) HexTheme.accent else HexTheme.textMuted)
            themeDotBiru.setTextColor(if (onBiru) HexTheme.accent else HexTheme.textMuted)
        }

        themeDotMono.textSize = 16f
        themeDotMono.setPadding(0, 0, dp(10), 0)
        themeLabelMono.text = "Hitam Putih (Default)"
        themeLabelMono.setTextColor(HexTheme.textPrimary)
        themeLabelMono.textSize = 13f
        val rowMono = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(themeDotMono)
            addView(themeLabelMono)
            setOnClickListener {
                HexSettings.setThemeId(this@MainActivity, HexTheme.THEME_MONO)
                refreshThemeRows()
                Toast.makeText(this@MainActivity, "Tema disimpan. Buka ulang aplikasi untuk menerapkan.", Toast.LENGTH_LONG).show()
            }
        }
        themeBox.addView(rowMono)

        themeDotBiru.textSize = 16f
        themeDotBiru.setPadding(0, 0, dp(10), 0)
        themeLabelBiru.text = "Biru Laut"
        themeLabelBiru.setTextColor(HexTheme.textPrimary)
        themeLabelBiru.textSize = 13f
        val rowBiru = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            addView(themeDotBiru)
            addView(themeLabelBiru)
            setOnClickListener {
                HexSettings.setThemeId(this@MainActivity, HexTheme.THEME_BIRU_LAUT)
                refreshThemeRows()
                Toast.makeText(this@MainActivity, "Tema disimpan. Buka ulang aplikasi untuk menerapkan.", Toast.LENGTH_LONG).show()
            }
        }
        themeBox.addView(rowBiru)
        refreshThemeRows()

        // ---------- THEME_PLUGIN_V1: tema unduhan dari GitHub ----------
        val pluginList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderPluginRows(entries: List<ThemeManager.Entry>) {
            pluginList.removeAllViews()
            entries.forEach { e ->
                val local = ThemeManager.installedVersion(this, e.id)
                val action = when { local < 0 -> "Unduh"; local < e.version -> "Update"; else -> "Pakai" }
                val active = HexSettings.getThemeId(this) == e.id
                pluginList.addView(TextView(this).apply {
                    text = (if (active) "\u25CF " else "\u25CB ") + e.name + "  [" + action + "]"
                    setTextColor(if (active) HexTheme.accent else HexTheme.textPrimary)
                    textSize = 13f
                    setPadding(0, dp(8), 0, dp(8))
                    setOnClickListener {
                        if (local >= e.version) {
                            HexSettings.setThemeId(this@MainActivity, e.id)
                            renderPluginRows(entries); refreshThemeRows()
                            Toast.makeText(this@MainActivity, "Tema disimpan. Buka ulang aplikasi untuk menerapkan.", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Mengunduh...", Toast.LENGTH_SHORT).show()
                            Thread {
                                val err = ThemeManager.download(this@MainActivity, e.id)
                                runOnUiThread {
                                    if (err == null) HexSettings.setThemeId(this@MainActivity, e.id)
                                    Toast.makeText(this@MainActivity, err ?: "Selesai. Buka ulang aplikasi untuk menerapkan.", Toast.LENGTH_LONG).show()
                                    renderPluginRows(entries); refreshThemeRows()
                                }
                            }.start()
                        }
                    }
                    if (local >= 0) setOnLongClickListener {
                        ThemeManager.delete(this@MainActivity, e.id)
                        if (HexSettings.getThemeId(this@MainActivity) == e.id)
                            HexSettings.setThemeId(this@MainActivity, HexTheme.THEME_MONO)
                        renderPluginRows(ThemeManager.installed(this@MainActivity)); refreshThemeRows()
                        Toast.makeText(this@MainActivity, "Tema dihapus", Toast.LENGTH_SHORT).show()
                        true
                    }
                })
            }
        }
        renderPluginRows(ThemeManager.installed(this))
        themeBox.addView(pluginList)
        themeBox.addView(TextView(this).apply {
            text = "Muat Tema Online"
            setTextColor(HexTheme.accent)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Memuat daftar...", Toast.LENGTH_SHORT).show()
                Thread {
                    val res = try { ThemeManager.fetchIndex() } catch (ex: Exception) { null }
                    runOnUiThread {
                        if (res == null) Toast.makeText(this@MainActivity, "Gagal memuat daftar tema", Toast.LENGTH_LONG).show()
                        else renderPluginRows(res)
                    }
                }.start()
            }
        })

        themeBox.addView(TextView(this).apply {
            text = "Perubahan tema terlihat setelah aplikasi dibuka ulang (termasuk bubble & overlay)."
            setTextColor(HexTheme.textMuted)
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        })

        // ---------- AES_CRYPTO_V1: fondasi enkripsi AES ----------
        val aesBox = collapsibleSection(col, "Enkripsi AES (Beta)")
        aesBox.addView(TextView(this).apply {
            text = "Passphrase global jadi kunci enkripsi default. Simpan passphrase yang SAMA PERSIS di HP lawan bicara -- kalau beda, dekripsi akan gagal."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val aesPassInput = EditText(this)
        aesPassInput.setTextColor(HexTheme.textPrimary)
        aesPassInput.setHintTextColor(HexTheme.textMuted)
        aesPassInput.hint = "Passphrase global"
        aesPassInput.textSize = 13f
        aesPassInput.setText(HexSettings.getAesPassphraseGlobal(this))
        aesPassInput.background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
        aesPassInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        aesBox.addView(aesPassInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        aesBox.addView(secondaryButton("Simpan Passphrase Global") {
            val p = aesPassInput.text.toString()
            if (p.isBlank()) {
                Toast.makeText(this, "Passphrase tidak boleh kosong", Toast.LENGTH_SHORT).show()
            } else {
                HexSettings.setAesPassphraseGlobal(this, p)
                Toast.makeText(this, "Passphrase global disimpan", Toast.LENGTH_SHORT).show()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        val aesTestInput = EditText(this)
        aesTestInput.setTextColor(HexTheme.textPrimary)
        aesTestInput.setHintTextColor(HexTheme.textMuted)
        aesTestInput.hint = "Teks untuk tes enkripsi/dekripsi"
        aesTestInput.textSize = 13f
        aesTestInput.minLines = 2
        aesTestInput.background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
        aesTestInput.setPadding(dp(14), dp(12), dp(14), dp(12))
        aesBox.addView(aesTestInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        val aesResult = TextView(this)
        aesResult.setTextColor(HexTheme.accent)
        aesResult.textSize = 12f
        aesResult.setPadding(dp(14), dp(12), dp(14), dp(12))
        aesResult.background = rounded(HexTheme.panelBg, 12f, HexTheme.accentDim, 1)
        aesResult.visibility = android.view.View.GONE

        var lastAesHex = ""

        aesBox.addView(secondaryButton("Enkripsi (tes)") {
            val txt = aesTestInput.text.toString()
            val p = HexSettings.getAesPassphraseGlobal(this)
            if (p.isBlank()) {
                Toast.makeText(this, "Simpan passphrase dulu", Toast.LENGTH_SHORT).show()
            } else if (txt.isBlank()) {
                Toast.makeText(this, "Isi teksnya dulu", Toast.LENGTH_SHORT).show()
            } else {
                lastAesHex = AesCipher.encryptToHex(txt, p)
                aesResult.visibility = android.view.View.VISIBLE
                aesResult.text = "HEX terenkripsi:\n" + lastAesHex
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })

        aesBox.addView(secondaryButton("Dekripsi (tes, dari hasil di atas)") {
            val p = HexSettings.getAesPassphraseGlobal(this)
            if (lastAesHex.isBlank()) {
                Toast.makeText(this, "Enkripsi dulu sebelum dekripsi", Toast.LENGTH_SHORT).show()
            } else {
                val r = AesCipher.decryptFromHex(lastAesHex, p)
                if (r is AesCipher.DecryptResult.Success) {
                    aesResult.visibility = android.view.View.VISIBLE
                    aesResult.text = "Berhasil didekripsi:\n" + r.text
                } else {
                    aesResult.visibility = android.view.View.VISIBLE
                    aesResult.text = "Gagal: kunci salah atau data rusak"
                }
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })

        aesBox.addView(aesResult, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Panel size
        val panelBox = collapsibleSection(col, "Ukuran Panel Floating")
        val panelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val panelNames = arrayOf("S", "M", "L")
        val panelBtns = ArrayList<TextView>()
        panelNames.forEachIndexed { i, name ->
            val btn = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    HexSettings.setPanelIndex(this@MainActivity, i)
                    refreshPanelButtons(panelBtns)
                }
            }
            panelBtns.add(btn)
            panelRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        refreshPanelButtons(panelBtns)
        panelBox.addView(panelRow)

        col.addView(TextView(this).apply {
            text = "Copyright \u00A9 Zyn / SIK Dev"
            setTextColor(HexTheme.textMuted)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(8))
        })

        scroll.addView(col)
        return scroll
    }

    private fun refreshPanelButtons(btns: List<TextView>) {
        val active = HexSettings.getPanelIndex(this)
        btns.forEachIndexed { i, b ->
            val on = i == active
            b.setTextColor(if (on) HexTheme.bg else HexTheme.textSecondary)
            b.background = rounded(
                if (on) HexTheme.accent else HexTheme.surface,
                12f,
                HexTheme.accentDim,
                1
            )
        }
    }

    private fun showNotifAppPicker(onDone: () -> Unit) {
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val current = HexSettings.getNotifPackages(this)
        val checked = BooleanArray(apps.size) { apps[it].first in current }
        android.app.AlertDialog.Builder(this)
            .setTitle("Aplikasi yang dipantau")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Simpan") { _, _ ->
                val listed = apps.map { it.first }.toSet()
                val result = current.filter { it !in listed }.toMutableSet()
                apps.forEachIndexed { i, a -> if (checked[i]) result.add(a.first) }
                HexSettings.setNotifPackages(this, result)
                onDone()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun settingsRow(title: String, desc: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(HexTheme.panelBg, 14f, HexTheme.accentDim, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }

            val textCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(HexTheme.textPrimary)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            })
            textCol.addView(TextView(this@MainActivity).apply {
                text = desc
                setTextColor(HexTheme.textSecondary)
                textSize = 12f
            })
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                setTextColor(HexTheme.textMuted)
                textSize = 20f
            })
        }
    }

    // =========================================================
    //  BOTTOM NAV
    // =========================================================

    private fun buildBottomNav(onSelect: (Int) -> Unit): LinearLayout {
        navButtons.clear()
        navIcons.clear()

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(HexTheme.panelBg)
                setStroke(1, HexTheme.accentDim)
            }
            setPadding(0, dp(6), 0, dp(6))
            elevation = dp(8).toFloat()
        }

        val items = listOf(
            "⌂" to "Home",
            "◎" to "Targets",
            "☰" to "Logs",
            "⚙" to "Settings"
        )

        items.forEachIndexed { index, (icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { onSelect(index) }
            }
            val iconView = TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) HexTheme.accent else HexTheme.textMuted)
            }
            val labelView = TextView(this).apply {
                text = label
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (index == 0) HexTheme.accent else HexTheme.textMuted)
            }
            navIcons.add(iconView)
            navButtons.add(labelView)
            navItems.add(item)
            item.addView(iconView)
            item.addView(labelView)
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        return bar
    }

    private fun switchTab(tabs: List<View>, index: Int) {
        tabs.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navButtons.forEachIndexed { i, b ->
            val on = i == index
            b.setTextColor(if (on) HexTheme.accent else HexTheme.textMuted)
            navIcons.getOrNull(i)?.setTextColor(if (on) HexTheme.accent else HexTheme.textMuted)
        }
        if (index == 0) refreshStatusCards()
        if (index == 2) refreshLogs()
    }

    // =========================================================
    //  HELPERS
    // =========================================================

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
            if (strokeWidth > 0) {
                setStroke(
                    (strokeWidth * resources.displayMetrics.density).toInt(),
                    strokeColor
                )
            }
        }
    }

    private fun roundedGradient(colorTop: Int, colorBottom: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(colorTop, colorBottom)).apply {
            cornerRadius = radius * resources.displayMetrics.density
            if (strokeWidth > 0) { setStroke((strokeWidth * resources.displayMetrics.density).toInt(), strokeColor) }
        }
    }

    private fun collapsibleSection(parent: LinearLayout, title: String, expanded: Boolean = false, topMarginDp: Int = 24): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(topMarginDp), 0, dp(10))
        }
        val titleTv = TextView(this).apply {
            text = title
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val arrowTv = TextView(this).apply {
            text = if (expanded) "\u25B4" else "\u25BE"
            setTextColor(HexTheme.textMuted)
            textSize = 14f
        }
        header.addView(titleTv)
        header.addView(arrowTv)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        }

        header.setOnClickListener {
            val show = content.visibility != android.view.View.VISIBLE
            content.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            arrowTv.text = if (show) "\u25B4" else "\u25BE"
        }

        parent.addView(header)
        parent.addView(content)
        return content
    }
    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun pageHeader(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(HexTheme.textPrimary)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(HexTheme.textSecondary)
                textSize = 13f
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun styledInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(HexTheme.textMuted)
            setTextColor(HexTheme.textPrimary)
            textSize = 14f
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setSingleLine()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = rounded(HexTheme.accent, 12f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(HexTheme.accent)
            textSize = 14f
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
    }

    private fun activateFloatingBubble() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingWidgetService::class.java))
            Toast.makeText(this, "Floating Bubble diaktifkan", Toast.LENGTH_SHORT).show()
            refreshStatusCards()
        } else {
            Toast.makeText(
                this,
                "Aktifkan \"Tampil di atas aplikasi lain\" untuk HEXTOOL, lalu kembali",
                Toast.LENGTH_LONG
            ).show()
            openOverlayPermission(startBubbleAfterGrant = true)
        }
    }

    // Bubble otomatis dinyalakan begitu pengguna kembali dengan izin sudah diberikan.
    private var pendingBubbleStart = false

    // Halaman izin overlay khusus HEXTOOL. Fallback bertingkat untuk ROM (MIUI dll.)
    // yang tidak mengenali intent standar atau mengabaikan URI paketnya.
    private fun openOverlayPermission(startBubbleAfterGrant: Boolean) {
        pendingBubbleStart = startBubbleAfterGrant
        val attempts = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in attempts) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
                // coba cara berikutnya
            }
        }
        pendingBubbleStart = false
        Toast.makeText(
            this,
            "Buka Pengaturan > Aplikasi > HEXTOOL > Tampil di atas aplikasi lain",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openPermissionSettings() {
        AlertDialog.Builder(this)
            .setTitle("Izin Sistem")
            .setMessage(
                "Pilih izin yang ingin diatur:\n\n" +
                    "• Notifikasi Listener — baca pesan WhatsApp\n" +
                    "• Overlay — floating bubble\n" +
                    "• Aksesibilitas — terjemah layar"
            )
            .setPositiveButton("Notifikasi") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNeutralButton("Overlay") { _, _ ->
                openOverlayPermission(startBubbleAfterGrant = false)
            }
            .setNegativeButton("Aksesibilitas") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }

    // =========================================================
    //  LINK / QR CHECKER (100% lokal)
    // =========================================================

    private fun showLinkCheckerDialog(fromClipboard: Boolean = false) {
        val pad = dp(20)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
        }

        container.addView(TextView(this).apply {
            text = "Pemeriksaan tautan sepenuhnya di perangkat. Tidak ada data yang dikirim ke server."
            setTextColor(HexTheme.textSecondary)
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        val input = EditText(this).apply {
            hint = "Tempel URL atau teks berisi link"
            setHintTextColor(HexTheme.textMuted)
            setTextColor(HexTheme.textPrimary)
            textSize = 14f
            minLines = 2
            background = rounded(HexTheme.surface, 12f, HexTheme.accentDim, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        container.addView(input)

        if (fromClipboard) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (clip.isNotBlank()) input.setText(clip)
        }

        val resultView = TextView(this).apply {
            setTextColor(HexTheme.textPrimary)
            textSize = 13f
            setPadding(0, dp(14), 0, 0)
        }
        container.addView(resultView)

        fun runCheck() {
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) {
                resultView.text = "Masukkan URL terlebih dahulu."
                resultView.setTextColor(HexTheme.textMuted)
                return
            }
            val report = LinkSafetyChecker.check(raw)
            val levelColor = when (report.level) {
                LinkSafetyChecker.Level.AMAN -> if (report.trusted) HexTheme.success else HexTheme.textPrimary
                LinkSafetyChecker.Level.HATI_HATI -> HexTheme.warning
                LinkSafetyChecker.Level.MENCURIGAKAN -> HexTheme.danger
            }
            val levelLabel = linkLevelLabel(report)
            val sb = StringBuilder()
            sb.append("Hasil: $levelLabel  (skor ${report.score})\n")
            if (report.urlCount > 1) sb.append("Tautan ditemukan: ${report.urlCount}\n")
            if (report.host.isNotBlank()) sb.append("Host: ${report.host}\n")
            if (report.scheme.isNotBlank()) sb.append("Skema: ${report.scheme}\n")
            sb.append("\nAlasan:\n")
            report.reasons.forEach { sb.append("• $it\n") }
            sb.append("\nSaran: ").append(linkAdvice(report))
            sb.append("\n\nCatatan: pemeriksaan lokal berbasis pola phishing yang beredar; tidak melihat umur domain " +
                "atau isi halaman, jadi bukan jaminan aman mutlak.")
            resultView.text = sb.toString()
            resultView.setTextColor(levelColor)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        val btnCheck = Button(this).apply {
            text = "Periksa"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(HexTheme.accent, 10f)
            setOnClickListener { runCheck() }
        }
        val btnClip = Button(this).apply {
            text = "Dari Clipboard"
            isAllCaps = false
            setTextColor(HexTheme.accent)
            background = rounded(HexTheme.surface, 10f, HexTheme.accentDim, 1)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                if (clip.isBlank()) {
                    Toast.makeText(this@MainActivity, "Clipboard kosong", Toast.LENGTH_SHORT).show()
                } else {
                    input.setText(clip)
                    runCheck()
                }
            }
        }
        btnRow.addView(btnCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        btnRow.addView(btnClip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        container.addView(btnRow)

        // QR: coba intent scanner eksternal (ZXing dll) jika terpasang
        container.addView(Button(this).apply {
            text = "Scan QR (app scanner eksternal)"
            isAllCaps = false
            setTextColor(HexTheme.textSecondary)
            background = rounded(HexTheme.surface, 10f, HexTheme.accentDim, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnClickListener {
                val scan = Intent("com.google.zxing.client.android.SCAN").apply {
                    putExtra("SCAN_MODE", "QR_CODE_MODE")
                }
                try {
                    startActivityForResult(scan, 9001)
                } catch (_: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tidak ada app QR scanner. Install Barcode Scanner (ZXing) atau tempel hasil scan manual.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Link / QR Checker")
            .setView(container)
            .setNegativeButton("Tutup", null)
            .show()

        if (fromClipboard && input.text.isNotEmpty()) runCheck()
    }

    private fun linkLevelLabel(r: LinkSafetyChecker.Report): String = when (r.level) {
        LinkSafetyChecker.Level.AMAN ->
            if (r.trusted) "TIDAK ADA SINYAL RISIKO (domain resmi dikenal)"
            else "BELUM ADA SINYAL RISIKO (domain tidak dikenal, bukan jaminan aman)"
        LinkSafetyChecker.Level.HATI_HATI -> "PERLU HATI-HATI"
        LinkSafetyChecker.Level.MENCURIGAKAN -> "MENCURIGAKAN — JANGAN DIBUKA"
    }

    private fun linkAdvice(r: LinkSafetyChecker.Report): String = when (r.level) {
        LinkSafetyChecker.Level.MENCURIGAKAN ->
            "jangan dibuka, jangan isi data/OTP/PIN, jangan pasang berkas apa pun. Hapus pesan, dan laporkan bila meniru instansi/bank " +
                "(cekrekening.id, aduannomor.id, atau layanan resmi merek terkait)."
        LinkSafetyChecker.Level.HATI_HATI ->
            "jangan masukkan data sensitif. Buka situs resminya langsung dengan mengetik alamat sendiri / lewat aplikasi resmi, bukan dari tautan ini."
        LinkSafetyChecker.Level.AMAN ->
            "tetap cek ejaan alamat, dan jangan pernah membagikan OTP/PIN/password meski halamannya tampak meyakinkan."
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9001 && resultCode == RESULT_OK && data != null) {
            val contents = data.getStringExtra("SCAN_RESULT").orEmpty()
            if (contents.isNotBlank()) {
                // Tampilkan dialog checker dengan hasil scan
                showLinkCheckerDialog()
                // Dialog baru; set via clipboard agar user langsung lihat
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("qr", contents))
                Toast.makeText(this, "Hasil QR disalin. Buka checker lagi atau tempel manual.", Toast.LENGTH_LONG).show()
                // Langsung cek
                val report = LinkSafetyChecker.check(contents)
                val level = linkLevelLabel(report)
                AlertDialog.Builder(this)
                    .setTitle("Hasil QR: $level")
                    .setMessage(
                        "Isi: $contents\n\nHost: ${report.host}\nSkor: ${report.score}\n\n" +
                            report.reasons.joinToString("\n") { "• $it" }
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ========================================================================
    // SYNCHRONIZED UI FUNCTIONS & ALL OVERLOADS
    // ========================================================================

    private fun createCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(HexTheme.surface)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun createDotDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    // --- setCardAccent Overloads ---
    private fun setCardAccent(card: LinearLayout, color: Int) {
        val header = card.getChildAt(0) as? LinearLayout
        val dot = header?.getChildAt(0)
        (dot?.background as? GradientDrawable)?.setColor(color)
    }

    private fun setCardAccent(card: LinearLayout, value: String, sub: String, color: Int) {
        setCardAccent(card, color)
        (card.getChildAt(1) as? TextView)?.text = value
        (card.getChildAt(2) as? TextView)?.text = sub
    }

    private fun setCardAccent(card: LinearLayout, color: Int, value: String, sub: String) {
        setCardAccent(card, value, sub, color)
    }

    private fun setCardAccent(card: LinearLayout, value: String, color: Int) {
        setCardAccent(card, value, "", color)
    }

    // --- statusCard Overloads ---
    private fun statusCard(
        title: String,
        accentColor: Int,
        initialValue: String,
        initialSub: String,
        onClick: (() -> Unit)?
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = createCardBackground()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            onClick?.let { cb -> setOnClickListener { cb() } }
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
            background = createDotDrawable(accentColor)
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(HexTheme.textSecondary)
        }

        headerLayout.addView(statusDot)
        headerLayout.addView(titleTv)

        val valueTv = TextView(this).apply {
            text = initialValue
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, 0)
            tag = "value"
        }

        val subTv = TextView(this).apply {
            tag = "subtitle" // dicari oleh setCardSubtitle()
            text = initialSub
            textSize = 10f
            setTextColor(HexTheme.textMuted)
        }

        card.addView(headerLayout)
        card.addView(valueTv)
        card.addView(subTv)
        return card
    }

    private fun statusCard(title: String, accentColor: Int, initialValue: String, initialSub: String): LinearLayout {
        return statusCard(title, accentColor, initialValue, initialSub, null)
    }

    private fun statusCard(title: String, initialValue: String, initialSub: String, accentColor: Int): LinearLayout {
        return statusCard(title, accentColor, initialValue, initialSub, null)
    }

    private fun statusCard(title: String, initialValue: String, initialSub: String): LinearLayout {
        return statusCard(title, HexTheme.accent, initialValue, initialSub, null)
    }

    // --- featureRow ---
    private fun featureRow(
        iconRes: Int,
        title: String,
        desc: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = GradientDrawable().apply {
            setColor(HexTheme.panelBg)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), HexTheme.accentDim)
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(8)) }
        row.setOnClickListener { onClick() }

        val iconContainer = FrameLayout(this)
        iconContainer.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        iconContainer.background = GradientDrawable().apply {
            setColor(HexTheme.panelBgAlt)
            cornerRadius = dp(10).toFloat()
        }
        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.setColorFilter(Color.WHITE)
        iv.layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        iconContainer.addView(iv)

        val textLayout = LinearLayout(this)
        textLayout.orientation = LinearLayout.VERTICAL
        textLayout.setPadding(dp(12), 0, dp(12), 0)
        textLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val tvTitle = TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val tvDesc = TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(HexTheme.textSecondary)
        }
        textLayout.addView(tvTitle)
        textLayout.addView(tvDesc)

        val arrowTv = TextView(this).apply {
            text = ">"
            textSize = 18f
            setTextColor(HexTheme.textMuted)
        }

        row.addView(iconContainer)
        row.addView(textLayout)
        row.addView(arrowTv)
        return row
    }

    private fun showPrePermissionDialog(
        title: String,
        explanation: String,
        onProceed: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton("Buka Pengaturan") { _, _ -> onProceed() }
            .setNegativeButton("Batal", null)
            .create()
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        dialog.show()
    }

    // --- Overload tambahan: statusCard(title, value, accent) ---
    private fun statusCard(title: String, initialValue: String, accentColor: Int): LinearLayout {
        return statusCard(title, accentColor, initialValue, "", null)
    }

    // --- Overload tambahan: featureRow dengan ikon emoji/teks ---
    private fun featureRow(
        icon: String,
        title: String,
        desc: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.background = GradientDrawable().apply {
            setColor(HexTheme.panelBg)
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), HexTheme.accentDim)
        }
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dp(8)) }
        row.setOnClickListener { onClick() }

        val iconTv = TextView(this).apply {
            text = icon
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = GradientDrawable().apply {
                setColor(HexTheme.panelBgAlt)
                cornerRadius = dp(10).toFloat()
            }
        }

        val textLayout = LinearLayout(this)
        textLayout.orientation = LinearLayout.VERTICAL
        textLayout.setPadding(dp(12), 0, dp(12), 0)
        textLayout.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        textLayout.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        textLayout.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(HexTheme.textSecondary)
        })

        val arrowTv = TextView(this).apply {
            text = ">"
            textSize = 18f
            setTextColor(HexTheme.textMuted)
        }

        row.addView(iconTv)
        row.addView(textLayout)
        row.addView(arrowTv)
        return row
    }
}