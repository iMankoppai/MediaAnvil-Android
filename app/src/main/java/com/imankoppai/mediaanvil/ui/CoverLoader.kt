package com.imankoppai.mediaanvil.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf

/**
 * Cheap embedded-cover display for the UI: reads artwork through
 * MediaMetadataRetriever directly from the SAF document, without copying or
 * parsing tags. Full-fidelity cover editing lives in the tag tools.
 */
object CoverLoader {
    /** Keep decoded artwork under roughly 32 MB so large libraries cannot balloon memory. */
    private const val MAX_CACHE_BYTES = 32 * 1024 * 1024

    /** Library rows show a small cover; only the player needs full artwork detail. */
    private const val THUMBNAIL_TARGET_PX = 160

    private val cache = object : android.util.LruCache<String, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }
    var revision by mutableIntStateOf(0)
        private set
    private val requests = ArtworkRequests(CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { key -> cache.get(key) }, { key, value -> cache.put(key, value); Unit }, android.os.SystemClock::elapsedRealtime)

    fun invalidate() {
        requests.invalidate { cache.evictAll() }
        revision++
    }

    suspend fun load(context: Context, uri: Uri, thumbnail: Boolean): ImageBitmap? {
        val key = if (thumbnail) "thumb:$uri" else uri.toString()
        val target = if (thumbnail) THUMBNAIL_TARGET_PX else 1024
        return requests.load(key) {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.embeddedPicture?.let { bytes ->
                        decodeScaled(bytes, target)?.asImageBitmap()
                    }
                } finally {
                    retriever.release()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    suspend fun loadImage(context: Context, uri: Uri): ImageBitmap? {
        val key = "image:$uri"
        return requests.load(key) {
            try {
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val width = info.size.width.coerceAtLeast(1)
                        val height = info.size.height.coerceAtLeast(1)
                        val longest = maxOf(width, height)
                        if (longest > 1024) {
                            val scale = 1024f / longest
                            decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                        }
                    }
                } else {
                    decodeDocumentImage(context, uri)
                }
                bitmap.asImageBitmap()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    private fun decodeDocumentImage(context: Context, uri: Uri, target: Int = 1024): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight, target)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return requireNotNull(
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) },
        )
    }

    /** Decode near display size to keep list thumbnails and artwork views light. */
    private fun decodeScaled(bytes: ByteArray, target: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = artworkSampleSize(bounds.outWidth, bounds.outHeight, target)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
