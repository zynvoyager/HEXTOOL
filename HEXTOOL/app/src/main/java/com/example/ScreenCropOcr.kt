package com.example.hextool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// Crop layar -> OCR -> salin ke clipboard.
// Android 11+ : AccessibilityService.takeScreenshot (tanpa dialog izin); kalau gagal, jatuh ke MediaProjection.
// Android 10- : MediaProjection lewat ProjectionCapture (CROP_COMPAT_V1), minta izin tiap kali.
// CROP_UI_V2: kotak awal otomatis, bisa digeser/diperluas, tombol SALIN menutup crop.
object ScreenCropOcr {
    private const val TAG = "ScreenCropOcr"

    fun capture(
        ctx: Context,
        wm: WindowManager,
        overlayType: Int,
        onCaptured: () -> Unit
    ) {
        // Bubble HARUS selalu dimunculkan lagi, apa pun yang terjadi (sebelumnya bubble bisa hilang
        // kalau ada error di tengah jalan). Dibungkus agar hanya dipanggil sekali.
        var restored = false
        val restore = {
            if (!restored) {
                restored = true
                try { onCaptured() } catch (_: Exception) {}
            }
        }

        fun fallbackToProjection() {
            ProjectionCapture.start(ctx) { bmp, err ->
                restore()
                if (bmp == null) toast(ctx, err ?: "Gagal mengambil screenshot")
                else showSelector(ctx, wm, overlayType, bmp)
            }
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                fallbackToProjection()
                return
            }
            val svc = HexClipboardService.instance
            if (svc == null) {
                // Tanpa Aksesibilitas, MediaProjection tetap bisa dipakai.
                fallbackToProjection()
                return
            }
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                ctx.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Gagal membaca hasil screenshot", e)
                        } finally {
                            try { result.hardwareBuffer.close() } catch (_: Throwable) {}
                        }
                        if (bmp == null) {
                            // Mis. layar berisi foto HDR / gamut lebar yang tak bisa disalin.
                            fallbackToProjection()
                            return
                        }
                        restore()
                        showSelector(ctx, wm, overlayType, bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot gagal: $errorCode")
                        if (errorCode == 3) { // terlalu cepat setelah screenshot sebelumnya
                            restore()
                            toast(ctx, "Tunggu sebentar lalu coba lagi")
                        } else {
                            fallbackToProjection()
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "capture gagal", e)
            restore()
            toast(ctx, "Crop gagal: ${e.message}")
        }
    }

    private fun showSelector(ctx: Context, wm: WindowManager, type: Int, bmp: Bitmap) {
        try {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            lateinit var root: CropRoot
            fun close() {
                try { wm.removeView(root) } catch (_: Exception) {}
            }

            val crop = CropView(ctx, bmp)
            root = CropRoot(ctx) { close() }
            root.addView(
                crop,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // Petunjuk di atas
            root.addView(
                TextView(ctx).apply {
                    text = "Geser kotak, atau tarik pegangannya untuk memperluas area"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.argb(190, 0, 0, 0))
                        cornerRadius = dp(10).toFloat()
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(48) }
            )

            // Tombol bawah: SALIN + BATAL
            fun pill(label: String, color: Int, onClick: () -> Unit) = Button(ctx).apply {
                text = label
                setTextColor(color)
                textSize = 14f
                minHeight = 0; minimumHeight = 0
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    setColor(HexTheme.panelBg)
                    setStroke(dp(1), color)
                    cornerRadius = dp(14).toFloat()
                }
                setOnClickListener { onClick() }
            }
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    pill("BATAL", Color.LTGRAY) { close() },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(6) }
                )
                addView(
                    pill("SALIN", HexTheme.accent) {
                        val r = crop.selectionInBitmap()
                        close() // crop ditutup begitu SALIN ditekan
                        recognize(ctx, bmp, r)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(6) }
                )
            }
            root.addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply {
                    leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(72)
                }
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(root, params)
            root.requestFocus()
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal membuka crop", e)
            toast(ctx, "Gagal membuka crop: ${e.message}")
        }
    }

    private fun recognize(ctx: Context, src: Bitmap, r: Rect) {
        try {
            val l = r.left.coerceIn(0, src.width - 1)
            val t = r.top.coerceIn(0, src.height - 1)
            val w = r.width().coerceAtMost(src.width - l).coerceAtLeast(1)
            val h = r.height().coerceAtMost(src.height - t).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(src, l, t, w, h)

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { res ->
                    val text = res.text.trim()
                    if (text.isEmpty()) {
                        toast(ctx, "Tidak ada teks terdeteksi")
                    } else {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HEXTOOL OCR", text))
                        toast(ctx, "Tersalin (${text.length} karakter)")
                    }
                }
                .addOnFailureListener { toast(ctx, "OCR gagal: ${it.message}") }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Throwable) {
            Log.e(TAG, "OCR gagal", e)
            toast(ctx, "OCR gagal: ${e.message}")
        }
    }

    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, if (msg.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // Wadah layar penuh; tombol Back menutup crop.
    private class CropRoot(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    // Screenshot beku + kotak seleksi yang bisa digeser dan diubah ukurannya.
    private class CropView(ctx: Context, private val bmp: Bitmap) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        }

        private val sel = RectF()
        private val minSize = 56f * density
        private val grab = 30f * density      // jarak sentuh ke pegangan
        private val handleR = 8f * density

        private var inited = false
        private var mode = Mode.NONE
        private var lastX = 0f
        private var lastY = 0f
        private var anchorX = 0f
        private var anchorY = 0f
        private var backup = RectF()

        private enum class Mode { NONE, MOVE, NEW, TL, TR, BL, BR, L, R, T, B }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!inited && w > 0 && h > 0) {
                inited = true
                // Kotak awal: lebar 80%, tinggi 16%, agak di tengah layar.
                val bw = w * 0.8f
                val bh = (h * 0.16f).coerceAtLeast(minSize * 1.5f)
                val l = (w - bw) / 2f
                val t = h * 0.42f
                sel.set(l, t, l + bw, t + bh)
            }
        }

        fun selectionInBitmap(): Rect {
            val kx = bmp.width / width.toFloat()
            val ky = bmp.height / height.toFloat()
            return Rect(
                (sel.left * kx).toInt(), (sel.top * ky).toInt(),
                (sel.right * kx).toInt(), (sel.bottom * ky).toInt()
            )
        }

        override fun onDraw(c: Canvas) {
            val full = Rect(0, 0, width, height)
            c.drawBitmap(bmp, null, full, null)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            c.save(); c.clipRect(sel)
            c.drawBitmap(bmp, null, full, null)
            c.restore()
            c.drawRect(sel, border)
            for (p in handlePoints()) {
                c.drawCircle(p.second, p.third, handleR, handleFill)
                c.drawCircle(p.second, p.third, handleR, handleRing)
            }
        }

        // (mode, x, y) -- sudut dulu supaya diprioritaskan.
        private fun handlePoints(): List<Triple<Mode, Float, Float>> {
            val cx = (sel.left + sel.right) / 2f
            val cy = (sel.top + sel.bottom) / 2f
            return listOf(
                Triple(Mode.TL, sel.left, sel.top),
                Triple(Mode.TR, sel.right, sel.top),
                Triple(Mode.BL, sel.left, sel.bottom),
                Triple(Mode.BR, sel.right, sel.bottom),
                Triple(Mode.T, cx, sel.top),
                Triple(Mode.B, cx, sel.bottom),
                Triple(Mode.L, sel.left, cy),
                Triple(Mode.R, sel.right, cy)
            )
        }

        private fun pickMode(x: Float, y: Float): Mode {
            var best = Mode.NONE
            var bestD = grab
            for ((m, hx, hy) in handlePoints()) {
                val d = Math.hypot((x - hx).toDouble(), (y - hy).toDouble()).toFloat()
                if (d < bestD) { bestD = d; best = m }
            }
            if (best != Mode.NONE) return best
            return if (sel.contains(x, y)) Mode.MOVE else Mode.NEW
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x.coerceIn(0f, width.toFloat())
            val y = e.y.coerceIn(0f, height.toFloat())
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = pickMode(x, y)
                    lastX = x; lastY = y
                    backup = RectF(sel)
                    if (mode == Mode.NEW) { anchorX = x; anchorY = y }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    when (mode) {
                        Mode.MOVE -> {
                            val nx = dx.coerceIn(-sel.left, width - sel.right)
                            val ny = dy.coerceIn(-sel.top, height - sel.bottom)
                            sel.offset(nx, ny)
                        }
                        Mode.NEW -> sel.set(
                            minOf(anchorX, x), minOf(anchorY, y),
                            maxOf(anchorX, x), maxOf(anchorY, y)
                        )
                        Mode.TL -> { sel.left = x.coerceAtMost(sel.right - minSize); sel.top = y.coerceAtMost(sel.bottom - minSize) }
                        Mode.TR -> { sel.right = x.coerceAtLeast(sel.left + minSize); sel.top = y.coerceAtMost(sel.bottom - minSize) }
                        Mode.BL -> { sel.left = x.coerceAtMost(sel.right - minSize); sel.bottom = y.coerceAtLeast(sel.top + minSize) }
                        Mode.BR -> { sel.right = x.coerceAtLeast(sel.left + minSize); sel.bottom = y.coerceAtLeast(sel.top + minSize) }
                        Mode.L -> sel.left = x.coerceAtMost(sel.right - minSize)
                        Mode.R -> sel.right = x.coerceAtLeast(sel.left + minSize)
                        Mode.T -> sel.top = y.coerceAtMost(sel.bottom - minSize)
                        Mode.B -> sel.bottom = y.coerceAtLeast(sel.top + minSize)
                        Mode.NONE -> {}
                    }
                    lastX = x; lastY = y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Area baru yang terlalu kecil (mis. cuma ketukan) -> kembalikan kotak sebelumnya.
                    if (mode == Mode.NEW && (sel.width() < minSize || sel.height() < minSize)) {
                        sel.set(backup)
                    }
                    mode = Mode.NONE
                }
            }
            invalidate()
            return true
        }
    }
}
