package com.example.hextool

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object HexConverter {

    const val PREFIX = "3301"

    // ---------- Teks (prefix 3301) -> HEX ----------

    sealed class Result {
        data class Success(val hex: String) : Result()
        object NoPrefix : Result()
        object EmptyPayload : Result()
    }

    // Tanpa trim(): spasi awal/akhir payload dipertahankan.
    fun convert(input: String): Result {
        if (!input.startsWith(PREFIX)) return Result.NoPrefix

        val payload = input.substring(PREFIX.length)
        if (payload.isEmpty()) return Result.EmptyPayload

        val hex = payload
            .toByteArray(Charsets.UTF_8)
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }

        return Result.Success(hex)
    }

    // ---------- HEX -> teks asli ----------

    sealed class DecodeResult {
        data class Success(val text: String) : DecodeResult()
        object Empty : DecodeResult()
        object InvalidHex : DecodeResult()
        object InvalidUtf8 : DecodeResult()
    }

    // allowWhitespace = true : spasi/newline pada input HEX dibuang (untuk panel).
    // allowWhitespace = false: input harus HEX murni tanpa spasi (untuk notifikasi).
    // rejectControlChars     : tolak hasil yang berisi karakter kontrol selain
    //                          \n \r \t (menyaring angka/teks biasa yang kebetulan valid HEX).
    // Teks hasil decode TIDAK di-trim.
    fun decode(
        input: String,
        allowWhitespace: Boolean = true,
        rejectControlChars: Boolean = false
    ): DecodeResult {
        val clean = if (allowWhitespace) {
            input.filter { !it.isWhitespace() }
        } else {
            input
        }
        if (clean.isEmpty()) return DecodeResult.Empty

        val allHex = clean.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
        if (clean.length % 2 != 0 || !allHex) return DecodeResult.InvalidHex

        val bytes = ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            return DecodeResult.InvalidUtf8
        }

        if (rejectControlChars && text.any {
                it.isISOControl() && it != '\n' && it != '\r' && it != '\t'
            }
        ) {
            return DecodeResult.InvalidUtf8
        }

        return DecodeResult.Success(text)
    }
}