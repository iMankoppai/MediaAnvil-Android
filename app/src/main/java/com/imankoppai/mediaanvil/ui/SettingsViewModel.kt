package com.imankoppai.mediaanvil.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.imankoppai.mediaanvil.data.PlaybackPreferences

/**
 * Settings that screens read as Compose state, so a switch reacts immediately
 * instead of waiting for the next recomposition to re-read storage.
 *
 * Split out of [LibraryViewModel]: these values have nothing to do with the media
 * library, and they were the reason the library ViewModel had to expose the whole
 * [PlaybackPreferences] object to the settings and playback screens.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    val preferences = PlaybackPreferences(application.applicationContext)

    /** Mirrors [PlaybackPreferences.autoLoadLyrics] so open screens react immediately. */
    var autoLoadLyrics by mutableStateOf(preferences.autoLoadLyrics)
        private set

    fun updateAutoLoadLyrics(value: Boolean) {
        autoLoadLyrics = value
        preferences.autoLoadLyrics = value
    }

    /** Mirrors [PlaybackPreferences.resumePlayback] so the settings switch reacts immediately. */
    var resumePlayback by mutableStateOf(preferences.resumePlayback)
        private set

    fun updateResumePlayback(value: Boolean) {
        resumePlayback = value
        preferences.resumePlayback = value
    }

    /** Mirrors [PlaybackPreferences.showLyricsTimestamps] so open screens react immediately. */
    var showLyricsTimestamps by mutableStateOf(preferences.showLyricsTimestamps)
        private set

    fun updateShowLyricsTimestamps(value: Boolean) {
        showLyricsTimestamps = value
        preferences.showLyricsTimestamps = value
    }

    /** Epoch-ms deadline of the sleep timer, or null when off. */
    var sleepTimerEndAt by mutableStateOf<Long?>(null)
        private set

    /**
     * Arms the sleep timer. The playback service watches the stored deadline and
     * applies the stop policy, so the timer keeps running across rotation and while
     * the app sits in the background.
     */
    fun startSleepTimer(minutes: Int) {
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        preferences.sleepTimerDeadlineAt = deadline
        sleepTimerEndAt = deadline
    }

    fun cancelSleepTimer() {
        preferences.sleepTimerDeadlineAt = 0L
        sleepTimerEndAt = null
    }

    /** Re-reads the deadline so screens reflect a timer that fired elsewhere. */
    fun refreshSleepTimer() {
        val deadline = preferences.sleepTimerDeadlineAt
        sleepTimerEndAt = deadline.takeIf { it > System.currentTimeMillis() }
    }
}
