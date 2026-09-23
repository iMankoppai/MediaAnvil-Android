package com.imankoppai.mediaanvil

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.subtitles.LyricsLoad
import com.imankoppai.mediaanvil.subtitles.PreviewLyrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

/**
 * Phase 6 lyric loading against real files on the device.
 *
 * The unit tests cover the parser and the decoder in isolation; this exercises the
 * path the player actually takes — bytes on disk, through the ContentResolver, into
 * a [LyricsLoad] — so an encoding that only works in a JVM test cannot pass here.
 *
 * Nothing in this class touches the network: local lyrics are the whole point of the
 * app, and they must keep working with the device offline.
 */
@RunWith(AndroidJUnit4::class)
class Phase6LyricsTest {
    private lateinit var context: Context
    private lateinit var folder: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        folder = File(context.cacheDir, "phase6-lyrics").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    /** Writes [bytes] into the cache and returns the uri the player would read. */
    private fun write(name: String, bytes: ByteArray): Uri {
        val file = File(folder, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun writeText(name: String, text: String, charset: Charset): Uri =
        write(name, text.toByteArray(charset))

    /** A track whose linked lyric file is [uri]. */
    private fun trackFor(uri: Uri?, extension: String? = "lrc") = AudioTrack(
        uri = Uri.parse("content://media/external/audio/media/1"),
        fileName = "song.mp3",
        title = "歌",
        artist = "歌手",
        album = null,
        durationMs = 200_000L,
        subtitleUri = uri,
        subtitleExtension = extension,
    )

    private fun load(uri: Uri?, extension: String? = "lrc"): LyricsLoad =
        PreviewLyrics.loadResult(context, trackFor(uri, extension))

    private fun cuesOf(result: LyricsLoad) = (result as LyricsLoad.Loaded).cues

    @Test
    fun plainUtf8LyricsLoadOffline() {
        val uri = writeText("plain.lrc", "[00:01.00]第一行\n[00:05.50]第二行\n", Charsets.UTF_8)

        val cues = cuesOf(load(uri))
        assertEquals(2, cues.size)
        assertEquals("第一行", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals("第二行", cues[1].text)
        assertEquals(5_500L, cues[1].startMs)
    }

    @Test
    fun utf8BomIsStrippedFromTheFirstLine() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val uri = write("bom.lrc", bom + "[00:02.00]带BOM".toByteArray(Charsets.UTF_8))

        val cues = cuesOf(load(uri))
        assertEquals(1, cues.size)
        // A BOM left in place would show up as a stray character before the text.
        assertEquals("带BOM", cues[0].text)
        assertEquals(2_000L, cues[0].startMs)
    }

    @Test
    fun gb18030LyricsDecodeInsteadOfShowingMojibake() {
        val text = "[00:01.00]中文歌词测试\n[00:03.00]第二句\n"
        val uri = writeText("gb.lrc", text, Charset.forName("GB18030"))

        val cues = cuesOf(load(uri))
        assertEquals(2, cues.size)
        assertEquals("中文歌词测试", cues[0].text)
        assertEquals("第二句", cues[1].text)
    }

    @Test
    fun utf16LeLyricsAreHonouredByTheirBom() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val uri = write("utf16.lrc", bom + "[00:01.00]UTF16歌词".toByteArray(Charsets.UTF_16LE))

        val cues = cuesOf(load(uri))
        assertEquals(1, cues.size)
        assertEquals("UTF16歌词", cues[0].text)
    }

    @Test
    fun theFilesOwnOffsetShiftsEveryLine() {
        // A positive [offset:] moves the lyrics later, which is the LRC convention.
        val uri = writeText(
            "offset.lrc",
            "[offset:+500]\n[00:01.00]晚半秒\n",
            Charsets.UTF_8,
        )

        val cues = cuesOf(load(uri))
        assertEquals(1, cues.size)
        assertEquals(1_500L, cues[0].startMs)
    }

    @Test
    fun aNegativeOffsetShiftsTheLyricsEarlier() {
        val uri = writeText(
            "neg.lrc",
            "[offset:-400]\n[00:02.00]早四百毫秒\n",
            Charsets.UTF_8,
        )

        val cues = cuesOf(load(uri))
        assertEquals(1_600L, cues[0].startMs)
    }

    @Test
    fun aNegativeOffsetNeverProducesANegativeTimestamp() {
        val uri = writeText("clamp.lrc", "[offset:-9000]\n[00:01.00]开头\n", Charsets.UTF_8)

        val cues = cuesOf(load(uri))
        assertEquals(0L, cues[0].startMs)
    }

    @Test
    fun aTagOnlyFileReportsNoTimestampsRatherThanAnError() {
        val uri = writeText("tags.lrc", "[ar:歌手]\n[ti:歌名]\n[al:专辑]\n", Charsets.UTF_8)

        val result = load(uri)
        assertTrue("expected NoTimestamps but was $result", result is LyricsLoad.NoTimestamps)
        assertTrue((result as LyricsLoad.NoTimestamps).metadataOnly)
    }

    @Test
    fun anEmptyFileIsReportedAsEmptyNotAsUnreadable() {
        val uri = write("empty.lrc", ByteArray(0))

        assertEquals(LyricsLoad.EmptyFile, load(uri))
    }

    @Test
    fun anUnsupportedExtensionIsRejectedWithItsOwnReason() {
        val uri = writeText("notes.txt", "[00:01.00]不是歌词\n", Charsets.UTF_8)

        assertEquals(LyricsLoad.UnsupportedFormat, load(uri, extension = "txt"))
    }

    @Test
    fun aTrackWithNoLinkedLyricsSaysSoInsteadOfFailing() {
        assertEquals(LyricsLoad.NotLinked, load(null))
    }

    @Test
    fun autoLoadDisabledReportsDisabledEvenWhenAFileIsLinked() {
        val uri = writeText("linked.lrc", "[00:01.00]有歌词\n", Charsets.UTF_8)

        val result = PreviewLyrics.loadResult(context, trackFor(uri), autoLoadExternal = false)
        assertEquals(LyricsLoad.Disabled, result)
    }

    @Test
    fun aMissingFileIsReportedAsUnreadable() {
        val missing = Uri.fromFile(File(folder, "does-not-exist.lrc"))

        assertEquals(LyricsLoad.Unreadable, load(missing))
    }

    @Test
    fun srtSidecarsStillLoadThroughTheSameEntryPoint() {
        val uri = writeText(
            "sub.srt",
            "1\n00:00:01,000 --> 00:00:03,000\n第一句\n\n2\n00:00:04,000 --> 00:00:06,000\n第二句\n",
            Charsets.UTF_8,
        )

        val cues = cuesOf(load(uri, extension = "srt"))
        assertEquals(2, cues.size)
        assertEquals("第一句", cues[0].text)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
    }

    @Test
    fun translationLinesSharingATimestampStayOnOneCue() {
        // The usual bilingual layout: original and translation carry the same stamp.
        val uri = writeText(
            "bilingual.lrc",
            "[00:01.00]original\n[00:01.00]译文\n",
            Charsets.UTF_8,
        )

        val cues = cuesOf(load(uri))
        assertEquals(1, cues.size)
        assertEquals("original\n译文", cues[0].text)
    }

    @Test
    fun lyricLinesComeBackInTimelineOrderEvenWhenTheFileIsNotSorted() {
        val uri = writeText(
            "unsorted.lrc",
            "[00:09.00]第三\n[00:01.00]第一\n[00:05.00]第二\n",
            Charsets.UTF_8,
        )

        val cues = cuesOf(load(uri))
        assertEquals(listOf("第一", "第二", "第三"), cues.map { it.text })
        assertEquals(listOf(1_000L, 5_000L, 9_000L), cues.map { it.startMs })
    }
}
