package io.github.hibyremote.remote

import androidx.compose.ui.graphics.ImageBitmap
import io.github.hibyremote.protocol.Actions
import io.github.hibyremote.protocol.Decode
import io.github.hibyremote.protocol.LibraryUri
import io.github.hibyremote.protocol.ListEntry
import io.github.hibyremote.protocol.MetaInfo
import io.github.hibyremote.protocol.PlayState
import io.github.hibyremote.protocol.Song
import io.github.hibyremote.protocol.Track
import io.github.hibyremote.protocol.asLong
import io.github.hibyremote.protocol.asStr
import io.github.hibyremote.transport.TcpTransport
import io.github.hibyremote.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Where the player is reached. */
sealed interface Target {
    val name: String

    data class Wifi(val host: String, val port: Int, override val name: String) : Target
    data class Rfcomm(val address: String, override val name: String) : Target
    data class Ble(val address: String, override val name: String) : Target

    fun encode(): String = when (this) {
        is Wifi -> "wifi|$host|$port|$name"
        is Rfcomm -> "rfcomm|$address||$name"
        is Ble -> "ble|$address||$name"
    }

    val label: String
        get() = when (this) {
            is Wifi -> "Wi-Fi · $host"
            is Rfcomm -> "Bluetooth · $address"
            is Ble -> "Bluetooth LE · $address"
        }

    companion object {
        fun decode(s: String?): Target? {
            val p = s?.split('|', limit = 4) ?: return null
            if (p.size < 4) return null
            return when (p[0]) {
                "wifi" -> Wifi(p[1], p[2].toIntOrNull() ?: 4001, p[3])
                "rfcomm" -> Rfcomm(p[1], p[3])
                "ble" -> Ble(p[1], p[3])
                else -> null
            }
        }
    }
}

enum class LinkState { IDLE, CONNECTING, CONNECTED }

data class DeviceInfo(val manufacturer: String = "", val model: String = "", val firmware: String = "")

data class PlayerState(
    val link: LinkState = LinkState.IDLE,
    val target: Target? = null,
    val error: String? = null,
    val device: DeviceInfo = DeviceInfo(),
    val maxVolume: Int = 100,
    val playState: Int? = null,
    val playMode: Int? = null,
    val volume: Int? = null,
    val progressMs: Long? = null,
    /** [nowMs] when [progressMs] was received. */
    val progressAt: Long = 0,
    val playlist: String? = null,
    val uuid: String? = null,
    val meta: MetaInfo? = null,
    val cover: ImageBitmap? = null,
) {
    val connected: Boolean get() = link == LinkState.CONNECTED
    val playing: Boolean get() = playState == PlayState.PLAYING

    /** Current position, interpolated while playing. */
    fun positionMs(now: Long = nowMs()): Long {
        val p = progressMs ?: return 0
        val pos = if (playing) p + (now - progressAt) else p
        val d = meta?.durationMs ?: 0
        return if (d > 0) pos.coerceIn(0, d) else pos.coerceAtLeast(0)
    }
}

/** One page of a library list. */
sealed interface LibraryPage {
    data class Lists(val items: List<ListEntry>) : LibraryPage
    data class Songs(val items: List<Song>) : LibraryPage
}

/**
 * Owns the link to the player and the player state. Application-scoped so the
 * link survives configuration changes.
 */
class RemoteController(private val platform: Platform, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot user-facing messages (errors). */
    val messages: SharedFlow<String> = _messages

    private val prefs = platform.settings

    @Volatile
    private var current: HiByLinkClient? = null
    private var connectJob: Job? = null
    /** Reconnect automatically after a lost link (set once a connection succeeded). */
    @Volatile
    private var retry = false
    /** Try the saved player once when the app first comes to the foreground. */
    @Volatile
    private var startupAttempt = false
    private var lastAttempt = 0L

    /** Set from the process lifecycle: polling and reconnection only run in the foreground. */
    @Volatile
    var foreground = false

    val lastTarget: Target? get() = Target.decode(prefs.getString(KEY_TARGET))

    init {
        lastTarget?.let {
            startupAttempt = true
            _state.update { s -> s.copy(target = it) }
        }
        scope.launch { housekeeping() }
    }

    // ------------------------------------------------------------ connection

    fun connect(target: Target) {
        prefs.putString(KEY_TARGET, target.encode())
        retry = false
        startupAttempt = false
        startConnect(target)
    }

    fun disconnect() {
        retry = false
        startupAttempt = false
        connectJob?.cancel()
        val c = current
        current = null
        c?.close()
        _state.value = PlayerState(target = _state.value.target)
    }

    /** Forgets the saved player (no automatic reconnection at startup). */
    fun forget() {
        disconnect()
        prefs.remove(KEY_TARGET)
        _state.value = PlayerState()
    }

    private fun startConnect(target: Target) {
        connectJob?.cancel()
        current?.close()
        current = null
        lastAttempt = nowMs()
        connectJob = scope.launch { open(target) }
    }

    private fun transportFor(target: Target): Transport = when (target) {
        is Target.Wifi -> TcpTransport(target.host, target.port)
        else -> platform.transportFor(target)
    }

    private suspend fun open(target: Target) {
        _state.value = PlayerState(link = LinkState.CONNECTING, target = target)
        var client: HiByLinkClient? = null
        try {
            val transport = withContext(Dispatchers.IO) { transportFor(target) }
            lateinit var self: HiByLinkClient
            self = HiByLinkClient(
                transport,
                platform,
                onSignal = { action, values -> if (current === self) onSignal(self, action, values) },
                onClosed = { e -> scope.launch { onLinkClosed(self, e) } },
            )
            client = self
            current = self
            withContext(Dispatchers.IO) { self.start() }
            self.login()
            try {
                self.call(Actions.DISCOVERY_SERVICES)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                platform.w(TAG, "DiscoveryServices failed", e)
            }
            if (current !== self) return
            retry = true
            _state.update { it.copy(link = LinkState.CONNECTED, error = null) }
            initialSync(self)
        } catch (e: CancellationException) {
            client?.close()
            throw e
        } catch (e: Exception) {
            platform.w(TAG, "connection to $target failed", e)
            client?.close()
            if (current === client) current = null
            _state.update { it.copy(link = LinkState.IDLE, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun onLinkClosed(client: HiByLinkClient, error: Throwable?) {
        if (current !== client) return
        current = null
        platform.i(TAG, "link closed", error)
        _state.update { it.copy(link = LinkState.IDLE, error = "Connection lost") }
    }

    private suspend fun initialSync(c: HiByLinkClient) {
        try {
            val info = DeviceInfo(
                manufacturer = c.call(Actions.MANUFACTURER).text(),
                model = c.call(Actions.MODEL).text(),
                firmware = c.call(Actions.FIRMWARE).text(),
            )
            val max = c.call(Actions.MAX_VOLUME).let { r -> if (r.ok) r.values.firstOrNull().asLong() else 0L }
            _state.update { it.copy(device = info, maxVolume = if (max in 1..255) max.toInt() else it.maxVolume) }
        } catch (e: Exception) {
            platform.w(TAG, "device info failed", e)
        }
        try {
            if (!c.lowBandwidth) c.post(Actions.SET_COVER_SIZE) { q(COVER_SIZE); q(COVER_SIZE) }
            requestStatus(c, cover = true)
        } catch (e: Exception) {
            platform.w(TAG, "status request failed", e)
        }
    }

    private fun Reply.text(): String = if (ok) values.firstOrNull().asStr() else ""

    private suspend fun requestStatus(c: HiByLinkClient, cover: Boolean) {
        for (a in Actions.STATUS) c.post(a)
        if (cover && !c.lowBandwidth) c.post(Actions.COVER)
    }

    /**
     * Refreshes status now and then and reconnects after a lost link. Progress is
     * not polled: the player pushes it while playing (every 800 ms, 1 s on BLE).
     */
    private suspend fun housekeeping() {
        var tick = 0L
        while (true) {
            delay(1000)
            tick++
            if (!foreground) continue
            val c = current
            val s = _state.value
            if (c == null || c.closed) {
                val t = s.target
                if (t != null && s.link != LinkState.CONNECTING) {
                    if (startupAttempt) {
                        startupAttempt = false
                        startConnect(t)
                    } else if (retry && nowMs() - lastAttempt > RECONNECT_MS) {
                        startConnect(t)
                    }
                }
                continue
            }
            if (!s.connected) continue
            try {
                // The player pushes state changes itself; this only catches missed ones.
                // Skipped on BLE, where each signal takes seconds to arrive.
                if (!c.lowBandwidth && tick % 30 == 0L) requestStatus(c, cover = false)
            } catch (e: Exception) {
                platform.w(TAG, "poll failed", e)
            }
        }
    }

    // ------------------------------------------------------------ signals

    private fun onSignal(c: HiByLinkClient, action: Long, values: List<Any?>) {
        val v = values.firstOrNull()
        when (action) {
            Actions.PLAY_STATE -> _state.update { it.copy(playState = v.asLong().toInt()) }
            Actions.PLAY_MODE -> _state.update { it.copy(playMode = v.asLong().toInt()) }
            Actions.VOLUME -> _state.update { it.copy(volume = v.asLong().toInt()) }
            Actions.PROGRESS -> _state.update {
                it.copy(progressMs = v.asLong(), progressAt = nowMs())
            }
            Actions.CUR_PLAYLIST_NAME -> _state.update { it.copy(playlist = v.asStr()) }
            Actions.CUR_PLAY_UUID -> {
                val uuid = v.asStr()
                val changed = uuid != _state.value.uuid
                _state.update { it.copy(uuid = uuid) }
                if (changed) scope.launch {
                    try {
                        c.post(Actions.CUR_META_INFO)
                        if (!c.lowBandwidth) c.post(Actions.COVER)
                        c.post(Actions.PROGRESS)
                    } catch (_: Exception) {
                    }
                }
            }
            Actions.CUR_META_INFO -> _state.update { it.copy(meta = Decode.meta(v)) }
            Actions.COVER -> {
                val bytes = v as? ByteArray
                val bmp = if (bytes != null && bytes.isNotEmpty()) {
                    try {
                        platform.decodeImage(bytes)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                _state.update { it.copy(cover = bmp) }
            }
            Actions.PLAY_ERROR_CODE -> {
                val code = v.asLong()
                if (code < 0) _messages.tryEmit("The player reported a playback error ($code)")
            }
            Actions.RESET_DATA -> scope.launch {
                try {
                    requestStatus(c, cover = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Asks the player for its full status again (e.g. when the app returns to the foreground). */
    fun refresh() {
        val c = current ?: return
        if (!_state.value.connected) return
        scope.launch {
            try {
                requestStatus(c, cover = false)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------ commands

    private fun command(block: suspend (HiByLinkClient) -> Reply?) {
        val c = current ?: run {
            _messages.tryEmit("Not connected to a player")
            return
        }
        scope.launch {
            try {
                val r = block(c)
                if (r != null && !r.ok) _messages.tryEmit("The player refused the command (error ${r.code})")
                // Android players push changes themselves; other firmwares may not,
                // so ask explicitly, except on the slow BLE link.
                if (!c.lowBandwidth) {
                    c.post(Actions.PLAY_STATE)
                    c.post(Actions.PLAY_MODE)
                    c.post(Actions.VOLUME)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Command failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun togglePlay() {
        val target = if (_state.value.playing) PlayState.PAUSED else PlayState.PLAYING
        _state.update { it.copy(playState = target, progressMs = it.positionMs(), progressAt = nowMs()) }
        command { it.call(Actions.SET_PLAY_STATE) { y(target) } }
    }

    fun stop() = command { it.call(Actions.SET_PLAY_STATE) { y(PlayState.STOPPED) } }
    fun next() = command { it.call(Actions.PLAY_NEXT) }
    fun previous() = command { it.call(Actions.PLAY_PREVIOUS) }

    fun setPlayMode(mode: Int) {
        _state.update { it.copy(playMode = mode) }
        command { it.call(Actions.SET_PLAY_MODE) { y(mode) } }
    }

    fun setVolume(level: Int) {
        val v = level.coerceIn(0, _state.value.maxVolume)
        _state.update { it.copy(volume = v) }
        command { it.call(Actions.SET_VOLUME) { y(v) } }
    }

    fun volumeStep(delta: Int) {
        val cur = _state.value.volume ?: return
        setVolume(cur + delta)
    }

    fun seek(positionMs: Long) {
        _state.update { it.copy(progressMs = positionMs, progressAt = nowMs()) }
        command { it.call(Actions.SET_PROGRESS) { u(positionMs) } }
    }

    // ------------------------------------------------------------ library

    private fun requireClient(): HiByLinkClient = current?.takeIf { _state.value.connected }
        ?: throw IOException("Not connected to a player")

    /** Size of a list; the player returns it in the error-code slot. */
    suspend fun listSize(uri: String): Int {
        val r = requireClient().call(Actions.GET_PLAYLIST_SIZE, LIST_TIMEOUT) { s(uri) }
        return r.code.coerceAtLeast(0).toInt()
    }

    /** Items [start..end] (end inclusive) of a list. */
    suspend fun listPage(uri: String, start: Int, end: Int): LibraryPage {
        val c = requireClient()
        return if (LibraryUri.isListOfLists(uri)) {
            val r = c.call(Actions.GET_PLAYLIST_LISTS, LIST_TIMEOUT) { s(uri); u(start.toLong()); u(end.toLong()) }
            LibraryPage.Lists(if (r.ok) Decode.lists(r.values.firstOrNull()) else emptyList())
        } else {
            val r = c.call(Actions.GET_PLAYLIST_ITEMS, LIST_TIMEOUT) { s(uri); u(start.toLong()); u(end.toLong()) }
            LibraryPage.Songs(if (r.ok) Decode.songs(r.values.firstOrNull()) else emptyList())
        }
    }

    suspend fun trackInfo(dir: String, file: String): List<Track> {
        val r = requireClient().call(Actions.GET_TRACK_INFO, LIST_TIMEOUT) { s(LibraryUri.folder(dir)); s(file) }
        return if (r.ok) Decode.tracks(r.values.firstOrNull()) else emptyList()
    }

    /** Plays [uuid] with list [uri] as the play queue. */
    suspend fun playUuid(uri: String, uuid: String): Boolean {
        val c = requireClient()
        val r = c.call(Actions.PLAY_UUID, LIST_TIMEOUT) { s(uri); s(uuid) }
        requestStatus(c, cover = true)
        return r.ok
    }

    /** Plays a whole song list from [index]. */
    suspend fun playList(uri: String, index: Int = 0): Boolean {
        val page = listPage(uri, index, index) as? LibraryPage.Songs
        val song = page?.items?.firstOrNull() ?: return false
        return playUuid(uri, song.uuid)
    }

    suspend fun playTrack(dir: String, file: String, trackUuid: String): Boolean {
        val c = requireClient()
        val r = c.call(Actions.PLAY_TRACK_UUID, LIST_TIMEOUT) { s(LibraryUri.folder(dir)); s(file); s(trackUuid) }
        requestStatus(c, cover = true)
        return r.ok
    }

    companion object {
        private const val TAG = "HiByRemote"
        private const val KEY_TARGET = "target"
        private const val RECONNECT_MS = 5000L
        private const val LIST_TIMEOUT = 15000L
        private const val COVER_SIZE = 720
    }
}
