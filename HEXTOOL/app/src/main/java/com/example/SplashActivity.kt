package com.example.hextool

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_MS = 1600L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var opened = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { openMain() }
        }

        // Wallpaper kontur + lapisan gelap tipis
        root.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.bg_topo)
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            View(this).apply { setBackgroundColor(Color.argb(90, 0, 0, 0)) },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Isi tengah: logo, nama, tagline, garis progres
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        column.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.hex_dragon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(dp(240), dp(240))
        )

        column.addView(
            TextView(this).apply {
                text = "HEXTOOL"
                setTextColor(HexTheme.accent)
                textSize = 32f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.15f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(
            TextView(this).apply {
                text = "Simple Tools. Greater Control."
                setTextColor(Color.rgb(150, 170, 185))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val track = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(80, 63, 167, 214))
        }
        val fill = View(this).apply {
            setBackgroundColor(HexTheme.accent)
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(
            fill,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        column.addView(
            track,
            LinearLayout.LayoutParams(dp(180), dp(3)).apply { topMargin = dp(36) }
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        // Copyright di bawah
        root.addView(
            TextView(this).apply {
                text = "Copyright © Zyn / SIK Dev"
                setTextColor(Color.rgb(120, 135, 148))
                textSize = 11f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(28) }
        )

        setContentView(root)

        fill.animate()
            .scaleX(1f)
            .setDuration(SPLASH_MS - 200)
            .start()

        handler.postDelayed({ openMain() }, SPLASH_MS)
    }

    private fun openMain() {
        if (opened) return
        opened = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}