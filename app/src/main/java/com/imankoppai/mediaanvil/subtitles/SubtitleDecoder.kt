package com.imankoppai.mediaanvil.subtitles

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes a lyric/subtitle file to text.
 *
 * Lyric files are written by many different tools, so the encoding has to be
 * inferred. Two rules matter and both used to be wrong:
 *
 * 1. A UTF-8 BOM is authoritative, and UTF-16 BOMs must be honoured.
 * 2. Without a BOM, UTF-8 is only assumed when the bytes actually *are* valid
 *    UTF-8. The previous check decoded as UTF-8 and looked for U+FFFD, but
 *    Java's default UTF-8 decoder silently substitutes U+FFFD for malformed
 *    input *and* real UTF-8 text can legitimately contain U+FFFD — so a GB18030
 *    file whose bytes happened to decode without the replacement character was
 *    accepted as UTF-8 and shown as mojibake, while valid UTF-8 containing that
 *    character was wrongly re-decoded as GB18030. Decoding with
 *    [CodingErrorAction.REPORT] tests validity instead of guessing from the
 *    result.
 */
object SubtitleDecoder {
    /** Legacy Chinese encodings, most likely first. */
    private val legacyCharsets = listOf("GB18030", "Big5")

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        readBom(bytes)?.let { (charset, bomLength) ->
            return bytes.copyOfRange(bomLength, bytes.size).toString(charset)
        }

        // No BOM: prefer UTF-8, but only if every byte really forms valid UTF-8.
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }

        // Not UTF-8, so it is a legacy encoding. Prefer the candidate that decodes
        // cleanly; if both do (possible for pure ASCII, already handled above, and
        // for short strings) the first candidate wins, which is GB18030.
        legacyCharsets.forEach { name ->
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: return@forEach
            strictDecode(bytes, charset)?.let { return it }
        }

        // Nothing decoded cleanly: show something rather than nothing, replacing
        // only the undecodable bytes.
        return bytes.toString(Charsets.UTF_8)
    }

    /** Returns the charset and BOM length, or null when there is no BOM. */
    private fun readBom(bytes: ByteArray): Pair<Charset, Int>? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8 to 3
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE to 2
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE to 2
        }
        return null
    }

    /** Decodes only when the bytes are valid for [charset]; null otherwise. */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}
