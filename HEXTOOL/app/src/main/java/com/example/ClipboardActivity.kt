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
        const val MODE_ENCRYPT = "encrypt" // AES_PANEL_V1
        private const val AES_PREFIX = "3302"
        private const val TAG = "ClipboardActivity"
    }

    private var handled = false
    private var keepOpen = false // AES_KEYS_V1: true saat dialog pilih-kunci tampil

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
            if (!keepOpen) {
                finish()
                overridePendingTransition(0, 0)
            }
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

    private fun encryptAndCopy(cm: ClipboardManager, input: String, pass: String) {
        try {
            val output = AES_PREFIX + AesCipher.encryptToHex(input, pass)
            cm.setPrimaryClip(ClipData.newPlainText("HEXTOOL", output))
            toast("HEX terenkripsi disalin (${output.length} karakter)", true)
        } catch (e: Exception) {
            toast("Enkripsi/salin gagal: ${e.message}", true)
        }
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

        // AES_PANEL_V1: dicegat sebelum jalur HEX biasa, supaya kode lama tidak disentuh.
        if (mode == MODE_ENCRYPT) {
            if (input.isBlank()) { toast("Teks kosong"); return }
            val choices = AesCipher.encryptionChoices(this) // AES_KEYS_V1
            if (choices.isEmpty()) {
                toast("Set passphrase AES dulu (Settings atau kunci target)", true)
                return
            }
            if (choices.size == 1) {
                encryptAndCopy(cm, input, choices[0].second)
                return
            }
            // Ada beberapa kunci: biarkan pengguna memilih.
            keepOpen = true
            val labels = choices.map { it.first }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Enkripsi dengan kunci...")
                .setItems(labels) { _, which -> encryptAndCopy(cm, input, choices[which].second) }
                .setOnDismissListener {
                    finish()
                    overridePendingTransition(0, 0)
                }
                .show()
            return
        }
        if (mode == MODE_DECODE && input.startsWith(AES_PREFIX)) {
            val keys = AesCipher.candidateKeys(this) // AES_KEYS_V1
            if (keys.isEmpty()) { toast("Set passphrase AES dulu (Settings atau kunci target)", true); return }
            val hexPart = input.substring(AES_PREFIX.length)
            val found = AesCipher.tryDecryptWithKeys(hexPart, keys)
            if (found != null) {
                try {
                    cm.setPrimaryClip(ClipData.newPlainText("HEXTOOL", found.first))
                    toast("Teks (AES) disalin: ${found.first.take(60)}", true)
                } catch (e: Exception) {
                    toast("Gagal menyalin: ${e.message}", true)
                }
            } else {
                toast("Gagal dekripsi: kunci salah atau data rusak", true)
            }
            return
        }

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

            // ENCODE_PLAIN_V1: prefix 3301 ditambahkan otomatis kalau belum ada,
            // jadi cukup ketik teks biasa lalu tekan ENCODE.
            else -> when (val r = HexConverter.convert(
                if (input.startsWith(HexConverter.PREFIX)) input else HexConverter.PREFIX + input
            )) {
                is HexConverter.Result.Success -> r.hex
                is HexConverter.Result.NoPrefix -> {
                    toast("Teks tidak valid"); return
                }
                is HexConverter.Result.EmptyPayload -> {
                    toast("Teks kosong"); return
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