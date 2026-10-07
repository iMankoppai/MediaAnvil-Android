package com.imankoppai.mediaanvil

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.imankoppai.mediaanvil.tags.TagIO
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exercises the real Android file replacement that Windows cannot perform. */
@RunWith(AndroidJUnit4::class)
class TagIODeviceTest {
    @Test
    fun m4aUnicodeRoundTripPreservesOtherTagsAndAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("m4a-tag-check-", ".m4a", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("tag-fixtures/tags.m4a").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val originalAudio = audioSignature(file)
            val seeded = AudioFileIO.read(file)
            assertEquals("KeepAlbum", seeded.tag.getFirst(FieldKey.ALBUM))
            seeded.tag.apply {
                setField(FieldKey.TITLE, "OldTitle")
                setField(FieldKey.ARTIST, "OldArtist")
                setField(FieldKey.YEAR, "2023")
                setField(FieldKey.TRACK, "7")
                setField(FieldKey.DISC_NO, "2")
                setField(FieldKey.COMMENT, "保留备注")
            }
            seeded.commit()
            assertEquals(originalAudio, audioSignature(file))
            val before = AudioFileIO.read(file).tag
            val editedIds = (before.getFields(FieldKey.TITLE) + before.getFields(FieldKey.ARTIST))
                .map { it.id }.toSet()
            val originalOtherTags = otherTags(before, editedIds)

            TagIO.write(file, "中文歌名", "青葉りんご")
            assertEquals(TagIO.Tags("中文歌名", "青葉りんご"), TagIO.read(file))
            assertEquals(originalOtherTags, otherTags(AudioFileIO.read(file).tag, editedIds))
            assertEquals(originalAudio, audioSignature(file))

            // Exercise another replacement with a shorter title and artist removal.
            TagIO.write(file, "第二标题", "")
            assertEquals(TagIO.Tags("第二标题", ""), TagIO.read(file))
            val after = AudioFileIO.read(file).tag
            assertEquals("KeepAlbum", after.getFirst(FieldKey.ALBUM))
            assertEquals("2023", after.getFirst(FieldKey.YEAR))
            assertEquals("7", after.getFirst(FieldKey.TRACK))
            assertEquals("2", after.getFirst(FieldKey.DISC_NO))
            assertEquals("保留备注", after.getFirst(FieldKey.COMMENT))
            assertEquals(originalOtherTags, otherTags(after, editedIds))
            assertEquals(originalAudio, audioSignature(file))
        } finally {
            assertTrue("Temporary M4A sample must be removed", file.delete())
        }
    }

    private fun otherTags(tag: Tag, editedIds: Set<String>): Map<String, List<String>> =
        tag.fields.asSequence().filter { it.id !in editedIds }.groupBy { it.id }.mapValues { (_, fields) ->
            fields.map { java.util.Base64.getEncoder().encodeToString(it.rawContent) }
        }

    private data class AudioSignature(
        val mime: String, val durationUs: Long, val sampleRate: Int, val channels: Int,
        val sampleCount: Int, val digest: List<Byte>,
    )

    /** Compare encoded samples and timing, also verifying updated MP4 chunk offsets. */
    private fun audioSignature(file: File): AudioSignature {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val audioIndex = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(audioIndex)
            extractor.selectTrack(audioIndex)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(1024 * 1024)
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes)
                digest.update(ByteBuffer.allocate(16).putLong(extractor.sampleTime)
                    .putInt(extractor.sampleFlags).putInt(size).array())
                digest.update(bytes)
                samples++
                if (!extractor.advance()) break
            }
            assertTrue("Fixture must contain readable audio samples", samples > 0)
            return AudioSignature(format.getString(MediaFormat.KEY_MIME)!!,
                format.getLong(MediaFormat.KEY_DURATION), format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), samples, digest.digest().toList())
        } finally { extractor.release() }
    }
}
