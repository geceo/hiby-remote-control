# HiByLink protocol (remote control of HiBy DAPs)

Reverse engineered from `base.apk` (HiBy Music, package `com.hiby.music`).
Sources: Java package `com.hiby.music.smartlink.*` (jadx) and native
`lib/arm64-v8a/libhibylink.so` (Ghidra, symbols not stripped).
Static analysis only, not yet validated against a real device.
The app also embeds the HiByLink **server** used by Android-based HiBy players
(`smartlink/server`, `smartlink/service`), so player-side behavior below comes
from that code. See [docs/APK_ANALYSIS.md](docs/APK_ANALYSIS.md) for the class
map and the evidence behind each point.

All multi-byte integers are **little endian**.

## 1. Roles

- **Server** = the DAP being controlled (HiBy player running the HiByLink server).
- **Client** = the phone app (`HlClientManager`, `libhibylink.so` `hlc_*`).
  The app also contains a server (`smartlink/server/*`, `libhibylinkServer.so`)
  so a phone can be controlled too.

## 2. Transports

| Address type | Transport | Details |
|---|---|---|
| 2 | WiFi | TCP `host:4001` (port overridable via `ip:port`), TCP_NODELAY, raw frame stream |
| 1 | Bluetooth Classic | RFCOMM, service UUID `00002902-0000-1000-8000-00805f9b34fb`, raw frame stream. Client: insecure socket (fallback `createRfcommSocket(1)`); player: **secure** `listenUsingRfcommWithServiceRecord("BT_SERVER", uuid)` |
| 8 | BLE | Service `0000c501-...-00805f9b34fb`, one characteristic `00001001-...` (properties WRITE + INDICATE), MTU 20, chunked both ways (see 2.2) |

### 2.1 WiFi discovery

The server announces itself over UDP; the client only listens:

- multicast group `239.0.0.5`, port **4001**
- broadcast, port **4003** (hotspot mode)

Payload is either a 334-byte binary beacon or a JSON fallback
`{"type":2,"address":"ip:port","name":"..."}`.

Binary beacon:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `"HLBM"` |
| 4 | 2 | CRC16 over bytes `[6..334)` |
| 6 | 8 | unknown / reserved |
| 14 | 64 | address, NUL-terminated (`"192.168.x.y"` or `"ip:port"`) |
| 78 | 256 | device name, NUL-terminated |

### 2.2 BLE chunking (only when MTU != 0)

Each link frame is cut into pieces of `mtu - 2` bytes, each prefixed by:
`[seq:u8][len:u8]` where `seq` counts 0,1,2... and is `0xFF` for the last piece,
and `len` = piece length + 2. The app registers BLE links with read and write
MTU 20 (Wi-Fi and RFCOMM use 0 = no chunking), so this applies in both
directions; the chunking is done in native code, the Java layer passes raw bytes.

BLE details from the app:

- Client: plain `connectGatt`, then `setCharacteristicNotification(char, true)`.
  It never writes the CCCD descriptor; the player sends indications regardless.
  Writes are acknowledged writes (with response), one at a time.
- Player: advertises service UUID `0xC501`, its device name and manufacturer
  data (company id 1740 = `0x06CC`) holding its Classic Bluetooth MAC. It sends
  each piece with `notifyCharacteristicChanged(confirm = true)`, waits for
  `onNotificationSent` and then **sleeps 400 ms** before the next piece (up to 13
  retries). Downstream throughput is about 18 bytes / 400 ms (~45 B/s), so a
  single signal takes seconds and covers are impractical.

### 2.3 Optional heartbeat

If enabled in the app, a second TCP connection to port **4002**: the client sends
`"HLREQ: HEARTBEAT"` every ~1 s, the server answers `"HLRSP: TAEBTRAEH"`.
Not required for the control channel.

## 3. Link layer frame (24-byte header)

```
off size field
 0   4   magic        0x5AA5AA55   (bytes 55 AA A5 5A)
 4   2   crc16        CRC over bytes [6 .. frame_len)
 6   2   version      0x0100       (bytes 00 01)
 8   4   frame_len    header (24) + payload
12   4   frag_index   0,1,2... within one message
16   4   stream_seq   per-message counter (starts at 11, ++ per message)
20   4   remaining    bytes left for this message, including this frame and
                      the 24-byte headers of following frames
24   ..  payload      (<= 0x7D8 = 2008 bytes, frame <= 0x7F0)
```

CRC = **CRC-16/MODBUS** (reflected poly 0xA001, init 0xFFFF, no final xor);
the binary uses a 16-entry nibble table (`hl_crc16_calc`).

A message is sent as:
- frame 0: the message header only (section 4),
- frames 1..n: the body, cut into chunks of 2008 bytes.

The receiver reassembles by `stream_seq`; the message is complete when
`frame_len == remaining`.

## 4. Message layer (D-Bus-like, unaligned)

```
off size field
 0   2   magic     0xA5A5
 2   1   endian    0x6C ('l')
 3   1   type      1 = method call, 2 = method return, 4 = signal
 4   1   priority  0 high, 1 normal, 2 low
 5   1   version   1
 6   2   id        call id (starts at 11); a return echoes the call id
 8   4   body_len
12   4   sig_len   length of the signature including its NUL
16   ..  signature ASCII, NUL-terminated (e.g. "ussi\0")
```

Body values are packed **without alignment** in signature order:

| sig | encoding |
|---|---|
| `y` | u8 |
| `q` / `n` | u16 / i16 |
| `u` / `i` | u32 / i32 |
| `s` | u32 length (strlen+1) + bytes + NUL |
| `a<T>` | u32 count + count × T (`ay` = raw bytes) |
| `a(...)` | u32 count + count × struct fields; signature only lists the fields once |

### Body conventions

- **Call**: `u action_id` followed by the action's inputs.
- **Return**: `i error_code` (>= 0 is OK) followed by outputs.
- **Signal**: `u action_id` followed by the value.

`action_id = (service << 16) | action`.

Note: `GET_*` calls in the media-information service only return an ack;
the actual values arrive as **signals** carrying the same action id. The server
also pushes those signals spontaneously on state changes (play state, mode,
volume, meta, uuid, playlist name, cover, error code) and pushes **Progress
every 800 ms while playing (1000 ms on BLE)**, so clients do not need to poll.

## 5. Session

1. TCP connect to `:4001` (or RFCOMM / BLE).
2. Call `Connect` `0xC5000003` with `s user="Guest", s password="", i 0`.
   Wait for the return; `error >= 0` means connected.
3. (App does it) `DiscoveryServices` `0xC5000005` then `DiscoveryActions`
   `0xC5000006 (q service)` per service.
4. App's initial sync: GET scan state, manufacturer, max volume, power state,
   playlist name, current uuid, meta info, progress, play state, mode, volume,
   cover (WiFi/BT only), `SetCoverSize(720,720)`.
5. `Disconnect` `0xC5000004` (no args).

## 6. Services and actions

Services: `0xC500` SDP, `0xC501` device info, `0xC502` media info,
`0xC503` media control, `0xC504` media list, `0xC505` device config,
`0xC506` scan control.

### Media control (0xC503) - what you need for a remote

| id | name | inputs |
|---|---|---|
| 0x1201 | SetPlayState | `y` 1=play, 2=pause, 3=stop (other values ignored) |
| 0x1202 | SetPlayMode | `y` 1=order, 2=repeat all, 3=repeat one, 4=shuffle |
| 0x1203 | SetVolume | `y` level (0..MaxVolume; Android players: music stream index) |
| 0x1204 | PlayUuid | `s` list uri, `s` song uuid (see "Playing specific content") |
| 0x1205 | PlayPrevious | - |
| 0x1206 | PlayNext | - |
| 0x1207 | SetProgress | `u` position in ms |
| 0x1208 | SetFileInit | `s` name, `u` size |
| 0x1209 | SetFileData | `ay` chunk |
| 0x120A | PlayTrackUuid | `s` dir uri, `s` file path, `s` track uuid |
| 0x120B | AddToPlaylist | `s`,`s`, `a(s)` |
| 0x120C | RemoveFromPlaylist | `s`, `a(s)` |
| 0x120D | DeleteFromPlaylist | `s`, `a(s)` |
| 0x120E | CreatePlaylist | `s` |
| 0x120F | DeletePlaylist | `a(s)` |
| 0x1210 | RenamePlaylist | `s` old, `s` new |
| 0x1211 | SetCoverSize | `q` w, `q` h |

### Media info (0xC502) - inputs: none; value comes back as a signal

| id | name | signal value |
|---|---|---|
| 0x1101 | PlayState | `y` 1 playing, 2 paused, 3 stopped, 4 preparing, 5 error |
| 0x1102 | PlayMode | `y` 1 order, 2 repeat all, 3 repeat one, 4 shuffle |
| 0x1103 | Progress | `u` ms |
| 0x1104 | Cover | `ay` JPEG (quality 100, scaled to 720x720 when larger); empty when none |
| 0x1105 | Volume | `y` |
| 0x1106 | CurPlaylistName | `s` |
| 0x1107 | CurPlayUuid | `s` |
| 0x1108 | CurMetaInfo | `a(sssssss uuuu q yyy)` |
| 0x1109 | ResetData | `s` |
| 0x110A | PlayErrorCode | `i` |

### Device info (0xC501) - no inputs; outputs in the return

0x1001 manufacturer, 0x1002 model, 0x1003 serial, 0x1004 firmware,
0x1005 hardware, 0x1006 software, 0x1007 system id (all `s`);
0x1008 MaxVolume; 0x1009 PowerState (4 values).

### Media list (0xC504) - library browsing

| id | name | inputs | return (after `i`) |
|---|---|---|---|
| 0x1301 | GetPlaylistSize | `s` uri | none: **the size is the `i` return code** |
| 0x1302 | GetPlaylistItems | `s` uri, `u` start, `u` end (**inclusive**) | `a(sssuuyy)` songs |
| 0x1303 | GetPlaylistLists | `s` uri, `u` start, `u` end (**inclusive**) | `a(ssuu)` sub-lists |
| 0x1304 | GetMetaInfo | `s` uri, `s` song uuid | `a(sssssssuuuuqyyy)` |
| 0x1305 | GetTrackInfo | `s` folder uri, `s` file path | `a(ssq)` CUE/ISO tracks |

Use 0x1303 for "list of lists" URIs (`#0`) and 0x1302 for song lists (`#1`).
The app pages 30 at a time (`start`, `min(start+29, size-1)`); the native
client rejects arrays > 1000 elements.

Struct field order (recovered from the JNI constructor calls):

- **Song** `a(sssuuyy)`: name, artist, **uuid**, index, sample_rate,
  quality, sample_bits
- **List** `a(ssuu)`: name, uuid, index, data. `data` is the song count for
  albums/artists/playlists. In the file browser, `uuid` is the full path and
  `data` = 0 for a directory, 80/81 for an audio file (MMQ / not MMQ).
  For the storage roots, `name` = path and `uuid` = `"<totalGB>-<freeGB>"`.
- **Track** `a(ssq)`: name, uuid, number
- **Meta** `a(sssssssuuuuqyyy)`: title, artist, album, year, genre, file path,
  note, duration (**ms**), file size, bitrate, sample rate, format (q), sample bits,
  channels, quality

#### Library URIs

Every list is addressed by a string:
`<depth>#<kind>@[<category>]<name>[@[<sub-category>]<name>]`,
with `kind` 0 = list of lists and 1 = list of songs. The first 8 characters are a
fixed prefix; the server takes the name from offset 8.

Categories: `00` current queue, `01` all songs, `02` playlists, `03` artists,
`04` genres, `05` albums, `06` files, `07` search / recently added,
`08` favourites, `09` recently played, `10` statistics.

| URI | content |
|---|---|
| `1#1@[01]` | all songs |
| `1#1@[00]` | current play queue |
| `1#1@[08]` / `1#1@[09]` / `1#1@[07]` | favourites / recently played / recently added |
| `1#0@[05]` | all albums |
| `2#1@[05]<album>` | songs of an album (sorted disc, track) |
| `1#0@[03]` | all artists |
| `2#1@[03]<artist>` | all songs of an artist |
| `3#0@[03]<artist>@[05]` | albums of an artist |
| `4#1@[03]<artist>@[05]<album>` | songs of an artist's album |
| `1#0@[04]`, `2#1@[04]<g>`, `3#0@[04]<g>@[05]`, `4#1@[04]<g>@[05]<album>` | same for genres |
| `1#0@[02]` | all playlists |
| `2#1@[02]<playlist>` | playlist items |
| `2#0@[06]\` | storage roots (literal backslash) |
| `2#0@[06]<abs dir path>` | directory listing (`\` is converted to `/`) |
| `3#0@[07]<q>@[05]` / `@[03]` / `@[04]` | search albums / artists / genres |
| `3#1@[07]<q>@[01]` | search songs |

The "unknown" album/artist/genre are named `sDefaultsAlbumsName`,
`sDefaultsArtistsName` and `sDefaultsStylesName` on the server.

#### Playing specific content

- **Song within a list** (the list becomes the play queue):
  `PlayUuid` 0xC5031204 (`s` list uri, `s` song uuid). To launch an album,
  artist, playlist, favourites… fetch the item at the wanted index with
  `GetPlaylistItems` and play its uuid with the same URI.
- **File by path**: `PlayUuid("2#0@[06]<parent dir>", "<absolute file path>")`.
  The folder becomes the queue.
- **CUE/ISO sub-track**: `GetTrackInfo("2#0@[06]<dir>", "<file>")` then
  `PlayTrackUuid` 0xC503120A (`s` dir uri, `s` file path, `s` track uuid).
- The `CurPlaylistName` / `CurPlayUuid` signals return the URI and uuid of what
  is currently playing.

URI grammar and semantics come from the server implementation embedded in the
app (`smartlink/service/BaseService`, `MediaListService`, `MediaCtrlService`).
HiBy players running native firmware (not Android) should implement the same
contract, but that is not verified.

### Device config (0xC505) / scan control (0xC506)

0x1401 GetDeviceConfig (`s` key), 0x1402 SetDeviceConfig (`s` key, `s` value);
0x1501 SetScanMusic (`y`), 0x1502 Start / 0x1503 Stop (`s`), 0x1504 GetScanState.

### Error codes

0 OK, -1 general, -2 invalid arg, -3 no memory, -4 no link, -5 no device,
-6 new connection, -7 reconnected, -8 link closed, -10 read timeout.

## 7. Example: Connect call on the wire

```
55aaa55a e05f 0001 2d000000 00000000 0b000000 5c000000   link header
a5a5 6c 01 01 01 0b00 17000000 05000000 "ussi\0"            message header (frame 0)
... frame 1: 03 00 00 c5 | 06000000 "Guest\0" | 01000000 "\0" | 00000000
```
