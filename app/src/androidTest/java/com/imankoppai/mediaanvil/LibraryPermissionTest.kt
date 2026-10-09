package com.imankoppai.mediaanvil

import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.imankoppai.mediaanvil.data.*
import com.imankoppai.mediaanvil.ui.LibraryViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real MediaStore rows plus an isolated permission boundary; no user permissions or audio are revoked/deleted. */
class LibraryPermissionTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Volatile private var granted = false
    private lateinit var cache: File
    private lateinit var context: Context
    private lateinit var application: Application
    private val models = ViewModelStore()
    private var fixture: Uri? = null
    private val fixtureName = "permission-${java.util.UUID.randomUUID()}.wav"
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before fun before() {
        cache = File(app.cacheDir, "permission-cache-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = cache
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == DeviceAudioLibrary.readPermission) {
                    if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                } else super.checkPermission(permission, pid, uid)
        }
        application = object : Application() { override fun getApplicationContext() = context }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fixtureName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/MediaAnvilPermissionTest/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        })!!
        fixture = uri
        val data = ByteArray(32000)
        context.contentResolver.openOutputStream(uri)!!.use { stream -> stream.write(
            ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(data.size).put(data).array()) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }
    @After fun after() {
        main { models.clear() }
        fixture?.let { context.contentResolver.delete(it, null, null) }
        cache.deleteRecursively()
    }

    @Test fun authorizationAutomaticallyLoadsExistingAudioWithoutManualRefresh() {
        lateinit var library: LibraryViewModel
        main {
            library = LibraryViewModel(application).also { models.put("library", it) }
            library.startup()
            assertFalse(library.loading)
            assertFalse(library.storageAccessGranted)
            assertFalse(library.scanFailed)
            assertTrue(library.tracks.isEmpty())
            library.rescan()
        }
        assertFalse("Denied startup must not create a partial cache", File(cache, "library_snapshot.json").exists())
        granted = true
        main {
            library.onStorageAccessChanged()
            assertTrue("Permission result must start the scan immediately", library.loading)
        }
        await { !library.loading && library.tracks.any { it.fileName == fixtureName } }
        assertTrue(LibraryCache.load(context)!!.audioReadGranted == true)
    }

    @Test fun grantingAccessCannotReuseAnEmptySnapshotFromBeforeAuthorization() {
        val prior = DeviceAudioLibrary.scan(context, full = true).copy(tracks = emptyList())
        assertFalse(prior.audioReadGranted!!)
        // Even a later cache write must remember the visibility used by the scan.
        granted = true
        LibraryCache.save(context, prior)
        val cached = LibraryCache.load(context)!!
        assertFalse(cached.audioReadGranted!!)
        val loaded = DeviceAudioLibrary.scan(context, previous = cached)
        assertFalse("A permission change invalidates incremental metadata", loaded.incremental)
        assertTrue(loaded.tracks.any { it.fileName == fixtureName })
    }

    @Test fun oldEmptyCachesRecoverAtStartupWithoutManualScan() {
        granted = true
        LibraryCache.save(context, DeviceAudioLibrary.scan(context, full = true).copy(tracks = emptyList()))
        val file = File(cache, "library_snapshot.json")
        file.writeText(JSONObject(file.readText()).apply { remove("audioReadGranted") }.toString())
        assertNull(LibraryCache.load(context)!!.audioReadGranted)
        lateinit var library: LibraryViewModel
        main {
            library = LibraryViewModel(application).also { models.put("library", it) }
            library.startup()
        }
        await { !library.loading && library.tracks.any { it.fileName == fixtureName } }
        assertFalse(library.files!!.incremental)
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(20000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(25)
        }
    }
}
