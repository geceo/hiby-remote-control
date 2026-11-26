package io.github.hibyremote.protocol

/** Service and action ids (PROTOCOL.md section 6). action_id = (service << 16) | action. */
object Actions {
    const val SVC_SDP = 0xC500
    const val SVC_DEV_INFO = 0xC501
    const val SVC_MEDIA_INFO = 0xC502
    const val SVC_MEDIA_CTRL = 0xC503
    const val SVC_MEDIA_LIST = 0xC504

    private fun aid(svc: Int, act: Int): Long = ((svc.toLong() shl 16) or act.toLong())

    val CONNECT = aid(SVC_SDP, 3)
    val DISCONNECT = aid(SVC_SDP, 4)
    val DISCOVERY_SERVICES = aid(SVC_SDP, 5)

    val MANUFACTURER = aid(SVC_DEV_INFO, 0x1001)
    val MODEL = aid(SVC_DEV_INFO, 0x1002)
    val FIRMWARE = aid(SVC_DEV_INFO, 0x1004)
    val MAX_VOLUME = aid(SVC_DEV_INFO, 0x1008)

    val PLAY_STATE = aid(SVC_MEDIA_INFO, 0x1101)
    val PLAY_MODE = aid(SVC_MEDIA_INFO, 0x1102)
    val PROGRESS = aid(SVC_MEDIA_INFO, 0x1103)
    val COVER = aid(SVC_MEDIA_INFO, 0x1104)
    val VOLUME = aid(SVC_MEDIA_INFO, 0x1105)
    val CUR_PLAYLIST_NAME = aid(SVC_MEDIA_INFO, 0x1106)
    val CUR_PLAY_UUID = aid(SVC_MEDIA_INFO, 0x1107)
    val CUR_META_INFO = aid(SVC_MEDIA_INFO, 0x1108)
    val RESET_DATA = aid(SVC_MEDIA_INFO, 0x1109)
    val PLAY_ERROR_CODE = aid(SVC_MEDIA_INFO, 0x110A)

    val SET_PLAY_STATE = aid(SVC_MEDIA_CTRL, 0x1201)
    val SET_PLAY_MODE = aid(SVC_MEDIA_CTRL, 0x1202)
    val SET_VOLUME = aid(SVC_MEDIA_CTRL, 0x1203)
    val PLAY_UUID = aid(SVC_MEDIA_CTRL, 0x1204)
    val PLAY_PREVIOUS = aid(SVC_MEDIA_CTRL, 0x1205)
    val PLAY_NEXT = aid(SVC_MEDIA_CTRL, 0x1206)
    val SET_PROGRESS = aid(SVC_MEDIA_CTRL, 0x1207)
    val PLAY_TRACK_UUID = aid(SVC_MEDIA_CTRL, 0x120A)
    val SET_COVER_SIZE = aid(SVC_MEDIA_CTRL, 0x1211)

    val GET_PLAYLIST_SIZE = aid(SVC_MEDIA_LIST, 0x1301)
    val GET_PLAYLIST_ITEMS = aid(SVC_MEDIA_LIST, 0x1302)
    val GET_PLAYLIST_LISTS = aid(SVC_MEDIA_LIST, 0x1303)
    val GET_TRACK_INFO = aid(SVC_MEDIA_LIST, 0x1305)

    /** Status values the app requests; they come back as signals. */
    val STATUS = listOf(PLAY_STATE, PLAY_MODE, PROGRESS, VOLUME, CUR_PLAYLIST_NAME, CUR_PLAY_UUID, CUR_META_INFO)
}

/** Values of the PlayState signal (server mapping in MediaInfoService.getPlayState). */
object PlayState {
    const val PLAYING = 1
    const val PAUSED = 2
    const val STOPPED = 3
    const val PREPARING = 4
    const val ERROR = 5
}

object PlayMode {
    const val ORDER = 1
    const val REPEAT_ALL = 2
    const val REPEAT_ONE = 3
    const val SHUFFLE = 4
}

/** Song entry, `a(sssuuyy)`. */
data class Song(
    val name: String,
    val artist: String,
    val uuid: String,
    val index: Long,
    val sampleRate: Long,
    val quality: Long,
    val sampleBits: Long,
)

/** List entry, `a(ssuu)`. */
data class ListEntry(val name: String, val uuid: String, val index: Long, val data: Long) {
    val isAudioFile: Boolean get() = data == 80L || data == 81L
}

/** CUE/ISO sub-track, `a(ssq)`. */
data class Track(val name: String, val uuid: String, val number: Long)

/** Song metadata, `a(sssssssuuuuqyyy)`. */
data class MetaInfo(
    val title: String,
    val artist: String,
    val album: String,
    val year: String,
    val genre: String,
    val path: String,
    val note: String,
    val duration: Long,
    val fileSize: Long,
    val bitrate: Long,
    val sampleRate: Long,
    val format: Long,
    val sampleBits: Long,
    val channels: Long,
    val quality: Long,
) {
    /** The duration is in milliseconds, like the Progress signal (docs/APK_ANALYSIS.md). */
    val durationMs: Long get() = duration
}

internal fun Any?.asLong(): Long = when (this) {
    is Long -> this
    is Int -> toLong()
    is String -> toLongOrNull() ?: 0L
    else -> 0L
}

internal fun Any?.asStr(): String = when (this) {
    null -> ""
    is String -> this
    else -> toString()
}

object Decode {
    @Suppress("UNCHECKED_CAST")
    private fun rows(v: Any?): List<List<Any?>> = (v as? List<*>)?.mapNotNull { it as? List<Any?> } ?: emptyList()

    fun songs(v: Any?) = rows(v).map {
        Song(it[0].asStr(), it[1].asStr(), it[2].asStr(), it[3].asLong(), it[4].asLong(), it[5].asLong(), it[6].asLong())
    }

    fun lists(v: Any?) = rows(v).map { ListEntry(it[0].asStr(), it[1].asStr(), it[2].asLong(), it[3].asLong()) }

    fun tracks(v: Any?) = rows(v).map { Track(it[0].asStr(), it[1].asStr(), it[2].asLong()) }

    fun meta(v: Any?): MetaInfo? = rows(v).firstOrNull()?.takeIf { it.size >= 15 }?.let {
        MetaInfo(
            it[0].asStr(), it[1].asStr(), it[2].asStr(), it[3].asStr(), it[4].asStr(), it[5].asStr(), it[6].asStr(),
            it[7].asLong(), it[8].asLong(), it[9].asLong(), it[10].asLong(), it[11].asLong(), it[12].asLong(),
            it[13].asLong(), it[14].asLong(),
        )
    }
}

/**
 * Library URIs: `<depth>#<0=list of lists|1=song list>@[<category>]<name>...`
 * (PROTOCOL.md, "Library URIs").
 */
object LibraryUri {
    const val ALL_SONGS = "1#1@[01]"
    const val QUEUE = "1#1@[00]"
    const val FAVORITES = "1#1@[08]"
    const val RECENT = "1#1@[09]"
    const val RECENTLY_ADDED = "1#1@[07]"
    const val ALBUMS = "1#0@[05]"
    const val ARTISTS = "1#0@[03]"
    const val GENRES = "1#0@[04]"
    const val PLAYLISTS = "1#0@[02]"
    const val FILES_ROOT = "2#0@[06]\\"

    fun folder(path: String) = "2#0@[06]$path"

    fun isListOfLists(uri: String) = uri.length > 2 && uri[2] == '0'
    fun isFiles(uri: String) = uri.length >= 8 && uri.substring(3, 8) == "@[06]"

    enum class SearchKind(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), GENRES("Genres") }

    fun search(query: String, kind: SearchKind) = when (kind) {
        SearchKind.SONGS -> "3#1@[07]$query@[01]"
        SearchKind.ALBUMS -> "3#0@[07]$query@[05]"
        SearchKind.ARTISTS -> "3#0@[07]$query@[03]"
        SearchKind.GENRES -> "3#0@[07]$query@[04]"
    }

    private val SEARCH_LISTS = Regex("""^3#0@\[07].*@\[(\d\d)]$""")
    private val ALBUMS_OF = Regex("""^3#0@\[(03|04)](.*)@\[05]$""")

    /** URI of the list opened by tapping [entry] inside the list-of-lists [uri]. */
    fun child(uri: String, entry: ListEntry): String {
        if (isFiles(uri)) return folder(if (uri == FILES_ROOT) entry.name else entry.uuid)
        SEARCH_LISTS.find(uri)?.let {
            return when (it.groupValues[1]) {
                "03" -> "3#0@[03]${entry.name}@[05]"
                "04" -> "3#0@[04]${entry.name}@[05]"
                else -> "2#1@[05]${entry.name}"
            }
        }
        if (uri == ARTISTS || uri == GENRES) return "3#0@[${uri.substring(5, 7)}]${entry.name}@[05]"
        return "${uri[0].digitToInt() + 1}#1${uri.substring(3)}${entry.name}"
    }

    /** For "albums of an artist/genre" lists: URI of all songs of that artist/genre. */
    fun allSongsOf(uri: String): String? =
        ALBUMS_OF.find(uri)?.let { "2#1@[${it.groupValues[1]}]${it.groupValues[2]}" }
}
