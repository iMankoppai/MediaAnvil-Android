package com.imankoppai.mediaanvil

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.DeviceAudioLibrary
import com.imankoppai.mediaanvil.data.LibraryCache
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.ui.LibraryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 5: the library must not touch the disk on the main thread.
 *
 * This is a regression test with teeth. `startup()` is called from a `LaunchedEffect`
 * during the first composition and `scanAll()` used to finish its work in the caller's
 * coroutine, so both ran a JSON cache read, a ContentResolver query per track and a
 * cache write on the main thread — jank no unit test would have noticed.
 *
 * StrictMode's thread policy is **per thread**, and instrumentation tests run on the
 * instrumentation thread rather than the app's main thread, so the policy is installed
 * from inside `withContext(Dispatchers.Main)` and removed the same way. Installing it
 * naively on the test thread would have watched the wrong thread and passed no matter
 * what the app did.
 *
 * A *collecting* penalty is used rather than a crashing one so a failure can name the
 * offending call site instead of only reporting that something, somewhere, did I/O.
 */
@RunWith(AndroidJUnit4::class)
class Phase5MainThreadIoTest {
    private val violations = CopyOnWriteArrayList<String>()
    private lateinit var context: Context

    @org.junit.Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }
    }

    /** Installs the policy on the app's main thread, where the app code runs. */
    private suspend fun installStrictModeOnMain() = withContext(Dispatchers.Main) {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyListener({ command -> command.run() }) { violation ->
                    violations += violation.stackTrace
                        .take(8)
                        .joinToString(" | ") { it.toString() }
                }
                .build(),
        )
    }

    private suspend fun removeStrictModeFromMain() = withContext(Dispatchers.Main) {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
    }

    /** Waits until [condition] holds, so the test does not race the background work. */
    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(50)
            true
        } ?: false

    private fun viewModel(): LibraryViewModel =
        LibraryViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun startupDoesNotReadTheDiskOnTheMainThread() {
        val library = viewModel()
        runBlocking {
            installStrictModeOnMain()
            // The call itself has to be dispatched to Main: `startup()` used to do its
            // work synchronously in the caller, and calling it from `runBlocking` would
            // run that work on the instrumentation thread, where the policy is not
            // installed — the test would then pass no matter what the app did.
            withContext(Dispatchers.Main) { library.startup() }
            awaitUntil { !library.loading }
            removeStrictModeFromMain()
        }

        val offenders = violations.filter {
            it.contains("LibraryCache") || it.contains("SafStorage") || it.contains("DeviceAudioLibrary")
        }
        assertTrue(
            "main-thread I/O from the library: ${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun aFullScanDoesNotTouchTheDiskOnTheMainThread() {
        val library = viewModel()
        var scanned = -1
        runBlocking {
            installStrictModeOnMain()
            withContext(Dispatchers.Main) {
                library.rescan(quiet = true) { count -> scanned = count }
            }
            awaitUntil { scanned >= 0 }
            removeStrictModeFromMain()
        }

        val offenders = violations.filter {
            it.contains("LibraryCache") || it.contains("SafStorage") || it.contains("DeviceAudioLibrary")
        }
        assertTrue(
            "main-thread I/O from a scan: ${offenders.joinToString("\n")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun startupShowsTheCachedLibrary() {
        val library = viewModel()
        // Seed a cache first, then let startup() load it. This checks that the cache
        // read still populates the UI state now that it happens on another thread —
        // without assuming the device has any audio on it. The CI emulator has none,
        // and an earlier version of this test asserted a non-empty library and failed
        // there while passing on the phone.
        val seeded = DeviceAudioLibrary.scan(context)
        LibraryCache.save(context, seeded)

        runBlocking {
            library.startup()
            awaitUntil { !library.loading }
        }

        // Hidden tracks are filtered out of the visible list, so compare against the
        // same filter rather than the raw scan.
        val hidden = PlaybackPreferences(context).hiddenTrackUris
        val expected = seeded.tracks.count { it.uri.toString() !in hidden }
        assertEquals(
            "startup() did not show the cached library",
            expected,
            library.tracks.size,
        )
    }
}
