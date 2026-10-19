package com.example.hextool

import android.content.Context
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// AES_CRYPTO_V1
// Enkripsi AES-GCM dengan kunci diturunkan dari passphrase lewat PBKDF2.
// Format HEX hasil: salt(16 byte) + iv(12 byte) + ciphertext+tag, semua digabung lalu di-HEX-kan,
// supaya tetap kompatibel tampil sebagai "HEX" seperti prefix 3301 yang sudah ada.
object AesCipher {

    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    fun encryptToHex(plainText: String, passphrase: String): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return bytesToHex(salt + iv + cipherBytes)
    }

    sealed class DecryptResult {
        data class Success(val text: String) : DecryptResult()
        object InvalidHex : DecryptResult()
        object TooShort : DecryptResult()
        object WrongKeyOrCorrupt : DecryptResult()
    }

    fun decryptFromHex(hex: String, passphrase: String): DecryptResult {
        val clean = hex.filter { !it.isWhitespace() }
        val allHex = clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (clean.isEmpty() || clean.length % 2 != 0 || !allHex) return DecryptResult.InvalidHex

        val bytes = hexToBytes(clean)
        if (bytes.size < SALT_LENGTH + IV_LENGTH + 1) return DecryptResult.TooShort

        val salt = bytes.copyOfRange(0, SALT_LENGTH)
        val iv = bytes.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val cipherBytes = bytes.copyOfRange(SALT_LENGTH + IV_LENGTH, bytes.size)

        return try {
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plain = cipher.doFinal(cipherBytes)
            DecryptResult.Success(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            // Kunci salah / data rusak -- ini wajar terjadi, bukan bug.
            DecryptResult.WrongKeyOrCorrupt
        }
    }

    // AES_CACHE_V1: PBKDF2 100 ribu iterasi itu berat. Jangan diulang untuk teks yang sama,
    // dan jangan dijalankan di main thread saat pemindaian layar.
    private val resultCache = HashMap<String, String?>()
    private val inFlight = HashSet<String>()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Dipakai pemindai layar (dipanggil tiap ~1,2 detik):
    // - sudah ada di cache -> kembalikan teks (null jika dulu gagal)
    // - belum ada          -> jadwalkan dekripsi di background, kembalikan null;
    //                         hasilnya terpakai pada siklus pemindaian berikutnya.
    fun decryptCachedAsync(hex: String, passphrase: String): String? {
        val key = passphrase + "\u0000" + hex
        synchronized(resultCache) {
            if (resultCache.containsKey(key)) return resultCache[key]
            if (!inFlight.add(key)) return null
        }
        executor.execute {
            val r = decryptFromHex(hex, passphrase)
            val plain = (r as? DecryptResult.Success)?.text
            synchronized(resultCache) {
                if (resultCache.size > 200) resultCache.clear()
                resultCache[key] = plain
                inFlight.remove(key)
            }
        }
        return null
    }

    // AES_KEYS_V1: kunci per target.
    private fun targetMatches(t: TargetModel, text: String): Boolean =
        (t.name.isNotBlank() && text.contains(t.name, ignoreCase = true)) ||
            (t.identifier.isNotBlank() && text.contains(t.identifier, ignoreCase = true))

    // Kandidat passphrase untuk MENDEKRIPSI. Urutan: kunci target yang cocok dengan pengirim
    // (jika diketahui), passphrase global, lalu kunci target lain. Tanpa duplikat, maksimal 8.
    fun candidateKeys(context: Context, senderHint: String = ""): List<String> {
        val out = LinkedHashSet<String>()
        val targets = HexLogDatabase.getTargets(context)

        if (senderHint.isNotBlank()) {
            for (t in targets) {
                val k = t.aesKey
                if (!k.isNullOrBlank() && targetMatches(t, senderHint)) out.add(k)
            }
        }

        val global = HexSettings.getAesPassphraseGlobal(context)
        if (global.isNotBlank()) out.add(global)

        for (t in targets) {
            val k = t.aesKey
            if (!k.isNullOrBlank()) out.add(k)
        }
        return out.toList().take(8)
    }

    // Pilihan kunci untuk MENGENKRIPSI: (label, passphrase).
    fun encryptionChoices(context: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val global = HexSettings.getAesPassphraseGlobal(context)
        if (global.isNotBlank()) out.add(Pair("Passphrase global", global))
        for (t in HexLogDatabase.getTargets(context)) {
            val k = t.aesKey
            if (!k.isNullOrBlank()) out.add(Pair("Target: " + t.name, k))
        }
        return out
    }

    // Versi cache + background untuk pemindai layar, mencoba beberapa kunci berurutan
    // dalam SATU tugas dan berhenti di kunci pertama yang berhasil.
    fun decryptCachedAsyncAny(hex: String, passphrases: List<String>): String? {
        val keys = passphrases.filter { it.isNotBlank() }
        if (keys.isEmpty()) return null

        val cacheKey = keys.joinToString("\u0001") + "\u0000" + hex
        synchronized(resultCache) {
            if (resultCache.containsKey(cacheKey)) return resultCache[cacheKey]
            if (!inFlight.add(cacheKey)) return null
        }
        executor.execute {
            var plain: String? = null
            for (p in keys) {
                val r = decryptFromHex(hex, p)
                if (r is DecryptResult.Success) {
                    plain = r.text
                    break
                }
            }
            synchronized(resultCache) {
                if (resultCache.size > 200) resultCache.clear()
                resultCache[cacheKey] = plain
                inFlight.remove(cacheKey)
            }
        }
        return null
    }

    // Coba beberapa kandidat passphrase berurutan (global dulu, lalu per-target).
    // Dipakai nanti di tahap 3 saat konteks pengirim tidak diketahui (overlay/paste manual).
    fun tryDecryptWithKeys(hex: String, passphrases: List<String>): Pair<String, String>? {
        for (p in passphrases) {
            if (p.isBlank()) continue
            val result = decryptFromHex(hex, p)
            if (result is DecryptResult.Success) return result.text to p
        }
        return null
    }
}