package io.github.hibyremote.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.hibyremote.protocol.LibraryUri
import io.github.hibyremote.protocol.ListEntry
import io.github.hibyremote.protocol.Song
import io.github.hibyremote.protocol.Track
import io.github.hibyremote.remote.LibraryPage
import io.github.hibyremote.remote.RemoteController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** One level of the library navigation. */
class LibraryView(val uri: String, val title: String, val tracksOf: Pair<String, String>? = null) {
    val lists = mutableStateListOf<ListEntry>()
    val songs = mutableStateListOf<Song>()
    val tracks = mutableStateListOf<Track>()
    var size by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val isSongs: Boolean get() = tracksOf == null && !LibraryUri.isListOfLists(uri)
    val loaded: Int get() = if (isSongs) songs.size else lists.size
    val hasMore: Boolean get() = tracksOf == null && (size ?: 0) > loaded
}

/**
 * Library navigation state. A plain class owning its scope (main dispatcher,
 * because it mutates Compose snapshot state); created once per app.
 */
class LibraryModel(private val controller: RemoteController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val stack = mutableStateListOf<LibraryView>()
    private var loadJob: Job? = null

    private val _played = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when playback was started from the library. */
    val played: SharedFlow<Unit> = _played

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors

    val top: LibraryView? get() = stack.lastOrNull()

    fun reset() {
        loadJob?.cancel()
        stack.clear()
    }

    fun open(uri: String, title: String) {
        val v = LibraryView(uri, title)
        stack.add(v)
        loadMore(v)
    }

    /** Pops one level; false when already at the root. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        loadJob?.cancel()
        stack.removeAt(stack.lastIndex).loading = false
        return true
    }

    fun popTo(index: Int) {
        loadJob?.cancel()
        while (stack.size > index + 1) stack.removeAt(stack.lastIndex)
        stack.lastOrNull()?.let { if (it.loading) { it.loading = false; loadMore(it) } }
    }

    fun loadMore(v: LibraryView) {
        if (v.loading || v.tracksOf != null) return
        v.loading = true
        v.error = null
        loadJob = scope.launch {
            try {
                val size = v.size ?: controller.listSize(v.uri).also { v.size = it }
                val start = v.loaded
                if (start < size) {
                    val end = minOf(start + PAGE, size) - 1
                    when (val page = controller.listPage(v.uri, start, end)) {
                        is LibraryPage.Lists -> v.lists.addAll(page.items)
                        is LibraryPage.Songs -> v.songs.addAll(page.items)
                    }
                    // A short page means the list is smaller than announced.
                    if (v.loaded < end + 1) v.size = v.loaded
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                v.error = e.message ?: "Loading failed"
            } finally {
                v.loading = false
            }
        }
    }

    fun openEntry(v: LibraryView, entry: ListEntry) {
        if (LibraryUri.isFiles(v.uri) && v.uri != LibraryUri.FILES_ROOT && entry.isAudioFile) {
            val dir = v.uri.substring(8)
            val ext = fileExtension(entry.uuid)
            if (ext == "CUE" || ext == "ISO") openTracks(dir, entry) else play(v.uri, entry.uuid)
            return
        }
        open(LibraryUri.child(v.uri, entry), entry.name)
    }

    private fun openTracks(dir: String, entry: ListEntry) {
        scope.launch {
            try {
                val tracks = controller.trackInfo(dir, entry.uuid)
                if (tracks.isEmpty()) {
                    play(LibraryUri.folder(dir), entry.uuid)
                } else {
                    val v = LibraryView("cue:${entry.uuid}", entry.name, dir to entry.uuid)
                    v.tracks.addAll(tracks)
                    v.size = tracks.size
                    stack.add(v)
                }
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Loading failed")
            }
        }
    }

    fun play(uri: String, uuid: String) = launchPlay { controller.playUuid(uri, uuid) }
    fun playAll(v: LibraryView) = launchPlay { controller.playList(v.uri, 0) }
    fun playTrack(v: LibraryView, track: Track) {
        val (dir, file) = v.tracksOf ?: return
        launchPlay { controller.playTrack(dir, file, track.uuid) }
    }

    private fun launchPlay(block: suspend () -> Boolean) {
        scope.launch {
            try {
                if (block()) _played.tryEmit(Unit)
                else _errors.tryEmit("The player refused to start playback")
            } catch (e: Exception) {
                _errors.tryEmit(e.message ?: "Playback failed")
            }
        }
    }

    companion object {
        /** The player expects small pages (the app uses 30). */
        const val PAGE = 30
    }
}
