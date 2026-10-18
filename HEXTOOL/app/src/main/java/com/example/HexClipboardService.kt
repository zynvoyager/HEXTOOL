package com.example.hextool

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

// Nama class dipertahankan agar manifest tidak berubah.
// Fungsi: membaca teks di layar dan mencari kode HEX untuk didecode.
class HexClipboardService : AccessibilityService() {

    data class TextHit(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class ScreenHit(
        val decoded: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    companion object {
        private const val TAG = "HexScreenScanner"

        private const val MIN_HEX_CHARS = 6
        private const val MAX_NODES = 3000
        private const val MAX_DEPTH = 60
        private const val MAX_HITS = 40
        private const val MIN_TEXT_CHARS = 4 // LANG_OVERLAY_V1
        private const val MAX_TEXT_CHARS = 200
        private const val MAX_TEXT_HITS = 25

        @Volatile
        var instance: HexClipboardService? = null
            private set

        // Dipanggil saat layar aplikasi lain berubah. Parameter true = karena scroll.
        @Volatile
        var onScreenChanged: ((Boolean) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    // Memindai semua jendela aplikasi (selain HEXTOOL sendiri).
    fun scanScreen(): List<ScreenHit> {
        val roots = ArrayList<AccessibilityNodeInfo>()

        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = w.root
                    if (r != null) {
                        if (r.packageName?.toString() == packageName) {
                            safeRecycle(r)
                        } else {
                            roots.add(r)
                        }
                    }
                }
                safeRecycle(w)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membaca daftar jendela: ${e.message}")
        }

        if (roots.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null) {
                if (r.packageName?.toString() == packageName) safeRecycle(r) else roots.add(r)
            }
        }

        val hits = ArrayList<ScreenHit>()
        val seen = HashSet<String>()
        val counter = intArrayOf(0)

        for (root in roots) {
            walk(root, 0, hits, seen, counter)
            safeRecycle(root)
        }

        Log.i(
            TAG,
            "Scan selesai: jendela=${roots.size}, node dipindai=${counter[0]}, hex ditemukan=${hits.size}"
        )

        return hits.sortedBy { it.top }.take(MAX_HITS)
    }

    // Memindai teks biasa (bukan HEX) untuk fitur Terjemah Bahasa.
    fun scanScreenText(): List<TextHit> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val r = w.root
                    if (r != null) {
                        if (r.packageName?.toString() == packageName) safeRecycle(r) else roots.add(r)
                    }
                }
                safeRecycle(w)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal membaca daftar jendela (teks): " + e.message)
        }
        if (roots.isEmpty()) {
            val r = rootInActiveWindow
            if (r != null) {
                if (r.packageName?.toString() == packageName) safeRecycle(r) else roots.add(r)
            }
        }

        val hits = ArrayList<TextHit>()
        val seen = HashSet<String>()
        val counter = intArrayOf(0)
        for (root in roots) {
            walkText(root, 0, hits, seen, counter)
            safeRecycle(root)
        }
        return hits.sortedBy { it.top }.take(MAX_TEXT_HITS)
    }

    private fun walkText(
        node: AccessibilityNodeInfo,
        depth: Int,
        hits: MutableList<TextHit>,
        seen: MutableSet<String>,
        counter: IntArray
    ) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH || hits.size >= MAX_TEXT_HITS) return
        counter[0]++

        if (node.isVisibleToUser) {
            checkPlainText(node.text, node, hits, seen)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkText(child, depth + 1, hits, seen, counter)
            safeRecycle(child)
        }
    }

    private fun checkPlainText(
        cs: CharSequence?,
        node: AccessibilityNodeInfo,
        hits: MutableList<TextHit>,
        seen: MutableSet<String>
    ) {
        val candidate = cs?.toString()?.trim() ?: return
        if (candidate.length < MIN_TEXT_CHARS || candidate.length > MAX_TEXT_CHARS) return

        val letters = candidate.count { it.isLetter() }
        if (letters < candidate.length / 2) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return

        val key = rect.left.toString() + "," + rect.top + "," + rect.right + "," + rect.bottom
        if (!seen.add(key)) return

        hits.add(TextHit(candidate, rect.left, rect.top, rect.right, rect.bottom))
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        hits: MutableList<ScreenHit>,
        seen: MutableSet<String>,
        counter: IntArray
    ) {
        if (counter[0] >= MAX_NODES || depth > MAX_DEPTH) return
        counter[0]++

        if (node.isVisibleToUser) {
            checkText(node.text, node, hits, seen)
            checkText(node.contentDescription, node, hits, seen)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, hits, seen, counter)
            safeRecycle(child)
        }
    }

    private fun checkText(
        cs: CharSequence?,
        node: AccessibilityNodeInfo,
        hits: MutableList<ScreenHit>,
        seen: MutableSet<String>
    ) {
        val candidate = cs?.toString()?.trim() ?: return
        if (candidate.isEmpty()) return

        // HEX boleh dipisah spasi/baris baru, asalkan setiap potongan
        // adalah HEX valid dengan panjang genap (mis. "48 61 6c 6f").
        val tokens = candidate.split(Regex("\\s+"))
        val allTokensHex = tokens.all { t ->
            t.length % 2 == 0 &&
                t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
        if (!allTokensHex) return

        val stripped = tokens.joinToString("")
        if (stripped.length < MIN_HEX_CHARS) return

        // Angka murni hampir selalu tanggal/nomor/jumlah uang, bukan HEX hasil encode.
        if (stripped.all { it in '0'..'9' }) return

        val result = HexConverter.decode(
            stripped,
            allowWhitespace = false,
            rejectControlChars = true
        )
        val text = (result as? HexConverter.DecodeResult.Success)?.text ?: return
        if (text.isBlank()) return

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return

        // Node yang sama bisa dibaca dua kali (text dan contentDescription).
        val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        if (!seen.add(key)) return

        hits.add(ScreenHit(text, rect.left, rect.top, rect.right, rect.bottom))
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun safeRecycle(window: AccessibilityWindowInfo?) {
        try {
            window?.recycle()
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // Abaikan event dari HEXTOOL sendiri (overlay, activity clipboard).
        if (e.packageName?.toString() == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                onScreenChanged?.invoke(true)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                onScreenChanged?.invoke(false)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}