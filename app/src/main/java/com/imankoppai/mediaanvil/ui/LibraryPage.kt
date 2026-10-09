package com.imankoppai.mediaanvil.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.imankoppai.mediaanvil.R
import com.imankoppai.mediaanvil.model.AudioTrack
import com.imankoppai.mediaanvil.model.TrackGroup
import com.imankoppai.mediaanvil.subtitles.PreviewLyrics
import com.imankoppai.mediaanvil.subtitles.SubtitleLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val libraryTabs = listOf(
    R.string.tab_music,
    R.string.continue_listening,
    R.string.tab_favorites,
    R.string.tab_groups,
)

/** Index of the Groups tab; the others are flat track lists. */
private const val GROUPS_TAB = 3
private const val FAVORITES_TAB = 2

private enum class MusicView { Songs, Albums, Artists, Folders }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryPage(
    library: LibraryViewModel,
    playlists: PlaylistViewModel,
    settings: SettingsViewModel,
    controller: androidx.media3.session.MediaController?,
    onRequestStorageAccess: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditTrack: (AudioTrack) -> Unit,
) {
    val context = LocalContext.current
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        // Persist the grant so lyrics and tag edits keep working after a restart.
        com.imankoppai.mediaanvil.data.SafStorage.takePersistablePermission(context, uri)
        val folder = com.imankoppai.mediaanvil.data.DeviceAudioLibrary.treeUriToRelativeFolder(uri)
        if (folder != null) {
            settings.preferences.scanFolders = settings.preferences.scanFolders + folder
            library.rescan(quiet = true)
        }
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabIndex = selectedTab.coerceIn(0, libraryTabs.lastIndex)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    var groupDialogOpen by remember { mutableStateOf(false) }
    var speedDialogOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var openTrackGroupId by remember { mutableStateOf<String?>(null) }
    var queueOpen by remember { mutableStateOf(false) }
    var musicView by rememberSaveable { mutableStateOf(MusicView.Songs) }
    var openGroup by remember { mutableStateOf<String?>(null) }

    fun syncPlaybackState(player: Player) {
        isPlaying = player.isPlaying
        // The player queue may be a filtered subset (group, search,
        // album groups, shuffle order); map by media id instead of
        // treating its index as an index into the full track list.
        val currentId = player.currentMediaItem?.mediaId
        val mapped = currentId?.let { id -> library.tracks.indexOfFirst { it.uri.toString() == id } } ?: -1
        if (mapped >= 0) library.selectedIndex = mapped
    }

    DisposableEffect(controller, library.tracks) {
        val player = controller
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = syncPlaybackState(player)
        }
        syncPlaybackState(player)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var sortMode by remember { mutableStateOf(settings.preferences.librarySort) }

    BackHandler(enabled = tabIndex == GROUPS_TAB && openTrackGroupId != null) {
        openTrackGroupId = null
    }

    val search = rememberLibrarySearch(library.tracks, searchQuery, sortMode)
    val filtered = search.tracks

    val unknownAlbumLabel = stringResource(R.string.unknown_album)
    val unknownArtistLabel = stringResource(R.string.unknown_artist)
    val groups = rememberLibraryGroups(library.tracks, unknownAlbumLabel, unknownArtistLabel)
    val albumGroups = groups?.albums.orEmpty()
    val artistGroups = groups?.artists.orEmpty()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
        TopBar(
            searchOpen = searchOpen,
            searchQuery = searchQuery,
            onSearchOpenChange = { searchOpen = it },
            onSearchQueryChange = { searchQuery = it },
            menuOpen = menuOpen,
            onMenuOpenChange = { menuOpen = it },
            onRescan = { library.rescan() },
            onFullRescan = { library.rescan(full = true) },
            hiddenTrackCount = playlists.hiddenTrackUris.size,
            onRestoreHiddenTracks = {
                playlists.restoreHiddenTracks()
                library.refreshHiddenTracks()
                library.rescan(quiet = true)
            },
            sortLabelText = sortLabel(sortMode),
            onSortCycle = {
                sortMode = when (sortMode) {
                    "fileName" -> "title"
                    "title" -> "duration"
                    else -> "fileName"
                }
                settings.preferences.librarySort = sortMode
            },
        )

        PrimaryScrollableTabRow(
            edgePadding = 0.dp,
            selectedTabIndex = tabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            libraryTabs.forEachIndexed { index, titleRes ->
                Tab(
                    selected = tabIndex == index,
                    onClick = { selectedTab = index },
                    text = { Text(stringResource(titleRes), fontWeight = FontWeight.SemiBold) },
                )
            }
        }

        when (tabIndex) {
            1 -> ContinueListeningPage(library, playlists, settings, controller, onEditTrack)
            GROUPS_TAB -> TrackGroupView(
                library = library,
                playlists = playlists,
                settings = settings,
                controller = controller,
                openGroupId = openTrackGroupId,
                onOpenGroup = { openTrackGroupId = it },
                onEditTrack = onEditTrack,
            )
            FAVORITES_TAB -> {
                val favorites = playlists.favoriteTracks(library.tracks)
                SimpleTrackList(
                    tracks = favorites,
                    emptyText = stringResource(R.string.favorites_empty),
                    library = library,
                    playlists = playlists,
                    settings = settings,
                    controller = controller,
                    onEditTrack = onEditTrack,
                )
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (library.loading || search.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                library.message?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                library.playbackError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                when {
                    library.tracks.isEmpty() && !library.loading -> when {
                        !library.storageAccessGranted -> EmptyLibrary(
                            hint = stringResource(R.string.storage_access_hint),
                            actionLabel = stringResource(R.string.storage_access_action),
                            onAction = onRequestStorageAccess,
                        )
                        settings.preferences.libraryScanMode == "folders" -> EmptyLibrary(
                            hint = stringResource(R.string.empty_folder_hint),
                            actionLabel = stringResource(R.string.empty_go_settings),
                            onAction = {
                                folderPickerLauncher.launch(
                                    com.imankoppai.mediaanvil.data.DeviceAudioLibrary.folderPickerIntent(context),
                                )
                            },
                        )
                        else -> EmptyLibrary(
                            hint = stringResource(R.string.empty_no_audio_hint),
                            actionLabel = null,
                            onAction = {},
                        )
                    }
                    else -> {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            FilterChip(
                                selected = musicView == MusicView.Songs,
                                onClick = {
                                    musicView = MusicView.Songs
                                    openGroup = null
                                },
                                label = { Text(stringResource(R.string.view_songs)) },
                            )
                            FilterChip(
                                selected = musicView == MusicView.Albums,
                                onClick = {
                                    musicView = MusicView.Albums
                                    openGroup = null
                                },
                                label = { Text(stringResource(R.string.view_albums)) },
                            )
                            FilterChip(
                                selected = musicView == MusicView.Artists,
                                onClick = {
                                    musicView = MusicView.Artists
                                    openGroup = null
                                },
                                label = { Text(stringResource(R.string.view_artists)) },
                            )
                            FilterChip(selected = musicView == MusicView.Folders, onClick = {
                                musicView = MusicView.Folders; openGroup = null
                            }, label = { Text(stringResource(R.string.view_folders)) })
                        }
                        if (selectionMode) {
                            // Select-all covers what the list currently shows, so a
                            // search narrows it down too. FlowRow keeps the extra
                            // button usable on a narrow phone screen.
                            val selectableUris = filtered.map { it.uri.toString() }
                            val allSelected = selectableUris.isNotEmpty() &&
                                selectedUris.containsAll(selectableUris)
                            androidx.compose.foundation.layout.FlowRow(
                                verticalArrangement = Arrangement.Center,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.selection_count, selectedUris.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                                androidx.compose.material3.TextButton(onClick = {
                                    selectedUris = if (allSelected) emptySet() else selectableUris.toSet()
                                }) {
                                    Text(
                                        stringResource(
                                            if (allSelected) R.string.selection_clear_all
                                            else R.string.selection_select_all,
                                        ),
                                    )
                                }
                                androidx.compose.material3.TextButton(onClick = { groupDialogOpen = true }) {
                                    Text(stringResource(R.string.selection_add_group))
                                }
                                TextButton(enabled = selectedUris.isNotEmpty(), onClick = { speedDialogOpen = true }) {
                                    Text(stringResource(R.string.batch_speed))
                                }
                                androidx.compose.material3.TextButton(onClick = {
                                    selectedUris.forEach { uri ->
                                        playlists.hideTrack(android.net.Uri.parse(uri))
                                    }
                                    library.refreshHiddenTracks()
                                    selectionMode = false
                                    selectedUris = emptySet()
                                }) {
                                    Text(stringResource(R.string.selection_remove))
                                }
                                androidx.compose.material3.TextButton(onClick = {
                                    selectionMode = false
                                    selectedUris = emptySet()
                                }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        }
                        if (groupDialogOpen) {
                            AlertDialog(
                                onDismissRequest = { groupDialogOpen = false },
                                title = { Text(stringResource(R.string.selection_add_group)) },
                                text = {
                                    Column {
                                        if (playlists.trackGroups.isEmpty()) {
                                            Text(stringResource(R.string.selection_no_groups))
                                        }
                                        playlists.trackGroups.forEach { group ->
                                            Text(
                                                group.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        playlists.addTracksToGroup(group.id, selectedUris)
                                                        groupDialogOpen = false
                                                        selectionMode = false
                                                        selectedUris = emptySet()
                                                    }
                                                    .padding(vertical = 12.dp),
                                            )
                                        }
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { groupDialogOpen = false }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                },
                            )
                        }
                        when (musicView) {
                            MusicView.Folders -> FolderBrowser(filtered, library, playlists, settings, controller, onEditTrack)
                            MusicView.Songs -> if (search.loading) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            } else if (filtered.isEmpty()) {
                                SectionPlaceholder(stringResource(R.string.no_tracks))
                            } else {
                                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                                    itemsIndexed(filtered, key = { _, track -> track.uri.toString() }) { _, track ->
                                        TrackRow(
                                            track = track,
                                            current = track.uri == library.selectedTrack?.uri,
                                            selectable = selectionMode,
                                            selected = track.uri.toString() in selectedUris,
                                            onToggleSelect = {
                                                selectedUris = if (track.uri.toString() in selectedUris) {
                                                    selectedUris - track.uri.toString()
                                                } else {
                                                    selectedUris + track.uri.toString()
                                                }
                                            },
                                            onLongClick = if (selectionMode) null else ({
                                                selectionMode = true
                                                selectedUris = setOf(track.uri.toString())
                                            }),
                                            onClick = {
                                                if (selectionMode) {
                                                    selectedUris = if (track.uri.toString() in selectedUris) {
                                                        selectedUris - track.uri.toString()
                                                    } else {
                                                        selectedUris + track.uri.toString()
                                                    }
                                                } else {
                                                    playFromLibrary(library, settings, controller, filtered.indexOf(track), filtered)
                                                }
                                            },
                                            onEdit = { onEditTrack(track) },
                                            onRemoveFromPlayer = {
                                                removeTrackFromPlayer(library, playlists, controller, track)
                                            },
                                            isFavorite = playlists.isFavorite(track.uri),
                                            onToggleFavorite = { playlists.toggleFavorite(track.uri) },
                                            highlight = searchQuery,
                                        )
                                    }
                                    item { Spacer(Modifier.height(96.dp)) }
                                }
                            }
                            MusicView.Albums -> GroupBrowser(
                                groups = albumGroups,
                                loading = groups == null,
                                openGroup = openGroup,
                                onOpenGroup = { openGroup = it },
                                groupIcon = Icons.Filled.Album,
                                library = library,
                                playlists = playlists,
                                settings = settings,
                                controller = controller,
                                onEditTrack = onEditTrack,
                            )
                            MusicView.Artists -> GroupBrowser(
                                groups = artistGroups,
                                loading = groups == null,
                                openGroup = openGroup,
                                onOpenGroup = { openGroup = it },
                                groupIcon = Icons.Filled.Person,
                                library = library,
                                playlists = playlists,
                                settings = settings,
                                controller = controller,
                                onEditTrack = onEditTrack,
                            )
                        }
                    }
                }
            }
        }
    }

    val currentTrack = library.selectedTrack
    currentTrack?.let {
        MiniPlayer(
            track = it,
            isPlaying = isPlaying,
            onToggle = {
                val player = controller
                if (player?.isPlaying == true) player.pause() else player?.play()
            },
            onOpen = onOpenPlayer,
            onQueue = { queueOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }

    if (queueOpen) {
        QueueSheet(library, controller, onDismiss = { queueOpen = false })
    }
    if (speedDialogOpen) BatchSpeedDialog(
        onApply = { settings.preferences.setTrackSpeeds(selectedUris, it) },
        onDismiss = { speedDialogOpen = false })
}

@Composable
private fun TopBar(
    searchOpen: Boolean,
    searchQuery: String,
    onSearchOpenChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onFullRescan: () -> Unit,
    hiddenTrackCount: Int,
    onRestoreHiddenTracks: () -> Unit,
    onSortCycle: () -> Unit,
    sortLabelText: String,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MediaAnvil",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.library_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                IconButton(onClick = { onSearchOpenChange(!searchOpen) }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
                Box {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_menu_label, sortLabelText)) },
                            onClick = {
                                onMenuOpenChange(false)
                                onSortCycle()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rescan)) },
                            onClick = {
                                onMenuOpenChange(false)
                                onRescan()
                            },
                        )
                        DropdownMenuItem(text = { Text(stringResource(R.string.full_rescan)) }, onClick = {
                            onMenuOpenChange(false)
                            onFullRescan()
                        })
                        if (hiddenTrackCount > 0) {
                            // Hidden records are metadata only; refresh never deletes files.
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.restore_hidden_tracks, hiddenTrackCount)) },
                                onClick = {
                                    onMenuOpenChange(false)
                                    onRestoreHiddenTracks()
                                },
                            )
                        }
                    }
                }
            }
            if (searchOpen) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}
