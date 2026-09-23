package com.imankoppai.mediaanvil.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.lifecycle.viewmodel.compose.viewModel
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.playback.PlaybackService

internal enum class MainTab(val titleRes: Int) {
    Media(R.string.tab_media),
    Player(R.string.tab_player),
    Settings(R.string.tab_settings),
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
fun MediaAnvilApp() {
    val context = LocalContext.current
    val library: LibraryViewModel = viewModel()
    val playlists: PlaylistViewModel = viewModel()
    val settings: SettingsViewModel = viewModel()
    val updates: UpdateViewModel = viewModel()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    // Survives the activity recreation a system locale change triggers, so
    // switching language stays on the current tab instead of resetting to Media.
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(MainTab.Media) }
    var editingTrack by remember { mutableStateOf<com.imankoppai.mediaanvil.model.AudioTrack?>(null) }

    BackHandler(enabled = editingTrack != null || tab != MainTab.Media) {
        if (editingTrack != null) editingTrack = null else tab = MainTab.Media
    }

    // Android 13+ 必须显式申请通知权限，否则系统媒体通知（以及锁屏播放控制）不会出现。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The media library only needs the audio read permission; folder-scoped writes
    // ask for a folder through the system picker instead of "all files access".
    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { library.rescan() }

    fun requestStorageAccess() {
        val needed = buildList {
            val permission = com.imankoppai.mediaanvil.data.DeviceAudioLibrary.readPermission
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(permission)
            }
        }
        if (needed.isEmpty()) {
            library.rescan()
        } else {
            audioPermission.launch(needed.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { mediaController ->
                mediaController.playbackParameters =
                    androidx.media3.common.PlaybackParameters(settings.preferences.playbackSpeed)
                mediaController.shuffleModeEnabled = settings.preferences.shuffleEnabled
                mediaController.repeatMode = settings.preferences.repeatMode
                mediaController.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        library.playbackError = when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                                context.getString(R.string.play_error_missing)
                            else -> context.getString(R.string.play_error_generic)
                        }
                    }
                })
                controller = mediaController
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(Unit) {
        library.startup()
        settings.refreshSleepTimer()
        updates.maybeCheck()
        if (library.hasStorageAccess()) {
            // Do not throw the user into a permission screen on first launch.
            // The empty-library explanation owns that explicit user action.
            requestNotificationAccess()
        }
    }

    // Shared by both navigation layouts so switching orientation keeps the same pages.
    val pageContent: @Composable () -> Unit = {
        if (editingTrack != null) {
            TagEditorPage(
                library = library,
                controller = controller,
                track = editingTrack!!,
                onBack = { editingTrack = null },
            )
        } else when (tab) {
            MainTab.Media -> CenteredPageContent {
                LibraryPage(
                    library = library,
                    playlists = playlists,
                    settings = settings,
                    controller = controller,
                    onRequestStorageAccess = ::requestStorageAccess,
                    onOpenPlayer = { tab = MainTab.Player },
                    onOpenSettings = { tab = MainTab.Settings },
                    onEditTrack = { editingTrack = it },
                )
            }
            // The player manages its own width: it has a two-pane layout for wide screens.
            MainTab.Player -> NowPlayingPage(
                library = library,
                settings = settings,
                controller = controller,
                onOpenLibrary = { tab = MainTab.Media },
            )
            MainTab.Settings -> CenteredPageContent {
                SettingsPage(
                    library = library,
                    playlists = playlists,
                    settings = settings,
                    updates = updates,
                    controller = controller,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        // The playback service runs the sleep timer; when it asks for the app to be
        // closed (定时结束后关闭软件) the screen finishes itself here.
        while (true) {
            kotlinx.coroutines.delay(2_000)
            val closedAt = settings.preferences.sleepTimerClosedAt
            if (closedAt != 0L) {
                settings.preferences.sleepTimerClosedAt = 0L
                // Only honour a fresh request: a stale flag must not close a later launch.
                if (System.currentTimeMillis() - closedAt < 20_000L) {
                    (context as? android.app.Activity)?.finishAffinity()
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Landscape keeps the side rail; portrait stays on the phone-style bottom bar.
        if (maxWidth >= RailLayoutMinWidth && maxWidth > maxHeight && editingTrack == null) {
            Scaffold(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ) { padding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    RailNavigation(tab = tab, onSelect = { tab = it })
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { pageContent() }
                }
            }
        } else {
            Scaffold(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                bottomBar = {
                    // A tablet in portrait has room for a taller bar with larger destinations.
                    val largeBar = maxWidth >= RailLayoutMinWidth
                    if (editingTrack == null) {
                        BottomNavigation(tab = tab, onSelect = { tab = it }, largeBar = largeBar)
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    pageContent()
                }
            }
        }
    }
}

/** Phone/tablet navigation kept separate so its state contract can be tested in isolation. */
@Composable
internal fun BottomNavigation(tab: MainTab, onSelect: (MainTab) -> Unit, largeBar: Boolean) {
    NavigationBar(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        modifier = Modifier.height(if (largeBar) 148.dp else 104.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            MainTab.entries.forEach { entry ->
                val selected = tab == entry
                val icon = tabIcon(entry)
                Box(
                    // The whole third is the touch target, not just the pill.
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag(entry.testTag)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(entry) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = if (largeBar) {
                            androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
                        } else {
                            CircleShape
                        },
                        color = if (selected) {
                            androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        modifier = Modifier.size(
                            width = if (largeBar) 112.dp else 68.dp,
                            height = if (largeBar) 112.dp else 68.dp,
                        ),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = if (selected) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(if (largeBar) 38.dp else 28.dp),
                            )
                            Text(
                                stringResource(entry.titleRes),
                                style = if (largeBar) {
                                    androidx.compose.material3.MaterialTheme.typography.titleMedium
                                } else {
                                    androidx.compose.material3.MaterialTheme.typography.labelMedium
                                },
                                color = if (selected) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: MainTab): ImageVector = when (tab) {
    MainTab.Media -> Icons.Filled.LibraryMusic
    MainTab.Player -> Icons.Filled.PlayCircle
    MainTab.Settings -> Icons.Filled.Settings
}

/** Side rail for wide screens: the three destinations split the rail height evenly. */
@Composable
private fun RailNavigation(tab: MainTab, onSelect: (MainTab) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(132.dp)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MainTab.entries.forEach { entry ->
            val selected = tab == entry
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(entry.testTag)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(entry) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    color = if (selected) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    modifier = Modifier.size(width = 104.dp, height = 104.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            tabIcon(entry),
                            contentDescription = null,
                            tint = if (selected) {
                                androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(34.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(entry.titleRes),
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

private val MainTab.testTag: String
    get() = when (this) {
        MainTab.Media -> UiTestTags.MediaTab
        MainTab.Player -> UiTestTags.PlayerTab
        MainTab.Settings -> UiTestTags.SettingsTab
    }
