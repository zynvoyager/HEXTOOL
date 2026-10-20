package com.example.hextool

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

// CROP_COMPAT_V1: tangkap layar untuk Android 10 (API 29) ke bawah lewat MediaProjection.
// Android 11+ tetap memakai AccessibilityService.takeScreenshot (tanpa dialog izin).
//
// Alur: ProjectionCapture.start -> ProjectionPermissionActivity (dialog izin sistem)
//       -> ProjectionService (foreground, ambil 1 frame) -> callback berisi Bitmap.
object ProjectionCapture {
    private var pending: ((Bitmap?, String?) -> Unit)? = null

    @Volatile
    private var launched = false

    // Dipanggil ProjectionPermissionActivity.onCreate: tandanya Android mengizinkan activity tampil.
    internal fun markLaunched() { launched = true }

    // Callback selalu dipanggil di main thread, tepat satu kali.
    fun start(ctx: Context, callback: (Bitmap?, String?) -> Unit) {
        pending = callback
        launched = false
        try {
            ctx.startActivity(
                Intent(ctx, ProjectionPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            deliver(null, "Gagal meminta izin tangkap layar: ${e.message}")
            return
        }
        // CROP_FIX_V3: MIUI/Xiaomi, Oppo, Vivo dll. memblokir activity yang dibuka dari latar belakang
        // TANPA error. Kalau dialog izin tak muncul dalam 2 dtk, jangan menunggu selamanya
        // (bubble sebelumnya tetap tersembunyi).
        Handler(Looper.getMainLooper()).postDelayed({
            if (!launched && pending != null) {
                deliver(
                    null,
                    "Dialog izin diblokir sistem. Aktifkan \"Tampilkan jendela pop-up saat " +
                        "berjalan di latar belakang\" untuk HEXTOOL di Info Aplikasi > Izin lainnya"
                )
            }
        }, 2000)
    }

    internal fun deliver(bmp: Bitmap?, error: String?) {
        val cb = pending
        pending = null
        if (cb == null) return
        Handler(Looper.getMainLooper()).post { cb(bmp, error) }
    }
}

class ProjectionPermissionActivity : Activity() {
    private companion object {
        const val REQ = 4711
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProjectionCapture.markLaunched()
        if (savedInstanceState != null) return // diputar ulang: jangan minta dua kali
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
        } catch (e: Exception) {
            ProjectionCapture.deliver(null, "Tangkap layar tidak tersedia: ${e.message}")
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode == RESULT_OK && data != null) {
            try {
                val i = Intent(this, ProjectionService::class.java)
                    .putExtra(ProjectionService.EXTRA_CODE, resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
            } catch (e: Exception) {
                ProjectionCapture.deliver(null, "Gagal memulai tangkap layar: ${e.message}")
            }
        } else {
            ProjectionCapture.deliver(null, "Izin tangkap layar ditolak")
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

class ProjectionService : Service() {

    companion object {
        const val EXTRA_CODE = "projection_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "hextool_capture"
        private const val NOTIF_ID = 4712
        private const val SETTLE_MS = 500L   // beri waktu dialog izin hilang dari layar
        private const val TIMEOUT_MS = 4000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 10+ mewajibkan startForeground dengan tipe mediaProjection SEBELUM getMediaProjection.
        try {
            startAsForeground()
        } catch (e: Exception) {
            finishWith(null, "Gagal memulai layanan tangkap layar: ${e.message}")
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            finishWith(null, "Data izin tangkap layar hilang")
            return START_NOT_STICKY
        }
        handler.postDelayed({ capture(code, data) }, SETTLE_MS)
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Crop layar", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("HEXTOOL")
            .setContentText("Mengambil tangkapan layar…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    @Suppress("DEPRECATION")
    private fun capture(code: Int, data: Intent) {
        if (done) return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            if (p == null) {
                finishWith(null, "Gagal memulai tangkap layar")
                return
            }
            projection = p
            // Wajib didaftarkan sebelum createVirtualDisplay di Android 14+.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {}
            }, handler)

            val dm = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(dm)
            val w = dm.widthPixels
            val h = dm.heightPixels

            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ rd ->
                if (done) return@setOnImageAvailableListener
                val img = try { rd.acquireLatestImage() } catch (_: Exception) { null }
                    ?: return@setOnImageAvailableListener
                val bmp = try { toBitmap(img, w, h) } catch (_: Exception) { null } finally { img.close() }
                finishWith(bmp, if (bmp == null) "Gagal membaca tangkapan layar" else null)
            }, handler)

            display = p.createVirtualDisplay(
                "hextool-crop", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            handler.postDelayed({
                if (!done) finishWith(null, "Waktu tangkap layar habis")
            }, TIMEOUT_MS)
        } catch (e: Exception) {
            finishWith(null, "Tangkap layar gagal: ${e.message}")
        }
    }

    private fun toBitmap(img: Image, w: Int, h: Int): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return exact
    }

    private fun finishWith(bmp: Bitmap?, error: String?) {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
        ProjectionCapture.deliver(bmp, error)
        try { stopForeground(true) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        if (!done) finishWith(null, "Tangkap layar dibatalkan")
        super.onDestroy()
    }
}
