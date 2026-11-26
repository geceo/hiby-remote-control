# HiBy Music APK analysis

Notes from decompiling the original app, so the work does not have to be redone.
PROTOCOL.md is the protocol spec; this file records where each fact comes from,
what was verified and what is still open.

## 1. Input

| item | value |
|---|---|
| file | `base.apk` (repository root, git-ignored, never commit it) |
| package | `com.hiby.music` |
| version | 4.3.11 (versionCode 5587), minSdk 21 |
| sha256 | `ef4aa1a62f42757c621974d03ef48d5dd7b0b5ac3581b97b28cfb953ff865de5` |
| HiByLink native libs | `lib/arm64-v8a/libhibylink.so` (client, 78 KB), `libhibylinkServer.so` (server, 116 KB) |

If the APK changes, compare its sha256 first: the notes below apply to this one.

## 2. Reproducing the decompilation

jadx 1.5.1 in Docker (no local JDK needed). The output is kept outside the
repository because it is proprietary code:

```sh
C=~/.cache/hiby-remote-build
mkdir -p $C/tools && cd $C/tools
curl -sSL -o jadx.zip https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
unzip -q jadx.zip -d jadx
cp /path/to/base.apk $C/base.apk
docker run --rm -u $(id -u):$(id -g) -v $C:/w -w /w eclipse-temurin:17-jre \
  sh -c 'JAVA_OPTS=-Xmx6g tools/jadx/bin/jadx -j 6 --log-level ERROR -d jadx base.apk'
```

It takes a few minutes. jadx exits with status 1 because about 29 methods fail
to decompile (none of them matter here, except `BleServer.doAction`, which
jadx prints as readable smali-like pseudo code). Sources end up in
`$C/jadx/sources/`. Native code (`hlc_*`, `hl_crc16_calc`...) needs Ghidra
(the symbols are not stripped); it was analysed earlier, see PROTOCOL.md.

## 3. Class map

All paths are under `sources/com/hiby/music/smartlink/`.

| area | class | role |
|---|---|---|
| client core | `client/HlClientManager.java` | one session; `initMtu()` (per transport MTU/throughput), `openConnection()` (`SmartHL.openConnection(uuid, "Guest", "", 3)`), action/notify callbacks, library URIs |
| client core | `client/ControllerModelImpl.java` | `initDevice()`: address type 1 → `HlBtManager`, 2 → `HlWifiManager`, 8 → `HlBleManager` |
| client transports | `client/wifi/HlWifiTask.java`, `client/bt/HlBtTask.java`, `client/ble/HlBleTask.java` | raw byte pipes called by native code through `onRead`/`onWrite` |
| client discovery | `client/ServerDiscoverUtil.java` | Wi-Fi multicast/broadcast listeners, BLE scan, Classic scan |
| heartbeat | `client/wifi/HeartbeatClient.java`, `server/HeartbeatServer.java` | optional TCP 4002 `HLREQ: HEARTBEAT` / `HLRSP: TAEBTRAEH` |
| JNI | `hl/SmartHL.java` | client natives (`native_hlc_open_connection`, `native_hlc_service_action_call`, `native_hl_register_com_dev(uuid, r_mtu, w_mtu, throughput, mode)`, `native_hlc_broadcast_check`) |
| server transports | `server/WifiServer.java`, `server/BtServer.java`, `server/BleServer.java`, `server/HotspotsServer.java` | player side of each transport |
| server JNI | `server/JNIManager.java` | `native_notify_*` pushes (play state, mode, volume, progress, meta, uuid, cover, error) |
| server services | `service/MediaCtrlService.java`, `MediaInfoService.java`, `MediaListService.java`, `BaseService.java` | what the player does for each action; library URI grammar |
| progress push | `util/NotifyProgressHelper.java` | periodic Progress signal while playing |
| remote player UI glue | `../smartplayer/player/HibyLinkPlayer.java` | how the app maps remote meta/progress to its player UI |

Android-based HiBy players run this same app, so the `server/` and `service/`
classes are the real player implementation for them. Players running HiBy OS
(native firmware) implement the server in native code that is not in this APK.

## 4. Findings

Each point says where it comes from. "Verified" means read in the decompiled
code (static analysis), not tested on hardware.

### 4.1 Units and values (verified)

- **Duration and progress are in milliseconds.** `HibyLinkPlayer` puts the
  meta `duration` into `currentAudioDuration()` and the Progress signal into
  `currentPosition()`; the UI formats both with `MusicUtils.makeTimeString`,
  which divides by 1000. Server side, Progress is `PlayerManager.currentPosition()`
  and SetProgress calls `PlayerManager.seek(ms)`. The old "< 30000 means
  seconds" heuristic was wrong and has been removed from both remotes.
- **PlayState** (`MediaInfoService.getPlayState`): PLAYING 1, PAUSE 2, STOP 3,
  PREPARING 4, Error 5 (anything else 2).
- **PlayMode** (`getPlayMode` / `setPlayMode`): 1 ORDER, 2 LIST_LOOP, 3 SINGLE,
  4 RANDOM; unknown values set ORDER.
- **SetPlayState** (`MediaCtrlService.setPlayState`): 1 `playOrPause(true)`,
  2 `playOrPause(false)`, 3 `stop()`, other values ignored. Returns the value.
- **Volume**: `AudioTools.get/setCurMusicVolume`, i.e. the Android music stream
  index; MaxVolume is that stream's maximum.
- **Cover**: JPEG, quality 100, scaled down to 720x720 when larger
  (`MediaInfoService.sortingBitmap`); an empty cover is sent as no data.

### 4.2 Server pushes (verified)

- `JNIManager` pushes PlayState, PlayMode, meta, CurPlayUuid, playlist name,
  cover and error code on player events.
- `NotifyProgressHelper` pushes Progress while the state is playing, every
  **800 ms** (`u9.C5057a.f60846l`) or **1000 ms** on BLE. Clients do not need
  to poll progress; the Android remote no longer does.

### 4.3 Wi-Fi (verified)

- Control channel TCP 4001; discovery multicast `239.0.0.5:4001` (beacon parsed
  in native code by `native_hlc_broadcast_check`).
- Hotspot mode (`HotspotsServer`): JSON `{"type":2,"address":"<ip>:4001","name":...}`
  sent every second to the subnet broadcast address on UDP 4003. Matches
  PROTOCOL.md 2.1.

### 4.4 Bluetooth Classic (verified)

- Service UUID `00002902-0000-1000-8000-00805f9b34fb` on both sides
  (`HlBtManager.CLIENT_UUID`, `BtServer.CLIENT_UUID`).
- Client (`HlBtTask.connect`): `createInsecureRfcommSocketToServiceRecord(uuid)`,
  then sleeps 200 ms. The fallback `createRfcommSocket(1)` (hidden API, secure)
  is effectively unreachable because of the nested try/catch.
- Player (`BtServer.acceptClient`): **secure**
  `listenUsingRfcommWithServiceRecord("BT_SERVER", uuid)`. An insecure client
  socket works on a bonded link, so the player must be paired first.
- Discovery (`SearchBtThread`): lists bonded devices plus `startDiscovery()`
  results, with no check that they are HiBy players.
- No chunking: `r_mtu = w_mtu = 0` for address type 1.

### 4.5 Bluetooth LE (verified)

- Service `0000c501-…`, one characteristic `00001001-…` created with properties
  `0x28` = **WRITE + INDICATE** and permissions 0x10 (write); CCCD `0x2902`
  with read/write permissions (`BleServer.createBleService`). A
  `00001002-…` "read" UUID is declared but never used.
- Advertising (`BleServer.init`): device name, service UUID `0xC501`,
  manufacturer data company id **1740 (0x06CC)** containing the player's
  Classic Bluetooth MAC (6 bytes). Connectable, low-latency mode.
- Client scan (`SearchBleThread`): legacy `startLeScan`, keeps devices whose
  scan record lists service `0xC501`.
- Client connect (`HlBleTask.connect`): `connectGatt(ctx, false, cb)` (no
  explicit transport), discover services, find the characteristic,
  `setCharacteristicNotification(true)`. **It never writes the CCCD.** 12 s
  connect timeout.
- Client write: one `writeCharacteristic` per buffer, waits for
  `onCharacteristicWrite` (5 s timeout). Received indications go into a ring
  buffer read by native code.
- MTU: `HlClientManager.initMtu()` uses `r_mtu = w_mtu = 20` for type 8, so the
  native layer chunks **both directions** with the `[seq][len]` header of
  PROTOCOL.md 2.2. Throughput hint 102400.
- Player send (`BleServer.doAction`, pseudo code): `notifyCharacteristicChanged(dev,
  char, confirm=true)` (indication), waits for `onNotificationSent`, then
  **sleeps 400 ms**, up to 13 retries. Downstream is therefore about 18 bytes
  per 400 ms (~45 B/s). A small signal (2 frames, ~75 bytes, 5 pieces) takes
  about 2 s and the 1 s Progress push alone can saturate the link.
- Player receive: each write is acknowledged with `sendResponse` and appended to
  a ring buffer.

### 4.6 Session

- `SmartHL.openConnection(uuid, "Guest", "", 3)`: the 4th argument is 3 on the
  Java side, while the Ghidra analysis found `i 0` on the wire (PROTOCOL.md 7).
  The 3 is probably a native-side parameter (timeout or mode), not the
  serialized value. **Open**: confirm in `hlc_open_connection` with Ghidra if
  Connect is ever refused.
- After connecting, the app calls DiscoveryServices (`initServices`).

## 5. Impact on this repository

Applied after this analysis:

- `android/`: duration in ms; no progress polling (server pushes); on BLE no
  periodic status refresh and no follow-up status requests after commands;
  BLE CCCD write made best-effort (indication value when the characteristic
  supports it), as the app never writes it; RFCOMM tries insecure, then secure,
  then channel 1 (`createRfcommSocket`, as the app); PlayState 4/5 constants.
- `webapp/static/index.html`: duration in ms.
- `PROTOCOL.md`: units, play state values, pushes, BLE and RFCOMM details.

## 6. Open questions

- Behavior of HiBy OS (non-Android) players: same contract expected, unverified.
- Connect's last `i` argument (4.6).
- Everything is static analysis; nothing has been tested against a real player.
