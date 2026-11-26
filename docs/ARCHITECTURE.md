# Architecture

HiBy Remote is a remote control for HiBy players over the HiByLink protocol
(see [PROTOCOL.md](../PROTOCOL.md)). It ships as:

| client | tech | transports | location |
|---|---|---|---|
| Android app (Android 13+) | Kotlin, Jetpack Compose | Wi-Fi, Bluetooth Classic, BLE | `apps/androidApp` |
| Desktop app (Windows, Linux) | Kotlin/JVM, Compose for Desktop | Wi-Fi | `apps/desktopApp` |
| Python CLI + web remote | Python stdlib | Wi-Fi (+ RFCOMM on Linux in the CLI) | `hibylink.py`, `webapp/` |

The Android and desktop apps share almost all their code in `apps/shared`.
Task tracking lives in [TASKS.md](TASKS.md).

## 1. Gradle layout

```
apps/                          Gradle root (settings.gradle.kts, wrapper)
├── shared/                    Kotlin Multiplatform library: androidTarget() + jvm("desktop")
│   └── src/
│       ├── jvmShared/kotlin/  ALL shared code (protocol, link, controller, UI)
│       └── jvmSharedTest/kotlin/  unit tests of the shared code
│                              (androidMain/ and desktopMain/ have no sources of their own)
├── androidApp/                com.android.application: Android entry point + Bluetooth
└── desktopApp/                kotlin("jvm") + org.jetbrains.compose: desktop entry point,
                               window icons (icons/), packaging
```

### Why a `jvmShared` source directory instead of `commonMain`

Both targets run on a JVM, and the shared code uses JDK APIs (`java.net`
sockets, `java.nio.ByteBuffer`, threads). `commonMain` cannot reference JDK
APIs. Instead of expect/actual everywhere, the directory `src/jvmShared/kotlin`
is added as a source directory of **both** `androidMain` and `desktopMain`
(`kotlin.srcDir(...)`), so it is compiled once per target with the full JDK
available. `commonMain` has no sources and only declares the dependencies
(`api(compose.*)`, coroutines), so that both apps see them. Same for `jvmSharedTest`, added to
`desktopTest` only (tests run on the desktop JVM with JUnit 4; no Android device needed).
This approach worked without any blocker with Kotlin 2.0.21 and AGP 8.7.3.

Platform differences are handled by **dependency injection** of a `Platform`
interface (section 3), not by expect/actual, because expect/actual does not work
across a plain shared source directory.

### Versions (pinned, upgrade deliberately)

Kotlin 2.0.21, Compose Multiplatform plugin `org.jetbrains.compose` 1.7.3,
`org.jetbrains.kotlin.plugin.compose` 2.0.21, AGP 8.7.3, Gradle 8.10.2,
JDK 17, kotlinx-coroutines 1.9.0 (`-swing` on desktop for `Dispatchers.Main`).
Android: compileSdk/targetSdk 35, minSdk 33.

## 2. Layers (all in `shared/src/jvmShared`, package `io.github.hibyremote`)

```
ui/          Compose screens: RemoteRoot, PlayerScreen, LibraryScreen, ConnectScreen
             (Wi-Fi part), LibraryModel, Theme, Common
remote/      RemoteController (session, state, commands, reconnection),
             HiByLinkClient (calls/returns/signals over a Transport),
             WifiDiscovery (UDP beacons), Target, PlayerState, Platform
             (+ Settings, LogLevel, nowMs)
transport/   Transport interface, StreamTransport, TcpTransport, BleFraming
             (BLE chunking, shared so that it is unit-tested)
protocol/    Codec (CRC, ArgWriter/ArgReader, Message, Frames, FrameReader),
             HiBy (action ids, values, data classes, LibraryUri), Beacon
```

Dependencies only point downwards (ui → remote → transport → protocol).
`protocol/` is pure Kotlin + JDK and must stay free of Android and Compose
imports: it is the part covered by unit tests.

Platform-specific code:

| | androidApp | desktopApp |
|---|---|---|
| entry point | `RemoteApp` (Application), `MainActivity` | `Main.kt` (`application { Window }`) |
| Platform impl | `AndroidPlatform` | `DesktopPlatform` |
| transports | Wi-Fi (shared) + `RfcommTransport`, `BleTransport` | Wi-Fi (shared) |
| extra UI | Bluetooth section of the connect screen (`bluetoothSources`), permissions, BackHandler, volume keys | keyboard shortcuts, window position/size saved in settings |
| settings store | SharedPreferences | `java.util.prefs.Preferences` |
| cover decoding | `BitmapFactory` → `asImageBitmap()` | `org.jetbrains.skia.Image.makeFromEncoded` → `toComposeImageBitmap()` |
| multicast lock | `WifiManager.MulticastLock` (`acquireMulticast`) | nothing to acquire |
| theme | Material You dynamic colors | static Material 3 light/dark scheme (`isSystemInDarkTheme()`) |

## 3. The `Platform` seam

```kotlin
interface Platform {
    val settings: Settings                       // getString/putString/remove
    fun log(level: LogLevel, tag: String, msg: String, error: Throwable? = null)
    fun decodeImage(bytes: ByteArray): ImageBitmap?
    /** Held while discovery listens; Android acquires a MulticastLock. */
    fun acquireMulticast(): AutoCloseable
    /** Transport for a target; the shared code handles Target.Wifi itself and
     *  calls this for the others (Bluetooth on Android, unsupported on desktop). */
    fun transportFor(target: Target): Transport
}
```

`RemoteController(platform, scope)`, `HiByLinkClient` and `WifiDiscovery(platform)`
take it as a constructor argument. `WifiDiscovery` itself joins `239.0.0.5` on
every interface that is up, not loopback and multicast-capable (on both
platforms; it falls back to the default join if none succeeds). `PlayerState.cover` is an `ImageBitmap?` decoded through
`platform.decodeImage`. Time uses a monotonic clock in shared code
(`System.nanoTime() / 1_000_000`), not `android.os.SystemClock`.

`LibraryModel` is a plain class owning a `CoroutineScope` on
`Dispatchers.Main.immediate` (not an androidx `ViewModel`), created once per
app (`RemoteApp` on Android, `main()` on desktop) and passed to `RemoteRoot`.
`RemoteTheme(colorScheme)` only wraps `MaterialTheme`: each app supplies its
scheme.

`ConnectScreen` (shared) shows the current player, Wi-Fi discovery and the
manual IP field, and accepts an optional slot
`extraSources: ConnectExtras` =
`@Composable (onConnect, snackbar) -> (LazyListScope.() -> Unit)`: a composable
(so it can hold state, permission launchers, effects) that returns the list
content to append. The Android app fills it with its Bluetooth section
(`bluetoothSources`). `RemoteRoot(controller, platform, library, extraSources,
backHandler)` also takes a `backHandler` hook (Android passes `BackHandler`;
default is a no-op).

Desktop keyboard shortcuts must not steal typing: text fields in the shared UI
use `Modifier.trackTextFocus()`, which maintains `TextInputFocus.active`.

## 4. Runtime behavior (shared)

- One `RemoteController` per process. It owns the `HiByLinkClient`, exposes
  `state: StateFlow<PlayerState>` and one-shot `messages`.
- Connection: open transport → `Connect("Guest", "", 0)` → `DiscoveryServices`
  → device info, `SetCoverSize(720, 720)` (not on BLE) → request status.
- The player pushes state changes and progress (every 800 ms while playing),
  so the controller does not poll progress; the UI interpolates the position.
  A full status refresh runs every 30 s (not on BLE).
- Reconnection: the last target is saved; tried once at startup, retried every
  5 s after a lost link while the app is in the foreground.
- Library: pages of 30 items (the player expects small pages).

## 5. Build, CI, distribution

- Entry point: the root `Makefile` (`make android | linux | windows`), which
  runs Gradle in Docker (`apps/docker/Dockerfile`) when no JDK is installed and
  collects the results in `dist/`. `make windows` on a non-Windows host builds
  through the GitHub Actions workflow and downloads the result.

- Local builds need only Docker (no JDK installed on the maintainer's machine):
  `ghcr.io/cirruslabs/android-sdk:35` for everything that includes Android;
  desktop packaging must run on the target OS (CI).
- `.github/workflows/android.yml`: unit tests, lint, debug + release APK.
- `.github/workflows/desktop.yml`: matrix `windows-latest` (MSI + portable ZIP)
  and `ubuntu-latest` (DEB + portable tar.gz), uploaded as artifacts and attached
  to GitHub releases on `v*` tags. The JVM runtime is bundled (jpackage), so
  users install nothing else.
- Version: `VERSION_NAME` / `VERSION_CODE` environment variables from CI,
  shared by both apps.

## 6. Known platform constraints

- Desktop discovery binds UDP 4001 and 4003. Windows Defender Firewall asks for
  permission on first launch; if refused, only the manual IP field works.
- Desktop Bluetooth is out of scope for now: the JVM has no Bluetooth API
  (would need BlueZ D-Bus on Linux and WinRT on Windows). See TASKS.md backlog.
- BLE is very slow by design of the player (~45 B/s); see docs/APK_ANALYSIS.md.
