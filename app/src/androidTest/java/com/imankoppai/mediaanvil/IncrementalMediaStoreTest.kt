package com.imankoppai.mediaanvil

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.ui.folderGroups
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IncrementalMediaStoreTest {
    @Test fun realMediaStoreHandlesAddsMovesEditsDeletesAndCachedGenerations() {
        assertTrue("Run generation integration on Android 11+", Build.VERSION.SDK_INT >= 30)
        val app = ApplicationProvider.getApplicationContext<Context>()
        val isolatedCache = java.io.File(app.cacheDir,"generation-cache-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = isolatedCache }
        val folder = "Music/MediaAnvilGenerationTest-${java.util.UUID.randomUUID()}/"
        val outside = folder.trimEnd('/') + "-outside/"
        val owned = mutableListOf<Uri>()
        fun insert(name: String): Uri {
            val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH,folder); put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!
            owned += uri
            val data = ByteArray(32000)
            val wave = ByteBuffer.allocate(44+data.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36+data.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(data.size).put(data).array()
            context.contentResolver.openOutputStream(uri)!!.use { it.write(wave) }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
            return uri
        }
        fun snapshot(scan: LibraryScan): LibraryCache.Snapshot {
            LibraryCache.save(context,scan)
            return LibraryCache.load(context)!!
        }
        try {
            val first = insert("episode10.wav")
            var scan = DeviceAudioLibrary.scan(context,setOf(folder),full=true)
            assertEquals(listOf(first),scan.tracks.map { it.uri })
            // Allow the asynchronous media indexer to settle; no sleeps are used on UI threads.
            repeat(6) { Thread.sleep(100); scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan)) }
            scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan))
            assertTrue(scan.incremental); assertEquals(0,scan.metadataRowsRead)
            val second=insert("episode2.wav")
            scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan))
            assertEquals(setOf(first,second),scan.tracks.map { it.uri }.toSet())
            assertTrue(scan.incremental)
            assertEquals(listOf("episode2.wav","episode10.wav"),folderGroups(scan.tracks).single().tracks.map { it.fileName })
            // TITLE is scanner-owned on some providers. Rename the actual file through
            // MediaStore instead, then verify the changed row is read incrementally.
            assertEquals(1,context.contentResolver.update(first,ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME,"episode11.wav") },null,null))
            scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan))
            assertEquals("episode11.wav",scan.tracks.single { it.uri==first }.fileName)
            assertTrue(scan.metadataRowsRead > 0)
            context.contentResolver.update(second,ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH,outside) },null,null)
            scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan))
            assertEquals(listOf(first),scan.tracks.map { it.uri })
            context.contentResolver.delete(first,null,null)
            owned.remove(first)
            scan=DeviceAudioLibrary.scan(context,setOf(folder),snapshot(scan))
            assertTrue(scan.tracks.isEmpty())
            assertEquals(setOf(folder),LibraryCache.load(context)!!.folderScope)
        } finally {
            try { owned.forEach { context.contentResolver.delete(it,null,null) } }
            finally { isolatedCache.deleteRecursively() }
        }
    }
}
