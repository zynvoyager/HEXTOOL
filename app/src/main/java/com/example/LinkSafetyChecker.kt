package com.example.hextool

import java.net.URI
import java.util.Locale

/**
 * Pemeriksa keamanan tautan sepenuhnya lokal (tanpa server / API).
 * Hasil berupa skor risiko + daftar alasan, bukan vonis malware.
 */
object LinkSafetyChecker {

    enum class Level { AMAN, HATI_HATI, MENCURIGAKAN }

    data class Report(
        val level: Level,
        val score: Int,          // 0 = aman, makin tinggi makin berisiko
        val reasons: List<String>,
        val host: String,
        val scheme: String,
        val normalized: String
    )

    // Shortener & redirector umum (lokal, statis)
    private val SHORTENERS = setOf(
        "bit.ly", "bitly.com", "t.co", "goo.gl", "tinyurl.com", "ow.ly",
        "is.gd", "cutt.ly", "rebrand.ly", "shorturl.at", "rb.gy",
        "tiny.cc", "buff.ly", "adf.ly", "shorte.st", "bc.vc",
        "clk.ink", "s.id", "t.ly", "lnkd.in", "trib.al"
    )

    // TLD yang sering disalahgunakan (bukan berarti selalu jahat)
    private val RISKY_TLDS = setOf(
        "tk", "ml", "ga", "cf", "gq", "xyz", "top", "buzz", "work",
        "click", "link", "info", "icu", "cn", "ru", "su"
    )

    // Kata mencurigakan di path/query
    private val SUSPICIOUS_WORDS = listOf(
        "login", "signin", "sign-in", "verify", "wallet", "seed",
        "mnemonic", "password", "passwd", "account-update",
        "secure-update", "confirm-account", "free-gift", "prize"
    )

    fun check(raw: String): Report {
        val input = raw.trim()
        if (input.isEmpty()) {
            return Report(Level.AMAN, 0, listOf("Kosong"), "", "", "")
        }

        // Ambil URL pertama jika ada teks campur
        val urlText = extractUrl(input) ?: input
        val reasons = mutableListOf<String>()
        var score = 0

        val withScheme = when {
            urlText.startsWith("http://", true) || urlText.startsWith("https://", true) -> urlText
            urlText.startsWith("//") -> "https:$urlText"
            else -> "https://$urlText"
        }

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return Report(
                Level.MENCURIGAKAN, 40,
                listOf("Format URL tidak valid"),
                "", "", urlText
            )
        }

        val scheme = (uri.scheme ?: "").lowercase(Locale.US)
        val host = (uri.host ?: "").lowercase(Locale.US)
        val path = (uri.path ?: "") + (uri.query?.let { "?$it" } ?: "")

        if (host.isBlank()) {
            return Report(
                Level.MENCURIGAKAN, 35,
                listOf("Host tidak ditemukan"),
                "", scheme, withScheme
            )
        }

        // --- Aturan lokal ---

        if (scheme == "http") {
            score += 15
            reasons += "Menggunakan HTTP (bukan HTTPS)"
        } else if (scheme !in listOf("https", "http")) {
            score += 25
            reasons += "Skema tidak biasa: $scheme"
        }

        // IP mentah sebagai host
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) {
            score += 25
            reasons += "Host berupa alamat IP mentah"
        }

        // Shortener
        val baseHost = host.removePrefix("www.")
        if (baseHost in SHORTENERS || SHORTENERS.any { baseHost.endsWith(".$it") }) {
            score += 20
            reasons += "Domain pemendek tautan (shortener)"
        }

        // TLD berisiko
        val tld = baseHost.substringAfterLast('.', "")
        if (tld in RISKY_TLDS) {
            score += 12
            reasons += "TLD sering disalahgunakan: .$tld"
        }

        // Subdomain berlebihan
        val labels = baseHost.split('.')
        if (labels.size >= 4) {
            score += 10
            reasons += "Subdomain terlalu dalam (${labels.size - 2} level)"
        }

        // Karakter @ di authority/path (phishing klasik)
        if (urlText.contains('@')) {
            score += 30
            reasons += "Mengandung karakter @ (teknik phishing umum)"
        }

        // Lookalike / homoglyph sederhana (campur digit di brand-like)
        if (host.contains(Regex("""[а-яА-ЯёЁ]"""))) { // Cyrillic
            score += 25
            reasons += "Host mengandung huruf Cyrillic (kemungkinan lookalike)"
        }

        // Kata mencurigakan
        val lowerPath = path.lowercase(Locale.US)
        val hitWords = SUSPICIOUS_WORDS.filter { lowerPath.contains(it) }
        if (hitWords.isNotEmpty()) {
            score += 8 * hitWords.size.coerceAtMost(3)
            reasons += "Path/query mengandung kata sensitif: ${hitWords.take(3).joinToString(", ")}"
        }

        // Port non-standar
        val port = uri.port
        if (port != -1 && port !in listOf(80, 443)) {
            score += 8
            reasons += "Port non-standar: $port"
        }

        // Persen-encoding berlebihan / null byte
        if (urlText.contains("%00", true) || urlText.contains('\u0000')) {
            score += 30
            reasons += "Terdeteksi null-byte / encoding berbahaya"
        }

        if (reasons.isEmpty()) {
            reasons += "Tidak ada sinyal risiko dari aturan lokal"
        }

        val level = when {
            score >= 30 -> Level.MENCURIGAKAN
            score >= 12 -> Level.HATI_HATI
            else -> Level.AMAN
        }

        return Report(level, score, reasons, host, scheme, withScheme)
    }

    /** Ambil URL pertama dari teks bebas. */
    fun extractUrl(text: String): String? {
        val regex = Regex(
            """(https?://[^\s<>"']+|www\.[^\s<>"']+|[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}(/[^\s<>"']*)?)""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(text)?.value
    }
}
