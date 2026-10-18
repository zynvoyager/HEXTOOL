package com.example.hextool

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

// Pembungkus ML Kit Translate (on-device). Model diunduh sekali, lalu jalan
// offline sepenuhnya -- tidak ada teks yang dikirim ke server mana pun.
// TRANSLATE_FOUNDATION_V1
object LanguageTranslator {

    enum class Direction { EN_TO_ID, ID_TO_EN }

    private var enToId: Translator? = null
    private var idToEn: Translator? = null

    private fun translatorFor(direction: Direction): Translator {
        val cached = if (direction == Direction.EN_TO_ID) enToId else idToEn
        if (cached != null) return cached

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(if (direction == Direction.EN_TO_ID) TranslateLanguage.ENGLISH else TranslateLanguage.INDONESIAN)
            .setTargetLanguage(if (direction == Direction.EN_TO_ID) TranslateLanguage.INDONESIAN else TranslateLanguage.ENGLISH)
            .build()
        val t = Translation.getClient(options)
        if (direction == Direction.EN_TO_ID) enToId = t else idToEn = t
        return t
    }

    fun downloadModel(direction: Direction, onDone: (Boolean, String?) -> Unit) {
        val conditions = DownloadConditions.Builder().build()
        translatorFor(direction).downloadModelIfNeeded(conditions)
            .addOnSuccessListener { onDone(true, null) }
            .addOnFailureListener { e -> onDone(false, e.message) }
    }

    fun translate(direction: Direction, text: String, onDone: (String?, String?) -> Unit) {
        translatorFor(direction).translate(text)
            .addOnSuccessListener { result -> onDone(result, null) }
            .addOnFailureListener { e -> onDone(null, e.message) }
    }
}