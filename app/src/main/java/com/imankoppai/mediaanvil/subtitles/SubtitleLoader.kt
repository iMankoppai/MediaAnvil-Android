package com.imankoppai.mediaanvil.subtitles

import android.content.Context
import android.net.Uri
import com.imankoppai.mediaanvil.model.SubtitleCue

object SubtitleLoader {
    fun load(context: Context, uri: Uri, extension: String): List<SubtitleCue> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        val text = decode(bytes)
        return SubtitleParser.parse(text, extension)
    }

    internal fun decode(bytes: ByteArray): String = SubtitleDecoder.decode(bytes)
}
