# Tasks

How to use this file: one entry per task, with a status, dependencies, a
description and acceptance criteria. Keep entries short; put design in
[ARCHITECTURE.md](ARCHITECTURE.md) and protocol facts in
[PROTOCOL.md](../PROTOCOL.md) / [APK_ANALYSIS.md](APK_ANALYSIS.md).
When a task is done, set its status to `done (YYYY-MM-DD)` and add a line under
"Notes" if something surprising was learned.

Statuses: `todo`, `in progress`, `done`, `blocked`, `idea` (backlog, not
planned).

## Done

### D1. Protocol reverse engineering and Python tools — done
`PROTOCOL.md`, `hibylink.py` (CLI), `webapp/` (web remote + Docker).

### D2. Android app, Wi-Fi + Bluetooth Classic + BLE — done (2026-11-26)
Kotlin/Compose app, Android 13+. Player, library browsing, search, discovery,
volume keys, reconnection. Unit tests for the protocol layer. CI builds APKs.

### D3. APK analysis report — done (2026-11-26)
`docs/APK_ANALYSIS.md`: class map, units (ms), play states, server pushes,
BLE/RFCOMM details, open questions.

## Desktop app, Wi-Fi only (milestone M1)

### T1. Restructure the Gradle project into shared + androidApp — done (2026-11-26)
Depends on: nothing.

Move `android/` to `apps/` and split it as described in ARCHITECTURE.md
section 1: `apps/shared` (KMP library, `jvmShared` source directory added to
both `androidMain` and `desktopMain`), `apps/androidApp` (application).
Move into `shared/src/jvmShared`: `protocol/`, `transport/Transport.kt`
(interface, StreamTransport, TcpTransport), `remote/HiByLinkClient.kt`,
`remote/RemoteController.kt`, `remote/WifiDiscovery.kt`, all of `ui/` except the
Bluetooth parts. Keep in `androidApp`: `RemoteApp`, `MainActivity`,
`BluetoothTransports.kt`, `BluetoothDevices.kt`, manifest, resources.
Introduce the `Platform` interface (ARCHITECTURE.md section 3) and
`AndroidPlatform`; remove every `android.*` import from `jvmShared`
(`SystemClock`, `Log`, `Bitmap`, `BitmapFactory`, `SharedPreferences`,
`WifiManager`, `org.json`). Replace `org.json` in `Beacons.parse` with a small
hand-written extraction of `name`, `address` (strings) from the JSON beacon.
`LibraryModel` becomes a plain class with its own `CoroutineScope`.
Shared UI uses `collectAsState()` (not the lifecycle variant) and gets
`BackHandler`, permissions and the Bluetooth section from `androidApp`
(slot `extraSources` on `ConnectScreen`).
Move tests to `shared/src/jvmSharedTest`, run by `:shared:desktopTest`.

Acceptance:
- `./gradlew :shared:desktopTest :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:lintRelease` passes in the Docker build (see README).
- `grep -rn "import android\." apps/shared/src/jvmShared` returns nothing.
- The Android app behaves as before (same applicationId `io.github.hibyremote`,
  same features, Bluetooth included).
- `.github/workflows/android.yml` updated to the new paths and tasks.

### T2. Desktop app (Windows, Linux), Wi-Fi — in progress (manual check pending)
Depends on: T1.

`apps/desktopApp`: Compose for Desktop window (`Main.kt`) hosting the shared
`RemoteRoot`, with `DesktopPlatform`:
- settings in `java.util.prefs.Preferences` (node `io/github/hibyremote`),
- `decodeImage` with Skia, logging to stderr,
- `transportFor` only supports `Target.Wifi` (throws a clear error otherwise),
- static Material 3 color schemes following the system dark mode when
  detectable (default light),
- Window: title "HiBy Remote", size ~420x820 default, min size, remembered
  position/size in settings if simple, app icon (PNG/ICO generated from the
  Android launcher vector, 256 px),
- keyboard shortcuts while connected: Space play/pause, Ctrl+Right/Left
  next/previous, Ctrl+Up/Down volume ±1.
- Desktop multicast: `WifiDiscovery` joins `239.0.0.5` on every network
  interface that is up, not loopback and supports multicast (fixes multi-NIC
  Windows machines); keep the broadcast listener on 4003.
- The foreground flag of `RemoteController` follows window focus/minimized
  state (or is simply always true on desktop).
- Bluetooth entries must not appear on desktop.

Acceptance:
- `./gradlew :desktopApp:run` starts the app (manual check by the maintainer).
- `./gradlew :desktopApp:createDistributable` and `:desktopApp:packageDeb`
  succeed on Linux in CI; `:desktopApp:packageMsi` succeeds on Windows in CI.
- Connects to a player by IP and through discovery; player, library and search
  work as on Android.

### T3. Desktop packaging and CI — in progress (first CI run pending)
Depends on: T2.

`compose.desktop.application.nativeDistributions`: `TargetFormat.Msi`,
`TargetFormat.Deb`; packageName `hiby-remote`, vendor "HiBy Remote
contributors", description, `packageVersion` from `VERSION_NAME` (MSI requires
`MAJOR.MINOR.BUILD` with numeric parts: derive from the tag or fall back to
`1.0.<run number>`), Windows: `menu = true`, `shortcut = true`, stable
`upgradeUuid`, `perUserInstall = true` (no admin rights needed); Linux:
`shortcut`, menu group "AudioVideo". Run `suggestRuntimeModules` once and set
`modules(...)` explicitly (at least `java.prefs`, `jdk.unsupported` if needed).

New workflow `.github/workflows/desktop.yml`: matrix `ubuntu-latest`
(`packageDeb` + zip of `createDistributable` output as `.tar.gz`) and
`windows-latest` (`packageMsi` + zip of `createDistributable`), JDK 17
(temurin), run `:shared:desktopTest` first, upload artifacts, attach to the
GitHub release on `v*` tags (same release as the APK).

Acceptance: the workflow produces `hiby-remote-<version>.msi`,
`hiby-remote-<version>-windows.zip`, `hiby-remote_<version>_amd64.deb`,
`hiby-remote-<version>-linux.tar.gz`.

Notes: on a tag, three jobs (Android + 2 desktop) publish to one release at
the same time, so publishing uses `gh release create … || true` then
`gh release upload --clobber` instead of a release action (review fix).
The `.deb` Depends field is rewritten after `packageDeb`
(`apps/desktopApp/packaging/fix-deb-depends.sh`, Gradle task `fixDebDepends`):
jpackage copies the Ubuntu build host's names, e.g. `libjpeg-turbo8`, which
Debian lacks (it has `libjpeg62-turbo`), and the `t64` renames absent before
Debian 13. Verified with `apt-get install -s` on debian:bookworm, debian:trixie
and ubuntu:24.04 (2026-11-26).
Left to verify on the first CI run: `packageMsi` on Windows, the artifact
names, and a tag release with all six files.

### T4. Documentation — done (2026-11-26)
Depends on: T2, T3.

README: user-facing install instructions for Windows (MSI, SmartScreen
"More info → Run anyway" since it is unsigned, firewall prompt) and Linux
(`sudo apt install ./hiby-remote_*.deb` or the tar.gz), developer build
instructions for the new layout. Update ARCHITECTURE.md if the implementation
diverged, and this file's statuses and notes.

### T5. Root Makefile — done (2026-11-26)
`make android | linux | windows | all | test | clean`, outputs in `dist/`.
Docker fallback when no JDK (`apps/docker/Dockerfile`: SDK image + fakeroot,
binutils, build-tools 34). `make windows` on Linux dispatches the desktop
workflow with `gh` and downloads the Windows artifact.
Notes: verified `make android linux` in Docker (about 1.5 min warm).
`make windows` (both the Windows-host and the CI paths) is not verified yet.

## Backlog

### B1. Validate against real hardware — idea
Test Wi-Fi, RFCOMM and BLE with a real Android-based HiBy player and a HiBy OS
player; record results in APK_ANALYSIS.md section 6. Highest value item.

### B2. Desktop Bluetooth Classic on Linux — idea
RFCOMM through BlueZ (D-Bus `org.bluez.ProfileManager1` or a native socket via
JNA, `AF_BLUETOOTH`/`BTPROTO_RFCOMM`). Implement `Transport`, list paired
devices with D-Bus. Est. medium.

### B3. Desktop BLE on Linux — idea
BlueZ GATT over D-Bus; reuse the BLE chunking from `BleTransport.chunk`. Low
value given BLE speed (~45 B/s).

### B4. Desktop Bluetooth on Windows — idea
WinRT `Windows.Devices.Bluetooth` (RFCOMM `RfcommDeviceService`, GATT) through a
small native helper or JNA/JNI. Riskiest item.

### B5. Media keys and system integration — idea
Android: media session / notification with controls while connected.
Desktop: system tray icon, global media keys (JNativeHook or OS APIs).

### B6. Signed releases — idea
Android release keystore secrets (already supported by CI, see README);
Windows code signing to avoid SmartScreen warnings.

### B7. Localization — idea
Extract UI strings (Compose resources) and add French.

### B8. Connect argument check — idea
Confirm with Ghidra what `hlc_open_connection`'s 4th argument (3) does, see
APK_ANALYSIS.md 4.6. Only needed if a player refuses `Connect`.

### B9. macOS desktop build — idea
Compose for Desktop supports DMG; needs a macOS runner and notarization for a
smooth install.

## Notes

- T1 (2026-11-26): the `jvmShared` source directory approach worked as designed.
  `:shared:desktopTest` runs 15 tests (10 moved + 5 for the JSON beacon parser);
  `:androidApp:lintRelease` reports no issues. `BleTransport.chunk` moved to
  `BleFraming` (shared) so that its test stays in the shared module. Slot and
  `RemoteRoot` signatures differ slightly from the first sketch, see
  ARCHITECTURE.md section 3.
- T2/T3 (2026-11-26): verified in the Docker build (JDK 21 image, the project
  targets 17): `createDistributable` and `packageDeb` succeed (`fakeroot` and
  `binutils` must be installed in the container); the app starts under Xvfb
  with software rendering and shows the connect screen without Bluetooth.
  Not verified yet: `packageMsi` and `desktop.yml` (need the Windows/Linux
  runners), the window icon on a real desktop, connection to a real player,
  keyboard shortcuts, multicast discovery on a multi-NIC Windows machine.
  Close T2/T3 after the first green CI run and a manual check.
- `jpackage` names the files `hiby-remote-<ver>.msi` and
  `hiby-remote_<ver>_amd64.deb`; the workflow renames/archives the rest.

- Local machine has no JDK: build with Docker; `/tmp` is a small tmpfs, keep
  Gradle caches in `~/.cache/hiby-remote-build`.
