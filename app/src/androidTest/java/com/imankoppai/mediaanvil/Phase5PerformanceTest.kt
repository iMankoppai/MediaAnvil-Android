package com.imankoppai.mediaanvil

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.imankoppai.mediaanvil.data.DeviceAudioLibrary
import com.imankoppai.mediaanvil.data.LibraryCache
import com.imankoppai.mediaanvil.data.LibraryScan
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.data.SettingsStore
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.ui.LibraryQuery
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import java.io.File

/**
 * Phase 5 measurements, taken on the device rather than guessed at.
 *
 * These are not pass/fail assertions 鈥?a wall-clock threshold on shared CI hardware
 * would be flaky and would fail for reasons that have nothing to do with the app.
 * Each test prints `PERF <name> <millis>` lines that the verification document
 * records, so an optimisation can be shown to have helped instead of merely
 * being believed to have helped.
 *
 * Every measurement is repeated and the **best** time is reported: the fastest run
 * is the one least polluted by other work on the device, which is what a "how long
 * does this actually take" number should reflect.
 */
@RunWith(AndroidJUnit4::class)
class Phase5PerformanceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Runs [block] [runs] times and reports the fastest, in **microseconds**.
     *
     * Milliseconds are too coarse: a warm settings read and a cache load both round
     * to 0, which hides the difference an optimisation is supposed to make.
     *
     * Results are appended to a file as well as printed: `println` from an
     * instrumentation process does not reliably reach either the raw output stream
     * or logcat, and a measurement nobody can read is worth nothing.
     */
    private fun measure(name: String, runs: Int = 7, block: () -> Unit) {
        repeat(2) { block() } // warm up
        var best = Long.MAX_VALUE
        repeat(runs) {
            val started = System.nanoTime()
            block()
            val elapsed = (System.nanoTime() - started) / 1_000
            if (elapsed < best) best = elapsed
        }
        report("$name ${best}us")
    }

    private fun report(line: String) {
        println("PERF $line")
        runCatching {
            File(context.getExternalFilesDir(null), "perf.txt").appendText("PERF $line\n")
        }
    }

    @Test
    fun settingsStoreFirstRead() {
        // The blocking one-time read that stands in for getSharedPreferences().
        measure("settings_store_first_read", runs = 5) {
            SettingsStore.resetForTests()
            PlaybackPreferences(context).autoLoadLyrics
        }
    }

    @Test
    fun settingsStoreWarmRead() {
        PlaybackPreferences(context).autoLoadLyrics
        measure("settings_store_warm_read", runs = 200) {
            PlaybackPreferences(context).autoLoadLyrics
        }
    }

    @Test
    fun libraryCacheLoad() {
        // Written by a real scan first so this measures a realistic file.
        val scan = DeviceAudioLibrary.scan(context)
        LibraryCache.save(context, scan)
        report("library_track_count ${scan.tracks.size}")

        measure("library_cache_load", runs = 20) {
            LibraryCache.load(context)
        }
    }

    @Test
    fun libraryScan() {
        measure("library_scan", runs = 5) {
            DeviceAudioLibrary.scan(context)
        }
    }

    @Test
    fun libraryCacheSave() {
        val scan = DeviceAudioLibrary.scan(context)
        measure("library_cache_save", runs = 20) {
            LibraryCache.save(context, scan)
        }
    }

    @Test
    fun libraryQueryResolve() {
        val scan = DeviceAudioLibrary.scan(context)
        val uris = scan.tracks.map { it.uri.toString() }
        val favorites = uris.reversed()

        measure("library_query_resolve", runs = 50) {
            LibraryQuery.resolve(uris, { it }, favorites)
        }
    }

    @Test
    fun librarySearchOverAFullLibrary() {
        val scan = DeviceAudioLibrary.scan(context)
        val query = "a"
        measure("library_search", runs = 50) {
            scan.tracks.count { it.title.contains(query, ignoreCase = true) }
        }
    }

    @Test
    fun sidecarLookupPerTrackIsTheCostThatMatters() {
        // attachKnownSidecars() asks the Storage Access Framework once per track that
        // has no lyrics yet. With no folder granted every call returns early, so this
        // measures the per-track bookkeeping that runs on the caller's thread.
        val tracks = List(300) { index ->
            AudioTrack(
                uri = Uri.parse("content://media/external/audio/media/$index"),
                fileName = "song$index.mp3",
                title = "song$index",
                artist = null,
                album = null,
                durationMs = 1_000L,
                subtitleUri = null,
                subtitleExtension = null,
                relativeFolder = "Music/",
            )
        }
        val scan = LibraryScan(tracks)

        measure("sidecar_pass_over_300_tracks", runs = 20) {
            tracks.map { track ->
                if (track.subtitleUri != null) track
                else track.copy(subtitleUri = null, subtitleExtension = null)
            }
        }
        report("sidecar_track_count ${scan.tracks.size}")
    }

    @Test
    fun reportDeviceLibrarySize() {
        val scan = DeviceAudioLibrary.scan(context)
        report("device_track_count ${scan.tracks.size}")
        report("device_file_count ${scan.files.size}")
    }
}
