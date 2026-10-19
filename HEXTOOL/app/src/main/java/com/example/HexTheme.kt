package com.example.hextool

import android.content.Context
import android.graphics.Color

/**
 * Pusat warna HEXTOOL. THEME_TOGGLE_V1: dua tema (Hitam Putih default, Biru Laut),
 * dipilih lewat Settings, diterapkan ulang setiap Activity/Service dibuka (restart-based).
 */
object HexTheme {

    // Latar
    var bg          = Color.parseColor("#000000")
    var panelBg     = Color.parseColor("#0D0D0D")
    var panelBgAlt  = Color.parseColor("#141414")
    var surface     = Color.parseColor("#1A1A1A")

    // Aksen
    var accent      = Color.parseColor("#FFFFFF")
    var accentSoft  = Color.parseColor("#F2F2F2")
    var accentDim   = Color.parseColor("#3A3A3A")
    var accentGlow  = Color.parseColor("#1A1A1A")

    // Teks
    var textPrimary   = Color.parseColor("#FFFFFF")
    var textSecondary = Color.parseColor("#B0B0B0")
    var textMuted     = Color.parseColor("#6E6E6E")

    // Status (tetap berwarna di kedua tema, supaya tetap gampang dibedakan)
    val success = Color.parseColor("#2ECC71")
    val warning = Color.parseColor("#F1C40F")
    val danger  = Color.parseColor("#E74C3C")
    val info    = Color.parseColor("#3498DB")

    // Legacy (dipakai FloatingWidgetService & service lain)
    var green       = Color.rgb(255, 255, 255)
    var cyan        = Color.rgb(220, 220, 220)
    var yellow      = Color.rgb(255, 200, 0)
    var black       = Color.rgb(0, 0, 0)
    var dark        = Color.rgb(17, 17, 17)
    var overlayGrey = Color.rgb(42, 42, 42)
    var red         = Color.rgb(255, 85, 85)
    var grey        = Color.rgb(140, 140, 140)
    var accentDark  = Color.rgb(42, 42, 42)
    var buttonBg    = Color.rgb(26, 26, 26)

    const val THEME_MONO = "MONO"
    const val THEME_BIRU_LAUT = "BIRU_LAUT"

    fun load(context: Context) {
        if (HexSettings.getThemeId(context) == THEME_BIRU_LAUT) applyBiruLaut() else applyMono()
    }

    private fun applyMono() {
        bg = Color.parseColor("#000000")
        panelBg = Color.parseColor("#0D0D0D")
        panelBgAlt = Color.parseColor("#141414")
        surface = Color.parseColor("#1A1A1A")
        accent = Color.parseColor("#FFFFFF")
        accentSoft = Color.parseColor("#F2F2F2")
        accentDim = Color.parseColor("#3A3A3A")
        accentGlow = Color.parseColor("#1A1A1A")
        textPrimary = Color.parseColor("#FFFFFF")
        textSecondary = Color.parseColor("#B0B0B0")
        textMuted = Color.parseColor("#6E6E6E")
        green = Color.rgb(255, 255, 255)
        cyan = Color.rgb(220, 220, 220)
        black = Color.rgb(0, 0, 0)
        dark = Color.rgb(17, 17, 17)
        overlayGrey = Color.rgb(42, 42, 42)
        red = Color.rgb(255, 85, 85)
        grey = Color.rgb(140, 140, 140)
        accentDark = Color.rgb(42, 42, 42)
        buttonBg = Color.rgb(26, 26, 26)
    }

    private fun applyBiruLaut() {
        bg = Color.parseColor("#0B0F14")
        panelBg = Color.parseColor("#12181F")
        panelBgAlt = Color.parseColor("#161E27")
        surface = Color.parseColor("#1A2330")
        accent = Color.parseColor("#3FA7D6")
        accentSoft = Color.parseColor("#7DD3F0")
        accentDim = Color.parseColor("#1E4A63")
        accentGlow = Color.parseColor("#0D2A3A")
        textPrimary = Color.parseColor("#E8F1F8")
        textSecondary = Color.parseColor("#8BA3B8")
        textMuted = Color.parseColor("#5A7185")
        green = Color.rgb(0, 255, 0)
        cyan = Color.rgb(0, 200, 255)
        black = Color.rgb(0, 0, 0)
        dark = Color.rgb(17, 17, 17)
        overlayGrey = Color.rgb(70, 70, 70)
        red = Color.rgb(255, 85, 85)
        grey = Color.rgb(85, 85, 85)
        accentDark = Color.rgb(20, 90, 130)
        buttonBg = Color.rgb(15, 44, 64)
    }
}