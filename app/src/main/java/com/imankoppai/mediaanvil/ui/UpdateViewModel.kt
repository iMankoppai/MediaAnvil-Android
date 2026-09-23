package com.imankoppai.mediaanvil.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imankoppai.mediaanvil.data.PlaybackPreferences
import com.imankoppai.mediaanvil.update.AppRelease
import com.imankoppai.mediaanvil.update.AppUpdateChecker
import com.imankoppai.mediaanvil.update.AppUpdateInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the in-app update flow: the daily background check, the download with its
 * progress, and handing the verified APK to the system installer.
 *
 * Split out of [LibraryViewModel] because none of it touches the media library; it
 * is the only state in the app that performs network I/O on its own schedule.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val preferences = PlaybackPreferences(appContext)

    /** Newest GitHub release when an in-app update is available, else null. */
    var release by mutableStateOf<AppRelease?>(null)
        private set

    /** -1 while idle, 0..100 while the update APK is downloading. */
    var progress by mutableIntStateOf(-1)
        private set

    /** True once the update APK is fully downloaded and ready to install. */
    var apkReady by mutableStateOf(false)
        private set

    /** True when the last download attempt failed; offers a retry. */
    var failed by mutableStateOf(false)
        private set

    fun report(release: AppRelease?) {
        this.release = release
        progress = -1
        apkReady = false
        failed = false
    }

    fun dismiss() {
        release = null
    }

    /**
     * Silent startup check for a newer GitHub release, throttled to once a day.
     * The timestamp is only recorded on success so a failed check (offline) is
     * retried on the next launch instead of being suppressed for a day.
     */
    fun maybeCheck() {
        val now = System.currentTimeMillis()
        if (now - preferences.updateLastCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { AppUpdateChecker.fetchLatest() }.getOrNull()
            } ?: return@launch
            preferences.updateLastCheckAt = System.currentTimeMillis()
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: return@launch
            if (AppUpdateChecker.isNewer(found.tagName, current)) {
                report(found)
            }
        }
    }

    fun startDownload() {
        val release = release ?: return
        if (progress >= 0) return
        progress = 0
        failed = false
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppUpdateInstaller.downloadApk(
                        appContext,
                        release.apkUrl,
                        release.sha256Url,
                    ) { percent ->
                        viewModelScope.launch {
                            if (progress in 0..99) progress = percent
                        }
                    }
                }
            }
            result.onSuccess {
                progress = 100
                failed = false
                apkReady = true
                if (AppUpdateInstaller.canInstall(appContext)) {
                    install()
                }
            }.onFailure {
                progress = -1
                apkReady = false
                failed = true
            }
        }
    }

    /** Launches the system installer, or the one-time unknown-sources permission page. */
    fun install() {
        if (!apkReady) return
        if (AppUpdateInstaller.canInstall(appContext)) {
            AppUpdateInstaller.install(appContext, AppUpdateInstaller.apkFile(appContext))
        } else {
            AppUpdateInstaller.unknownSourcesSettings(appContext)
        }
    }

    private companion object {
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
