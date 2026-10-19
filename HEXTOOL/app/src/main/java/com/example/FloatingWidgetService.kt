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

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = LayoutInflater.from(this).inflate(
            R.layout.floating_widget_layout,
            null
        )

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
        Log.d(TAG, "Ikon bubble: drawable=${iconView?.drawable}")
        windowManager.addView(floatingView, bubbleParams)
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
                        toggleScreenOverlay()
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
                                hideMenu()
                                bubbleParams.x = startX + dx.toInt()
                                bubbleParams.y = startY + dy.toInt()

                                windowManager.updateViewLayout(
                                    floatingView,
                                    bubbleParams
                                )
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            handler.removeCallbacks(longPress)
                            if (!moved && !longPressed) toggleMenu()
                            return true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            handler.removeCallbacks(longPress)
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

        // Anak pertama pada layout bubble adalah TextView "HEX".
        val label = (floatingView as? ViewGroup)?.getChildAt(0)
        if (label is TextView) {
            label.textSize = sizeDp * 0.25f
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
            hint = "3301... atau kode HEX"
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

        // Teks 3301... -> HEX
        root.addView(
            makeRow(
                makeButton("CONVERT", HexTheme.accent) {
                    startClipboardActivity(
                        ClipboardActivity.MODE_ENCODE,
                        input.text.toString()
                    )
                },
                makeButton("PASTE & CONVERT", HexTheme.accent) {
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

    private fun toggleScreenOverlay() {
        if (overlayActive) stopOverlay(true) else startOverlay()
    }

    private fun startOverlay() {
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
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Gagal membuka pengaturan aksesibilitas: ${e.message}")
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
            hideMenu()
        } else if (panelView == null) {
            // Ketuk bubble saat menu terbuka memicu ACTION_OUTSIDE (menu tertutup)
            // lalu ACTION_UP di sini; abaikan agar menu tidak langsung terbuka lagi.
            if (android.os.SystemClock.uptimeMillis() - lastOutsideCloseMs < 400) return
            showMenu()
        }
    }

    private fun showMenu() {
        if (menuView != null) return

        val items: List<Pair<String, () -> Unit>> = listOf(
            "Panel" to { showPanel() },
            (if (overlayActive) "Terjemahkan (NYALA)" else "Terjemahkan") to { toggleScreenOverlay() },
            "Target" to { openMainActivity() },
            "Log" to { openMainActivity() },
            (if (langOverlayActive) "Terjemah Bahasa (NYALA)" else "Terjemah Bahasa") to { toggleLanguageOverlay() },
            "Pengaturan" to { openMainActivity() },
            "Tutup bubble" to { stopSelf() }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(HexTheme.panelBg)
                setStroke(2, HexTheme.accent)
                cornerRadius = dp(12).toFloat()
            }
        }

        for ((label, action) in items) {
            root.addView(
                TextView(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setOnClickListener {
                        hideMenu()
                        action()
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val metrics = resources.displayMetrics
        val menuW = dp(190)
        val menuH = dp(44) * items.size + dp(12)
        val gap = dp(6)

        var menuX = bubbleParams.x
        if (menuX + menuW > metrics.widthPixels) menuX = metrics.widthPixels - menuW - dp(4)
        if (menuX < 0) menuX = 0

        var menuY = bubbleParams.y + bubbleParams.height + gap
        if (menuY + menuH > metrics.heightPixels) {
            menuY = (bubbleParams.y - menuH - gap).coerceAtLeast(0)
        }

        val params = WindowManager.LayoutParams(
            menuW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = menuX
            this.y = menuY
        }

        // Sentuh di luar menu -> tutup (sentuhan tetap diteruskan ke aplikasi di bawah).
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                lastOutsideCloseMs = android.os.SystemClock.uptimeMillis()
                hideMenu()
            }
            false
        }

        menuView = root
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            menuView = null
            Toast.makeText(this, "Menu gagal dibuka: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideMenu() {
        val view = menuView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // Menu mungkin sudah dilepas oleh sistem.
        }
        menuView = null
    }

    // Target, Log, dan Pengaturan sementara membuka layar utama aplikasi.
    private fun openMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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