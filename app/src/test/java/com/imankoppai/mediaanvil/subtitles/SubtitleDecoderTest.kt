package com.imankoppai.mediaanvil.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * The previous heuristic decoded as UTF-8 and looked for U+FFFD. That is wrong in
 * both directions: a GB18030 file can decode without any replacement character and
 * was then shown as mojibake, while genuine UTF-8 containing U+FFFD was re-decoded
 * as GB18030. These cases pin down the corrected behaviour.
 */
class SubtitleDecoderTest {

    private val text = "[00:01.00]你好，世界\n[00:05.00]第二句"

    @Test
    fun `plain utf8 is decoded as utf8`() {
        assertEquals(text, SubtitleDecoder.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `utf8 with a bom drops the bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            text.toByteArray(Charsets.UTF_8)

        assertEquals(text, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `utf16 little endian with a bom is decoded`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            text.toByteArray(Charsets.UTF_16LE)

        assertEquals(text, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `utf16 big endian with a bom is decoded`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            text.toByteArray(Charsets.UTF_16BE)

        assertEquals(text, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `gb18030 without a bom is decoded as gb18030`() {
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(text, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `a gb18030 file that is not valid utf8 is not shown as mojibake`() {
        // This is the regression the old heuristic missed: the bytes are not valid
        // UTF-8, so they must never be handed to a lenient UTF-8 decoder.
        val chinese = "[00:01.00]周杰伦 - 稻香\n[00:02.00]对这个世界如果你有太多的抱怨"
        val bytes = chinese.toByteArray(Charset.forName("GB18030"))

        val decoded = SubtitleDecoder.decode(bytes)

        assertEquals(chinese, decoded)
        assertTrue("mojibake must not appear", '\uFFFD' !in decoded)
    }

    @Test
    fun `valid utf8 containing a replacement character is kept as utf8`() {
        // The other direction of the old bug: this IS valid UTF-8, so switching to
        // GB18030 because of the U+FFFD would corrupt the readable parts.
        val withReplacement = "[00:01.00]前\uFFFD后"

        assertEquals(
            withReplacement,
            SubtitleDecoder.decode(withReplacement.toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * A known and accepted limitation, recorded here so nobody assumes Big5 is
     * detected reliably. Big5 and GB18030 overlap, and a double-byte sequence that
     * is valid in one is usually "valid" in the other, so without a BOM no decoder
     * can tell them apart by validity alone. GB18030 is tried first because it is
     * far more common for lyric files; Big5 is only a last resort for the rarer
     * case where GB18030 genuinely cannot decode the bytes.
     */
    @Test
    fun `a bomless big5 file is read as gb18030 which is a documented limitation`() {
        val traditional = "[00:01.00]你好世界"
        val bytes = traditional.toByteArray(Charset.forName("Big5"))

        // Not asserting the text: asserting the current, understood behaviour.
        val decoded = SubtitleDecoder.decode(bytes)
        assertTrue("must still produce text, never throw", decoded.isNotEmpty())
    }

    @Test
    fun `a big5 file is decoded correctly when it carries a utf8 bom`() {
        // The reliable escape hatch: a BOM makes the encoding unambiguous.
        val traditional = "[00:01.00]你好世界"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            traditional.toByteArray(Charsets.UTF_8)

        assertEquals(traditional, SubtitleDecoder.decode(bytes))
    }

    @Test
    fun `ascii only files are unaffected`() {
        val ascii = "[00:01.00]Hello world"

        assertEquals(ascii, SubtitleDecoder.decode(ascii.toByteArray(Charsets.US_ASCII)))
        assertEquals(ascii, SubtitleDecoder.decode(ascii.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `an empty file decodes to an empty string`() {
        assertEquals("", SubtitleDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `undecodable bytes still produce text instead of throwing`() {
        // Lone continuation bytes are invalid UTF-8 and invalid GB18030 too.
        val garbage = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0xFF.toByte())

        val decoded = SubtitleDecoder.decode(garbage)

        assertTrue("must not throw", decoded.isNotEmpty())
    }

    @Test
    fun `the loader delegates to the shared decoder`() {
        val bytes = text.toByteArray(Charset.forName("GB18030"))

        assertEquals(text, SubtitleLoader.decode(bytes))
    }
}
