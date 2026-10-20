package com.example.hextool

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.TextViewCompat
import kotlin.math.abs

class FloatingWidgetService : Service() {

    companion object {
        private const val TAG = "FloatingWidgetService"
        private const val LONG_PRESS_MS = 600L
        private const val REFRESH_INTERVAL_MS = 1200L

        // Dibaca MainActivity untuk kartu status "Floating".
        @Volatile var running = false

        // Indeks 0 = S, 1 = M, 2 = L
        private val SIZE_NAMES = arrayOf("S", "M", "L")
        private val WIDTH_FRACTION = floatArrayOf(0.72f, 0.86f, 0.95f)
        private val INPUT_SP = floatArrayOf(12f, 14f, 16f)
        private val BUTTON_SP = floatArrayOf(11f, 13f, 15f)
        private val HEADING_SP = floatArrayOf(13f, 16f, 18f)
        private val BUTTON_PAD_DP = intArrayOf(6, 9, 12)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleAdded = false
    private var panelView: View? = null
    private var menuView: View? = null  // MENU_BUBBLE_V1
    private var lastOutsideCloseMs = 0L

    // Mode terjemah layar: kotak abu-abu yang menimpa kode HEX di layar.
    private val overlayViews = ArrayList<View>()

    // ---------------- LANG_OVERLAY_V1 ----------------
    private var langOverlayActive = false
    private val langOverlayViews = ArrayList<View>()
    private var lastLangHits: List<HexClipboardService.TextHit> = emptyList()
    private var lastLangDrawn: List<Pair<HexClipboardService.TextHit, String>> = emptyList()
    private var langErrorShown = false
    private val langCache = HashMap<String, String>()
    private val langInFlight = HashSet<String>()

    private val langRefreshRunnable: Runnable = object : Runnable {
        override fun run() {
            refreshLangOverlay()
            if (langOverlayActive) {
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }
    private var overlayActive = false
    private var lastHits: List<HexClipboardService.ScreenHit> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            refreshOverlay()
            if (overlayActive) {
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
    }

    private val green = HexTheme.green
    private val cyan = HexTheme.cyan
    private val yellow = HexTheme.yellow
    private val black = HexTheme.black
    private val dark = HexTheme.dark
    private val overlayGrey = HexTheme.overlayGrey

    // Harus disimpan di field: SharedPreferences hanya menyimpan referensi lemah.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == HexSettings.KEY_BUBBLE_DP) applyBubbleSize()
        }

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HexTheme.load(this) // THEME_TOGGLE_V1

        // Tanpa izin overlay addView() melempar BadTokenException (crash). Hentikan dengan sopan.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Izin overlay belum diberikan. Buka HEXTOOL > Pengaturan > Overlay.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }
        running = true

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = try {
            LayoutInflater.from(this).inflate(R.layout.floating_widget_layout, null)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat tampilan bubble", e)
            Toast.makeText(this, "Bubble gagal dibuat: ${e.message}", Toast.LENGTH_LONG).show()
            running = false
            stopSelf()
            return
        }

        val bubblePx = dp(HexSettings.getBubbleDp(this))

        bubbleParams = WindowManager.LayoutParams(
            bubblePx,
            bubblePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // BUBBLE_ICON_CHECK
        val iconView = floatingView.findViewById<android.widget.ImageView>(R.id.bubbleIcon)
        // THEME_PLUGIN_V2: ikon + latar bubble ikut tema
        // ICON_PLUGIN_V1: icons.bubble > images.mascot > bawaan
        iconView?.let {
            val bubbleIcon = HexTheme.iconBitmap("bubble")
            if (bubbleIcon != null) it.setImageBitmap(bubbleIcon)
            else HexTheme.setImage(it, "mascot", R.drawable.cat_hex)
        }
        floatingView.findViewById<android.view.View>(R.id.bubbleCircle)?.background =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(HexTheme.panelBg)
                setStroke(2, HexTheme.accentDim)
            }
        Log.d(TAG, "Ikon bubble: drawable=${iconView?.drawable}")
        try {
            windowManager.addView(floatingView, bubbleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menampilkan bubble", e)
            Toast.makeText(this, "Bubble gagal tampil: ${e.message}", Toast.LENGTH_LONG).show()
            running = false
            stopSelf()
            return
        }
        bubbleAdded = true
        applyBubbleSize()

        HexSettings.prefs(this)
            .registerOnSharedPreferenceChangeListener(prefsListener)

        floatingView.setOnTouchListener(
            object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false
                private var longPressed = false

                private val longPress = Runnable {
                    if (!moved) {
                        longPressed = true
                        hideMenu()
                        toggleScreenOverlay(openSettings = false)
                    }
                }

                override fun onTouch(
                    v: View,
                    event: MotionEvent
                ): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = bubbleParams.x
                            startY = bubbleParams.y
                            touchX = event.rawX
                            touchY = event.rawY
                            bubbleTouching = true
                            moved = false
                            longPressed = false
                            handler.postDelayed(longPress, LONG_PRESS_MS)
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - touchX
                            val dy = event.rawY - touchY

                            if (abs(dx) > 10 || abs(dy) > 10) {
                                moved = true
                                handler.removeCallbacks(longPress)
                            }

                            if (moved) {
                                bubbleParams.x = startX + dx.toInt()
                                bubbleParams.y = startY + dy.toInt()

                                windowManager.updateViewLayout(
                                    floatingView,
                                    bubbleParams
                                )
                                updateMenuPosition()
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            handler.removeCallbacks(longPress)
                            bubbleTouching = false
                            if (!moved && !longPressed) toggleMenu()
                            // Selesai geser: susun ulang menu agar muat di posisi baru.
                            if (moved) settleMenu()
                            return true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            handler.removeCallbacks(longPress)
                            bubbleTouching = false
                            return true
                        }
                    }

                    return true
                }
            }
        )
    }

    // Menerapkan ukuran bubble dari pengaturan; dipanggil juga saat slider digeser.
    private fun applyBubbleSize() {
        if (!::floatingView.isInitialized || !::bubbleParams.isInitialized) return

        val sizeDp = HexSettings.getBubbleDp(this)
        val px = dp(sizeDp)

        bubbleParams.width = px
        bubbleParams.height = px

        // Ikon ikut skala ukuran bubble (sekitar 50%).
        floatingView.findViewById<android.widget.ImageView>(R.id.bubbleIcon)?.let { icon ->
            val iconPx = (px * 0.5f).toInt()
            val lp = icon.layoutParams
            if (lp != null && (lp.width != iconPx || lp.height != iconPx)) {
                lp.width = iconPx
                lp.height = iconPx
                icon.layoutParams = lp
            }
        }

        if (bubbleAdded) {
            try {
                windowManager.updateViewLayout(floatingView, bubbleParams)
            } catch (e: Exception) {
                Log.w(TAG, "Gagal memperbarui ukuran bubble: ${e.message}")
            }
        }
    }

    private fun makeBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(HexTheme.panelBg)
            setStroke(2, HexTheme.accent)
            cornerRadius = 18f
        }
    }

    private fun makeButton(
        label: String,
        color: Int,
        onClick: () -> Unit
    ): Button {
        val s = HexSettings.getPanelIndex(this)
        return Button(this).apply {
            text = label
            setTextColor(color)
            setBackgroundColor(HexTheme.buttonBg)
            textSize = BUTTON_SP[s]
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), dp(BUTTON_PAD_DP[s]), dp(4), dp(BUTTON_PAD_DP[s]))
            setOnClickListener { onClick() }
        }
    }

    // Dua tombol sejajar dalam satu baris, lebar sama.
    private fun makeRow(left: Button, right: Button, topMargin: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val gap = dp(3)
        row.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = gap }
        )
        row.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = gap }
        )
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { this.topMargin = topMargin }
        return row
    }

    private fun showPanel(initialText: String = "") {
        if (panelView != null) return

        val s = HexSettings.getPanelIndex(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = makeBackground()
        }

        val heading = TextView(this).apply {
            text = "HEXTOOL"
            setTextColor(HexTheme.accent)
            textSize = HEADING_SP[s]
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(heading)

        val input = EditText(this).apply {
            hint = "ketik teks untuk di-encode, atau tempel kode HEX"
            setHintTextColor(Color.GRAY)
            setTextColor(HexTheme.accent)
            textSize = INPUT_SP[s]
            typeface = Typeface.MONOSPACE
            minLines = 1
            maxLines = 3
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(dark)
                setStroke(1, HexTheme.accent)
                cornerRadius = 8f
            }
            setText(initialText)
            setSelection(text.length)
        }
        root.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        )

        // Terjemahkan HEX yang tampil di layar (menyala/mati)
        root.addView(
            makeButton(
                if (overlayActive) "TERJEMAH LAYAR: MATI" else "TERJEMAH LAYAR",
                yellow
            ) { toggleScreenOverlay() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        )

        // Teks biasa -> HEX (prefix 3301 otomatis), hasil langsung disalin
        root.addView(
            makeRow(
                makeButton("ENCODE", HexTheme.accent) {
                    startClipboardActivity(
                        ClipboardActivity.MODE_ENCODE,
                        input.text.toString()
                    )
                },
                makeButton("PASTE & ENCODE", HexTheme.accent) {
                    startClipboardActivity(ClipboardActivity.MODE_ENCODE, null)
                },
                0
            )
        )

        // HEX -> teks asli
        root.addView(
            makeRow(
                makeButton("DECODE", HexTheme.accentSoft) {
                    startClipboardActivity(
                        ClipboardActivity.MODE_DECODE,
                        input.text.toString()
                    )
                },
                makeButton("PASTE & DECODE", HexTheme.accentSoft) {
                    startClipboardActivity(ClipboardActivity.MODE_DECODE, null)
                },
                dp(4)
            )
        )

        // Enkripsi AES (hasil ditandai 3302) -- AES_PANEL_V1
        root.addView(
            makeButton("ENKRIPSI (AES)", HexTheme.warning) {
                startClipboardActivity(ClipboardActivity.MODE_ENCRYPT, input.text.toString())
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )

        // Ukuran panel dan tutup
        root.addView(
            makeRow(
                makeButton("UKURAN: ${SIZE_NAMES[s]}", Color.WHITE) {
                    val keepText = input.text.toString()
                    HexSettings.setPanelIndex(
                        this,
                        (HexSettings.getPanelIndex(this) + 1) % HexSettings.PANEL_COUNT
                    )
                    hidePanel()
                    showPanel(keepText)
                },
                makeButton("TUTUP", Color.LTGRAY) { hidePanel() },
                dp(4)
            )
        )

        val metrics = resources.displayMetrics
        val panelParams = WindowManager.LayoutParams(
            (metrics.widthPixels * WIDTH_FRACTION[s]).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * 0.10f).toInt()
            softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        panelView = root

        try {
            windowManager.addView(root, panelParams)
            input.requestFocus()

            input.post {
                val imm = getSystemService(
                    Context.INPUT_METHOD_SERVICE
                ) as InputMethodManager

                imm.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT
                )
            }
        } catch (e: Exception) {
            panelView = null

            Toast.makeText(
                this,
                "Panel gagal dibuka: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ---------------- Terjemah layar (overlay menimpa HEX) ----------------

    private fun toggleScreenOverlay(openSettings: Boolean = true) {
        if (overlayActive) stopOverlay(true) else startOverlay(openSettings)
    }

    private fun startOverlay(openSettings: Boolean = true) {
        if (langOverlayActive) stopLanguageOverlay(false)
        if (HexClipboardService.instance == null) {
            // TAHAP_A11Y_CHECK: bedakan "belum diaktifkan" vs "aktif tapi belum tersambung"
            val msg = if (isAccessibilityEnabled()) {
                "Layanan Aksesibilitas HEXTOOL aktif tapi belum tersambung. Matikan lalu nyalakan lagi."
            } else {
                "Aktifkan layanan Aksesibilitas HEXTOOL dulu"
            }
            Toast.makeText(
                this,
                msg,
                Toast.LENGTH_LONG
            ).show()
            // Tahan bubble tidak boleh melempar pengguna keluar dari aplikasi;
            // pengaturan hanya dibuka bila dipilih lewat menu.
            if (openSettings) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Gagal membuka pengaturan aksesibilitas: ${e.message}")
                }
            }
            return
        }

        hidePanel()
        overlayActive = true
        lastHits = emptyList()

        HexClipboardService.onScreenChanged = { scrolled ->
            onScreenChanged(scrolled)
        }

        Toast.makeText(
            this,
            "Terjemah layar NYALA. Tahan bubble lagi untuk mematikan.",
            Toast.LENGTH_LONG
        ).show()

        // Jeda singkat agar panel benar-benar hilang sebelum layar dipindai.
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 250)
    }

    // Apakah HEXTOOL tercentang di Pengaturan Aksesibilitas (terlepas dari sudah tersambung atau belum).
    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val cn = ComponentName(this, HexClipboardService::class.java)
            val full = cn.flattenToString()
            val short = cn.flattenToShortString()
            enabled.split(':').any {
                it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun stopOverlay(announce: Boolean) {
        overlayActive = false
        HexClipboardService.onScreenChanged = null
        handler.removeCallbacks(refreshRunnable)
        clearOverlays()
        lastHits = emptyList()

        if (announce) {
            Toast.makeText(this, "Terjemah layar MATI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScreenChanged(scrolled: Boolean) {
        if (langOverlayActive) {
            // Saat scroll, kotak terjemahan lama sudah salah posisi: sembunyikan dulu.
            if (scrolled && langOverlayViews.isNotEmpty()) {
                clearLangOverlays()
                lastLangDrawn = emptyList()
            }
            return
        }
        if (!overlayActive) return

        // Saat scroll, kotak lama sudah salah posisi: sembunyikan dulu.
        // Kotak baru digambar oleh pemindaian berkala berikutnya.
        if (scrolled && overlayViews.isNotEmpty()) {
            clearOverlays()
            lastHits = emptyList()
        }
    }

    private fun refreshOverlay() {
        if (!overlayActive) return

        val scanner = HexClipboardService.instance
        if (scanner == null) {
            stopOverlay(false)
            Toast.makeText(
                this,
                "Layanan Aksesibilitas terputus. Terjemah layar dimatikan.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val hits = try {
            scanner.scanScreen()
        } catch (e: Exception) {
            Log.e(TAG, "Pemindaian gagal", e)
            return
        }

        // Tidak ada perubahan: jangan gambar ulang (mencegah berkedip).
        if (hits == lastHits && (hits.isEmpty() || overlayViews.isNotEmpty())) return

        lastHits = hits
        clearOverlays()
        for (hit in hits) addOverlay(hit)
    }

    private fun addOverlay(hit: HexClipboardService.ScreenHit) {
        val width = (hit.right - hit.left).coerceAtLeast(dp(48))
        val height = (hit.bottom - hit.top).coerceAtLeast(dp(24))

        val tv = TextView(this).apply {
            text = hit.decoded
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                setColor(HexTheme.accentDark)
                cornerRadius = dp(4).toFloat()
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 9, 16, 1, TypedValue.COMPLEX_UNIT_SP
            )
            setOnClickListener {
                startClipboardActivity(ClipboardActivity.MODE_COPY, hit.decoded)
            }
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = hit.left
            y = hit.top
        }

        try {
            windowManager.addView(tv, params)
            overlayViews.add(tv)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menambah overlay: ${e.message}")
        }
    }

    private fun currentTranslateDirection(): LanguageTranslator.Direction =
        if (HexSettings.getTranslateDirection(this) == "ID_TO_EN")
            LanguageTranslator.Direction.ID_TO_EN
        else
            LanguageTranslator.Direction.EN_TO_ID

    private fun toggleLanguageOverlay() {
        if (langOverlayActive) stopLanguageOverlay(true) else startLanguageOverlay()
    }

    private fun startLanguageOverlay() {
        if (HexClipboardService.instance == null) {
            val msg = if (isAccessibilityEnabled()) {
                "Layanan Aksesibilitas HEXTOOL aktif tapi belum tersambung. Matikan lalu nyalakan lagi."
            } else {
                "Aktifkan layanan Aksesibilitas HEXTOOL dulu"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membuka pengaturan aksesibilitas: " + e.message)
            }
            return
        }

        if (overlayActive) stopOverlay(false)

        hidePanel()
        langOverlayActive = true
        lastLangHits = emptyList()

        HexClipboardService.onScreenChanged = { scrolled -> onScreenChanged(scrolled) }

        langCache.clear()
        langErrorShown = false
        lastLangDrawn = emptyList()

        val dirLabel = if (currentTranslateDirection() == LanguageTranslator.Direction.EN_TO_ID) "EN -> ID" else "ID -> EN"
        Toast.makeText(this, "Terjemah Bahasa (" + dirLabel + ") NYALA.", Toast.LENGTH_LONG).show()

        handler.removeCallbacks(langRefreshRunnable)
        handler.postDelayed(langRefreshRunnable, 250)
    }

    private fun stopLanguageOverlay(announce: Boolean) {
        langOverlayActive = false
        HexClipboardService.onScreenChanged = null
        handler.removeCallbacks(langRefreshRunnable)
        clearLangOverlays()
        lastLangHits = emptyList()
        if (announce) {
            Toast.makeText(this, "Terjemah Bahasa MATI", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLangOverlay() {
        if (!langOverlayActive) return

        val scanner = HexClipboardService.instance
        if (scanner == null) {
            stopLanguageOverlay(false)
            Toast.makeText(this, "Layanan Aksesibilitas terputus. Terjemah Bahasa dimatikan.", Toast.LENGTH_LONG).show()
            return
        }

        val hits = try {
            scanner.scanScreenText()
        } catch (e: Exception) {
            Log.e(TAG, "Pemindaian teks gagal", e)
            return
        }

        lastLangHits = hits
        val direction = currentTranslateDirection()
        if (langCache.size > 400) langCache.clear()

        val drawn = ArrayList<Pair<HexClipboardService.TextHit, String>>()
        for (hit in hits) {
            val cacheKey = direction.name + "|" + hit.text
            val cached = langCache[cacheKey]
            if (cached != null) {
                // Lewati jika hasilnya sama dengan teks asli (sudah berbahasa tujuan / gagal).
                if (!cached.trim().equals(hit.text.trim(), ignoreCase = true)) {
                    drawn.add(Pair(hit, cached))
                }
            } else if (langInFlight.add(cacheKey)) {
                LanguageTranslator.translate(direction, hit.text) { result, err ->
                    langInFlight.remove(cacheKey)
                    if (result != null) {
                        langCache[cacheKey] = result
                    } else {
                        // Cegah percobaan ulang tanpa henti; beri tahu sekali per sesi.
                        langCache[cacheKey] = hit.text
                        if (!langErrorShown && langOverlayActive) {
                            langErrorShown = true
                            Toast.makeText(
                                this,
                                "Terjemah gagal: " + (err ?: "tidak diketahui") + ". Unduh model di Settings > Terjemah Bahasa.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        // Tidak ada perubahan: jangan gambar ulang (mencegah berkedip).
        if (drawn == lastLangDrawn && (drawn.isEmpty() || langOverlayViews.isNotEmpty())) return

        lastLangDrawn = drawn
        clearLangOverlays()
        for (p in drawn) addLangOverlay(p.first, p.second)
    }

    private fun addLangOverlay(hit: HexClipboardService.TextHit, translated: String) {
        val width = (hit.right - hit.left).coerceAtLeast(dp(48))
        val height = (hit.bottom - hit.top).coerceAtLeast(dp(24))

        val tv = TextView(this).apply {
            text = translated
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                setColor(HexTheme.accentDark)
                cornerRadius = dp(4).toFloat()
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 9, 16, 1, TypedValue.COMPLEX_UNIT_SP
            )
            setOnClickListener {
                startClipboardActivity(ClipboardActivity.MODE_COPY, translated)
            }
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = hit.left
            y = hit.top
        }

        try {
            windowManager.addView(tv, params)
            langOverlayViews.add(tv)
        } catch (e: Exception) {
            Log.w(TAG, "Gagal menambah overlay bahasa: " + e.message)
        }
    }

    private fun clearLangOverlays() {
        for (v in langOverlayViews) {
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        langOverlayViews.clear()
    }

    private fun clearOverlays() {
        for (v in overlayViews) {
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
                // Jendela mungkin sudah dilepas oleh sistem.
            }
        }
        overlayViews.clear()
    }

    // ---------------- Clipboard ----------------

    // text == null berarti ClipboardActivity membaca clipboard sendiri.
    // Validasi dilakukan di ClipboardActivity supaya aturan hanya ada satu tempat.
    private fun startClipboardActivity(mode: String, text: String?) {
        if (text != null && text.isEmpty()) {
            Toast.makeText(this, "Kolom input kosong", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(this, ClipboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(ClipboardActivity.EXTRA_MODE, mode)
                if (text != null) putExtra(ClipboardActivity.EXTRA_TEXT, text)
            }
            startActivity(intent)
            Log.i(TAG, "ClipboardActivity dimulai; mode=$mode, dariInput=${text != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai ClipboardActivity", e)
            Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Menu bubble ----------------

    private fun toggleMenu() {
        if (menuView != null) {
            hideMenuAnimated()
        } else if (panelView == null) {
            // Ketuk bubble saat menu terbuka memicu ACTION_OUTSIDE (menu tertutup)
            // lalu ACTION_UP di sini; abaikan agar menu tidak langsung terbuka lagi.
            if (android.os.SystemClock.uptimeMillis() - lastOutsideCloseMs < 400) return
            showMenu()
        }
    }

    // ---------------- RADIAL_MENU_V1: menu melingkar mengelilingi bubble ----------------
    private class MenuEntry(
        val icon: String,
        val label: String,
        val active: Boolean,
        val action: () -> Unit
    )

    private class MenuSlot(
        val view: View,
        val params: WindowManager.LayoutParams,
        var dx: Float, // offset saat ini dari pusat bubble
        var dy: Float,
        var tx: Float, // offset tujuan
        var ty: Float
    )

    private val menuSlots = ArrayList<MenuSlot>()
    private var menuAnim: android.animation.ValueAnimator? = null
    private var menuClosing = false
    private var bubbleTouching = false
    private var itemTouching = false

    // Sentuhan di luar menu menutup menu, kecuali sentuhan itu pada bubble / item menu.
    private val outsideCloseRunnable = Runnable {
        if (!bubbleTouching && !itemTouching) {
            lastOutsideCloseMs = android.os.SystemClock.uptimeMillis()
            hideMenuAnimated()
        }
    }

    private fun bubbleCenterX() = bubbleParams.x + bubbleParams.width / 2
    private fun bubbleCenterY() = bubbleParams.y + bubbleParams.height / 2

    // Hitung posisi tiap item (offset dari pusat bubble). Pakai busur terpanjang yang
    // masih muat di layar; kalau terlalu sempit (dekat tepi/pojok) pakai dua lingkaran.
    private fun computeMenuOffsets(n: Int, cx: Int, cy: Int, r1: Int): List<IntArray> {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val hw = dp(26)
        val hh = dp(29)
        val mg = dp(6)
        val minChord = dp(54).toDouble()

        fun valid(x: Double, y: Double) =
            x >= hw + mg && x <= w - hw - mg && y >= hh + mg && y <= h - hh - mg

        fun bestArc(r: Int): Pair<Double, Double> {
            val ok = BooleanArray(360) { a ->
                val rad = Math.toRadians(a.toDouble())
                valid(cx + r * Math.cos(rad), cy + r * Math.sin(rad))
            }
            val first = ok.indexOfFirst { !it }
            if (first < 0) return Pair(-90.0, 360.0)
            var bestStart = 0
            var bestLen = 0
            var start = 0
            var len = 0
            for (k in 1..360) {
                val idx = (first + k) % 360
                if (ok[idx]) {
                    if (len == 0) start = idx
                    len++
                    if (len > bestLen) {
                        bestLen = len
                        bestStart = start
                    }
                } else {
                    len = 0
                }
            }
            return Pair(bestStart.toDouble(), maxOf(bestLen - 1, 0).toDouble())
        }

        fun angles(r: Int, count: Int): List<Double> {
            val (start, len) = bestArc(r)
            return when {
                count <= 0 -> emptyList()
                len >= 360.0 -> List(count) { start + 360.0 * it / count }
                count == 1 -> listOf(start + len / 2)
                else -> List(count) { start + len * it / (count - 1) }
            }
        }

        fun chord(r: Int, count: Int): Double {
            val (_, len) = bestArc(r)
            if (len >= 360.0) return 2 * r * Math.sin(Math.PI / count)
            if (count <= 1) return Double.MAX_VALUE
            return 2 * r * Math.sin(Math.toRadians(len / (count - 1) / 2))
        }

        val out = ArrayList<IntArray>()
        fun add(r: Int, a: Double) {
            val rad = Math.toRadians(a)
            out.add(intArrayOf((r * Math.cos(rad)).toInt(), (r * Math.sin(rad)).toInt()))
        }

        if (chord(r1, n) >= minChord) {
            angles(r1, n).forEach { add(r1, it) }
        } else {
            val r2 = r1 + dp(62)
            val n1 = n / 2
            val n2 = n - n1
            val a1 = angles(r1, n1)
            val a2 = angles(r2, n2)
            for (i in 0 until maxOf(a1.size, a2.size)) {
                if (i < a2.size) add(r2, a2[i])
                if (i < a1.size) add(r1, a1[i])
            }
        }
        return out
    }

    private fun makeMenuItemView(e: MenuEntry): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // ICON_PLUGIN_V1: pakai ikon gambar dari tema (kunci = label huruf kecil) kalau ada,
        // kalau tidak jatuh ke emoji.
        val themed = HexTheme.iconBitmap(e.label.lowercase())
        val circleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (e.active) HexTheme.accentDim else HexTheme.panelBg)
            setStroke(if (e.active) 3 else 2, HexTheme.accent)
        }
        if (themed != null) {
            col.addView(
                android.widget.ImageView(this).apply {
                    setImageBitmap(themed)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = circleBg
                },
                LinearLayout.LayoutParams(dp(40), dp(40))
            )
        } else {
            col.addView(
                TextView(this).apply {
                    text = e.icon
                    textSize = 18f
                    gravity = Gravity.CENTER
                    background = circleBg
                },
                LinearLayout.LayoutParams(dp(40), dp(40))
            )
        }
        col.addView(
            TextView(this).apply {
                text = e.label
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
                setPadding(0, dp(2), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return col
    }

    private fun menuRadius(): Int {
        val bubblePx = if (floatingView.width > 0) floatingView.width else bubbleParams.width
        return maxOf(dp(80), bubblePx / 2 + dp(42))
    }

    // Hitung ulang posisi tujuan tiap item sesuai posisi bubble sekarang.
    private fun retargetMenu() {
        val offs = computeMenuOffsets(
            menuSlots.size, bubbleCenterX(), bubbleCenterY(), menuRadius()
        )
        for ((i, s) in menuSlots.withIndex()) {
            if (i < offs.size) {
                s.tx = offs[i][0].toFloat()
                s.ty = offs[i][1].toFloat()
            }
        }
    }

    private fun applyMenuSlot(s: MenuSlot, scale: Float, alpha: Float) {
        s.params.x = bubbleCenterX() + s.dx.toInt() - s.params.width / 2
        s.params.y = bubbleCenterY() + s.dy.toInt() - s.params.height / 2
        s.view.alpha = alpha
        s.view.scaleX = scale
        s.view.scaleY = scale
        try {
            windowManager.updateViewLayout(s.view, s.params)
        } catch (_: Exception) {
        }
    }

    // t: 0 = semua item menumpuk di pusat bubble, 1 = di posisi tujuan.
    private fun animateMenu(
        from: Float,
        to: Float,
        durationMs: Long,
        interp: android.animation.TimeInterpolator,
        onEnd: (() -> Unit)? = null
    ) {
        menuAnim?.cancel()
        val a = android.animation.ValueAnimator.ofFloat(from, to)
        a.duration = durationMs
        a.interpolator = interp
        a.addUpdateListener { va ->
            val t = va.animatedValue as Float
            for (s in menuSlots) {
                s.dx = s.tx * t
                s.dy = s.ty * t
                applyMenuSlot(s, 0.5f + 0.5f * t, (t * 1.4f).coerceIn(0f, 1f))
            }
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return
                onEnd?.invoke()
            }
        })
        menuAnim = a
        a.start()
    }

    private fun showMenu() {
        if (menuSlots.isNotEmpty()) return

        val entries = listOf(
            MenuEntry("\uD83E\uDDE9", "Panel", false) { showPanel() },
            MenuEntry("\uD83D\uDD13", "Decode", overlayActive) { toggleScreenOverlay() },
            MenuEntry("\uD83C\uDF10", "Bahasa", langOverlayActive) { toggleLanguageOverlay() },
            MenuEntry("\u2702\uFE0F", "Crop", false) { startCropOcr() },
            MenuEntry("\uD83C\uDFAF", "Target", false) { openMainActivity(1) },
            MenuEntry("\uD83D\uDCDC", "Log", false) { openMainActivity(2) },
            MenuEntry("\u2699\uFE0F", "Atur", false) { openMainActivity(3) },
            MenuEntry("\u2716\uFE0F", "Tutup", false) { stopSelf() }
        )

        val cx = bubbleCenterX()
        val cy = bubbleCenterY()
        val offsets = computeMenuOffsets(entries.size, cx, cy, menuRadius())

        val itemW = dp(52)
        val itemH = dp(58)

        for ((i, e) in entries.withIndex()) {
            val v = makeMenuItemView(e)
            v.alpha = 0f
            v.scaleX = 0.5f
            v.scaleY = 0.5f
            val p = WindowManager.LayoutParams(
                itemW,
                itemH,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cx - itemW / 2
                y = cy - itemH / 2
            }

            v.setOnClickListener {
                hideMenuAnimated()
                e.action()
            }
            v.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> itemTouching = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> itemTouching = false
                    MotionEvent.ACTION_OUTSIDE -> {
                        handler.removeCallbacks(outsideCloseRunnable)
                        handler.postDelayed(outsideCloseRunnable, 120)
                    }
                }
                false
            }

            try {
                windowManager.addView(v, p)
                menuSlots.add(
                    MenuSlot(v, p, 0f, 0f, offsets[i][0].toFloat(), offsets[i][1].toFloat())
                )
            } catch (ex: Exception) {
                Toast.makeText(this, "Menu gagal dibuka: ${ex.message}", Toast.LENGTH_LONG).show()
                break
            }
        }
        menuView = menuSlots.firstOrNull()?.view
        animateMenu(0f, 1f, 220, android.view.animation.OvershootInterpolator(0.9f))
    }

    // Dipanggil tiap bubble bergerak: susunan dihitung ulang agar tetap di dalam layar,
    // item mengejar posisi tujuan dengan easing supaya tidak melompat.
    private fun updateMenuPosition() {
        if (menuSlots.isEmpty() || menuClosing) return
        menuAnim?.cancel()
        retargetMenu()
        for (s in menuSlots) {
            s.dx += (s.tx - s.dx) * 0.3f
            s.dy += (s.ty - s.dy) * 0.3f
            applyMenuSlot(s, 1f, 1f)
        }
    }

    // Setelah bubble dilepas: item meluncur halus ke posisi akhir.
    private fun settleMenu() {
        if (menuSlots.isEmpty() || menuClosing) return
        retargetMenu()
        menuAnim?.cancel()
        val sx = menuSlots.map { it.dx }
        val sy = menuSlots.map { it.dy }
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = 160
        a.interpolator = android.view.animation.DecelerateInterpolator()
        a.addUpdateListener { va ->
            val t = va.animatedValue as Float
            for ((i, s) in menuSlots.withIndex()) {
                s.dx = sx[i] + (s.tx - sx[i]) * t
                s.dy = sy[i] + (s.ty - sy[i]) * t
                applyMenuSlot(s, 1f, 1f)
            }
        }
        menuAnim = a
        a.start()
    }

    private fun hideMenuAnimated() {
        if (menuSlots.isEmpty()) {
            hideMenu()
            return
        }
        if (menuClosing) return
        menuClosing = true
        handler.removeCallbacks(outsideCloseRunnable)
        for (s in menuSlots) {
            s.tx = s.dx
            s.ty = s.dy
        }
        animateMenu(1f, 0f, 140, android.view.animation.AccelerateInterpolator()) { hideMenu() }
    }

    private fun hideMenu() {
        handler.removeCallbacks(outsideCloseRunnable)
        menuAnim?.cancel()
        menuAnim = null
        for (s in menuSlots) {
            try {
                windowManager.removeView(s.view)
            } catch (_: Exception) {
                // Item mungkin sudah dilepas oleh sistem.
            }
        }
        menuSlots.clear()
        menuView = null
        menuClosing = false
        itemTouching = false
    }

    private fun startCropOcr() {
        floatingView.visibility = View.INVISIBLE
        // Pengaman terakhir: bubble tidak boleh tersembunyi selamanya.
        handler.postDelayed({
            if (::floatingView.isInitialized) floatingView.visibility = View.VISIBLE
        }, 20000)
        handler.postDelayed({
            try {
                ScreenCropOcr.capture(this, windowManager, overlayType) {
                    floatingView.visibility = View.VISIBLE
                }
            } catch (e: Throwable) {
                // Bubble jangan sampai hilang kalau crop gagal.
                Log.e(TAG, "Crop gagal", e)
                floatingView.visibility = View.VISIBLE
                Toast.makeText(this, "Crop gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, 300)
    }

    // Target, Log, dan Pengaturan sementara membuka layar utama aplikasi.
    private fun openMainActivity(tab: Int = 0) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_TAB, tab)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuka MainActivity", e)
            Toast.makeText(this, "Gagal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        val view = panelView ?: return

        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Panel mungkin sudah dilepas oleh sistem.
        }

        panelView = null
    }

    override fun onDestroy() {
        running = false
        stopLanguageOverlay(false) // LANG_FIX_V1
        stopOverlay(false)
        handler.removeCallbacksAndMessages(null)
        hideMenu()

        HexSettings.prefs(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)

        hidePanel()

        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: Exception) {
                // View mungkin sudah dilepas.
            }
        }
        bubbleAdded = false

        super.onDestroy()
    }
}
// LANG_OVERLAY_V1