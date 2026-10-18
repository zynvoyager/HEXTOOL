package com.example.hextool

import android.graphics.Color

/**
 * Pusat warna HEXTOOL — diselaraskan dengan mockup cyber / neon.
 */
object HexTheme {

    // Latar
    val bg          = Color.parseColor("#0B0F14")   // hampir hitam
    val panelBg     = Color.parseColor("#12181F")   // kartu
    val panelBgAlt  = Color.parseColor("#161E27")   // kartu sekunder
    val surface     = Color.parseColor("#1A2330")   // input / surface

    // Aksen neon cyan (mockup)
    val accent      = Color.parseColor("#3FA7D6")   // utama
    val accentSoft  = Color.parseColor("#7DD3F0")   // highlight
    val accentDim   = Color.parseColor("#1E4A63")   // stroke / pressed
    val accentGlow  = Color.parseColor("#0D2A3A")   // glow soft

    // Teks
    val textPrimary   = Color.parseColor("#E8F1F8")
    val textSecondary = Color.parseColor("#8BA3B8")
    val textMuted     = Color.parseColor("#5A7185")

    // Status
    val success = Color.parseColor("#2ECC71")
    val warning = Color.parseColor("#F1C40F")
    val danger  = Color.parseColor("#E74C3C")
    val info    = Color.parseColor("#3498DB")

    // Legacy (masih dipakai FloatingWidget & service)
    val green       = Color.rgb(0, 255, 0)
    val cyan        = Color.rgb(0, 200, 255)
    val yellow      = Color.rgb(255, 200, 0)
    val black       = Color.rgb(0, 0, 0)
    val dark        = Color.rgb(17, 17, 17)
    val overlayGrey = Color.rgb(70, 70, 70)
    val red         = Color.rgb(255, 85, 85)
    val grey        = Color.rgb(85, 85, 85)
    val accentDark  = Color.rgb(20, 90, 130)
    val buttonBg    = Color.rgb(15, 44, 64)
}
