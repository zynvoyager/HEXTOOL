package com.example.hextool

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast

class ClipboardActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "hextool_input_text"
        const val EXTRA_MODE = "hextool_mode"
        const val MODE_ENCODE = "encode"
        const val MODE_DECODE = "decode"
        const val MODE_COPY = "copy"
        private const val TAG = "ClipboardActivity"
    }

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            TAG,
            "onCreate; mode=${intent.getStringExtra(EXTRA_MODE) ?: MODE_ENCODE}, " +
                "bacaClipboard=${!intent.hasExtra(EXTRA_TEXT)}"
        )
    }

    // Akses clipboard hanya diizinkan saat jendela punya fokus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(TAG, "onWindowFocusChanged hasFocus=$hasFocus")

        if (hasFocus && !handled) {
            handled = true
            process()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun readClipboardText(cm: ClipboardManager): String? {
        return try {
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membaca clipboard", e)
            null
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(
            this,
            msg,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun process() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ENCODE

        // Teks dari panel, atau (jika tidak ada) dari clipboard.
        val input: String = intent.getStringExtra(EXTRA_TEXT)
            ?: readClipboardText(cm)
            ?: run {
                Log.i(TAG, "Clipboard kosong atau tidak terbaca")
                toast("Clipboard kosong atau tidak terbaca")
                return
            }

        // Isi teks tidak dilog, hanya panjangnya.
        Log.i(TAG, "mode=$mode, inputLength=${input.length}")

        val output: String = when (mode) {
            MODE_COPY -> input

            MODE_DECODE -> when (val r = HexConverter.decode(input)) {
                is HexConverter.DecodeResult.Success -> r.text
                is HexConverter.DecodeResult.Empty -> {
                    toast("Tidak ada kode HEX"); return
                }
                is HexConverter.DecodeResult.InvalidHex -> {
                    toast("Bukan kode HEX yang valid", true); return
                }
                is HexConverter.DecodeResult.InvalidUtf8 -> {
                    toast("HEX valid, tapi bukan teks UTF-8", true); return
                }
            }

            else -> when (val r = HexConverter.convert(input)) {
                is HexConverter.Result.Success -> r.hex
                is HexConverter.Result.NoPrefix -> {
                    toast("Teks harus diawali 3301"); return
                }
                is HexConverter.Result.EmptyPayload -> {
                    toast("Teks setelah prefix 3301 kosong"); return
                }
            }
        }

        try {
            cm.setPrimaryClip(ClipData.newPlainText("HEXTOOL", output))

            val readBack = readClipboardText(cm)
            val ok = readBack == output
            Log.i(
                TAG,
                "setPrimaryClip selesai; outputLength=${output.length}, " +
                    "bacaUlangNull=${readBack == null}, cocok=$ok"
            )

            val msg = when (mode) {
                MODE_DECODE -> "Teks disalin: ${output.take(60)}"
                MODE_COPY -> "Teks disalin"
                else -> "HEX disalin (${output.length} karakter)"
            }
            toast(msg, true)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menulis clipboard", e)
            toast("Gagal menyalin: ${e.message}", true)
        }
    }
}