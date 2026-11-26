package io.github.hibyremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hibyremote.protocol.LibraryUri
import io.github.hibyremote.remote.PlayerState

private data class Category(val title: String, val uri: String, val icon: ImageVector)

private val CATEGORIES = listOf(
    Category("Play queue", LibraryUri.QUEUE, Icons.AutoMirrored.Rounded.QueueMusic),
    Category("Albums", LibraryUri.ALBUMS, Icons.Rounded.Album),
    Category("Artists", LibraryUri.ARTISTS, Icons.Rounded.Person),
    Category("Genres", LibraryUri.GENRES, Icons.Rounded.Category),
    Category("Playlists", LibraryUri.PLAYLISTS, Icons.Rounded.PlaylistPlay),
    Category("All songs", LibraryUri.ALL_SONGS, Icons.Rounded.MusicNote),
    Category("Favourites", LibraryUri.FAVORITES, Icons.Rounded.Favorite),
    Category("Recently played", LibraryUri.RECENT, Icons.Rounded.History),
    Category("Recently added", LibraryUri.RECENTLY_ADDED, Icons.Rounded.NewReleases),
    Category("Folders", LibraryUri.FILES_ROOT, Icons.Rounded.Folder),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    model: LibraryModel,
    state: PlayerState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(model) { model.played.collect { onClose() } }
    LaunchedEffect(model) { model.errors.collect { snackbar.showSnackbar(it) } }

    val view = model.top
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(view?.title ?: "Library", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val size = view?.size
                        if (view != null && size != null) {
                            Text(
                                if (view.isSongs) "$size song${if (size == 1) "" else "s"}"
                                else "$size item${if (size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (view != null) IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close library")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (view == null) LibraryRoot(model) else LibraryList(model, view, state)
        }
    }
}

@Composable
private fun LibraryRoot(model: LibraryModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(LibraryUri.SearchKind.SONGS) }
    fun search() {
        val q = query.trim()
        if (q.isNotEmpty()) model.open(LibraryUri.search(q, kind), "“$q”")
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().trackTextFocus(),
                    singleLine = true,
                    label = { Text("Search the player's library") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryUri.SearchKind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
            }
            HorizontalDivider()
        }
        CATEGORIES.forEach { c ->
            item(key = c.uri) {
                ListItem(
                    modifier = Modifier.clickableRow { model.open(c.uri, c.title) },
                    headlineContent = { Text(c.title) },
                    leadingContent = { Icon(c.icon, null) },
                )
            }
        }
    }
}

@Composable
private fun LibraryList(model: LibraryModel, view: LibraryView, state: PlayerState) {
    val listState = remember(view) { LazyListState() }
    LoadMoreOnScroll(listState, view, model)
    val isFiles = LibraryUri.isFiles(view.uri)
    val isRoots = view.uri == LibraryUri.FILES_ROOT

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        val allSongs = LibraryUri.allSongsOf(view.uri)
        if ((view.isSongs && (view.size ?: 0) > 0) || allSongs != null) {
            item(key = "actions") {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (view.isSongs) FilledTonalButton(onClick = { model.playAll(view) }) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Text("Play all", Modifier.padding(start = 4.dp))
                    }
                    if (allSongs != null) OutlinedButton(onClick = { model.open(allSongs, "All songs") }) {
                        Text("All songs")
                    }
                }
            }
        }

        when {
            view.tracksOf != null -> itemsIndexed(view.tracks, key = { i, _ -> "t$i" }) { _, t ->
                ListItem(
                    modifier = Modifier.clickableRow { model.playTrack(view, t) },
                    headlineContent = { Text(t.name.ifBlank { "Track ${t.number}" }) },
                    leadingContent = { Text("${t.number}", style = MaterialTheme.typography.labelLarge) },
                )
            }
            view.isSongs -> itemsIndexed(view.songs, key = { i, _ -> "s$i" }) { i, s ->
                val current = s.uuid == state.uuid && view.uri == state.playlist
                ListItem(
                    modifier = Modifier.clickableRow { model.play(view.uri, s.uuid) },
                    headlineContent = { Text(s.name.ifBlank { "(untitled)" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (s.artist.isNotBlank()) {
                        { Text(s.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else null,
                    leadingContent = {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 28.dp))
                    },
                    trailingContent = {
                        val q = listOfNotNull(
                            s.sampleBits.takeIf { it > 0 }?.let { "$it" },
                            formatRate(s.sampleRate).removeSuffix(" kHz").ifBlank { null },
                        ).joinToString("/")
                        if (q.isNotEmpty()) Text(q, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = if (current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else ListItemDefaults.colors(),
                )
            }
            else -> itemsIndexed(view.lists, key = { i, _ -> "l$i" }) { _, e ->
                val audioFile = isFiles && !isRoots && e.isAudioFile
                val supporting = when {
                    isRoots -> e.uuid.split('-').takeIf { it.size == 2 }?.let { "${it[1]} GB free of ${it[0]} GB" }
                    audioFile -> fileExtension(e.uuid)
                    !isFiles && e.data > 0 -> "${e.data} song${if (e.data == 1L) "" else "s"}"
                    else -> null
                }
                ListItem(
                    modifier = Modifier.clickableRow { model.openEntry(view, e) },
                    headlineContent = { Text(e.name.ifBlank { "(unknown)" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = supporting?.let { { Text(it) } },
                    leadingContent = {
                        Icon(
                            when {
                                isRoots -> Icons.Rounded.SdStorage
                                audioFile -> Icons.Rounded.AudioFile
                                isFiles -> Icons.Rounded.Folder
                                else -> Icons.Rounded.LibraryMusic
                            },
                            null,
                        )
                    },
                )
            }
        }

        item(key = "footer") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    view.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    view.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(view.error ?: "", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { model.loadMore(view) }) { Text("Retry") }
                    }
                    view.loaded == 0 && view.tracksOf == null -> Text(
                        "This list is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreOnScroll(listState: LazyListState, view: LibraryView, model: LibraryModel) {
    val nearEnd by remember(listState, view) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, view.loaded, view.size) {
        if (nearEnd && view.hasMore && !view.loading && view.error == null) model.loadMore(view)
    }
}
