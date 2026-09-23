package com.imankoppai.mediaanvil.subtitles

import android.content.Context
import android.net.Uri
import com.imankoppai.mediaanvil.data.SafStorage
import com.imankoppai.mediaanvil.model.AudioTrack
import java.nio.charset.StandardCharsets

/**
 * Writes a sidecar lyric file beside its audio file without touching the audio itself.
 *
 * Access goes through the folder the user granted with the system picker, so the app
 * no longer needs "all files access". Without a matching grant the caller gets
 * `folder_permission_required` and must ask the user to choose the music folder.
 */
object LyricsFileStore {
    const val FOLDER_PERMISSION_REQUIRED = "folder_permission_required"

    fun save(context: Context, track: AudioTrack, lyrics: String): Uri =
        saveText(context, track, lyrics, "lrc")

    fun import(context: Context, track: AudioTrack, bytes: ByteArray, extension: String): Uri {
        val cleanExtension = extension.lowercase().takeIf { it in setOf("lrc", "srt", "vtt") }
            ?: error("unsupported_lyrics")
        return saveText(context, track, SubtitleLoader.decode(bytes), cleanExtension)
    }

    private fun saveText(context: Context, track: AudioTrack, lyrics: String, extension: String): Uri {
        val body = lyrics.trim().toByteArray(StandardCharsets.UTF_8) + '\n'.code.toByte()
        check(body.isNotEmpty()) { "empty_lyrics" }
        return SafStorage.writeSubtitle(context, track, body, extension)
    }

    /** True when a granted folder already covers this track, so saving can succeed. */
    fun canWrite(context: Context, track: AudioTrack): Boolean =
        SafStorage.findGrant(context, track.relativeFolder) is SafStorage.FolderAccess.Granted

    fun delete(context: Context, track: AudioTrack): Boolean {
        val uri = track.subtitleUri ?: return false
        return SafStorage.deleteSubtitle(context, uri, track.fileName)
    }
}
