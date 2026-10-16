package com.example.hextool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class HexClipboardService : AccessibilityService() {

    private lateinit var clipboardManager: ClipboardManager
    private var isProcessing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        clipboardManager.addPrimaryClipChangedListener {
            handleClipboardText()
        }
    }

    private fun handleClipboardText() {
        if (isProcessing) return

        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val copiedText = clipData.getItemAt(0).text?.toString() ?: ""

            if (copiedText.trim().startsWith("3301")) {
                isProcessing = true
                val rawMessage = copiedText.removePrefix("3301").trim()
                val hexResult = encodePlaintextToHex(rawMessage)

                val newClip = ClipData.newPlainText("Hex Encrypted", hexResult)
                clipboardManager.setPrimaryClip(newClip)
                isProcessing = false
            }
        }
    }

    private fun encodePlaintextToHex(input: String): String {
        return try {
            val hexString = StringBuilder()
            for (char in input) {
                val hex = String.format("%02x", char.code)
                hexString.append(hex)
            }
            hexString.toString()
        } catch (e: Exception) {
            input
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}