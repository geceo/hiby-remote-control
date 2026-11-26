# HiByLink tools

Reverse-engineered client for the HiByLink remote-control protocol used by the
HiBy Music app and HiBy players. See [PROTOCOL.md](PROTOCOL.md) for the spec.

- `hibylink.py`: stdlib-only Python client and CLI (`./hibylink.py -h`).
- `webapp/`: a small web remote (now playing, transport, volume, seek,
  play mode, library browsing, search, folders, playing albums/playlists/files).
- `apps/`: native remotes written in Kotlin/Compose, sharing one code base:
  an Android app (Android 13 and later) and a desktop app (Windows, Linux),
  see below. Design notes: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md),
  task list and backlog: [docs/TASKS.md](docs/TASKS.md).

## Building

A `Makefile` at the repository root builds everything; results go to `dist/`.

| command | builds | output in `dist/` |
|---|---|---|
| `make android` | Android app | `hiby-remote-<ver>.apk`, `hiby-remote-<ver>-debug.apk` |
| `make linux` | Linux desktop app | `hiby-remote_<ver>_amd64.deb`, `hiby-remote-<ver>-linux.tar.gz` |
| `make windows` | Windows desktop app | `hiby-remote-<ver>.msi`, `hiby-remote-<ver>-windows.zip` |
| `make all` | android + linux (+ windows on a Windows host) | |
| `make test` | unit tests of the shared code | |
| `make clean` | removes build outputs and `dist/` | |

Requirements:

- **Docker only** is enough on Linux: when no `java` is found, builds run in a
  container (image built from `apps/docker/Dockerfile` on first use, Gradle
  cache in `~/.cache/hiby-remote-build`). Force a mode with `USE_DOCKER=1` or
  `USE_DOCKER=0` (the latter needs JDK 17 and the Android SDK, e.g. Android
  Studio, with `ANDROID_HOME` set).
- **Windows packages** can only be built on Windows, because jpackage bundles the
  host's runtime. On Windows, run `make windows` from Git Bash or MSYS2 with
  JDK 17 installed. On Linux, `make windows` starts the `Desktop app` GitHub
  Actions workflow on the current branch, waits for it and downloads the
  Windows files: it needs the [GitHub CLI](https://cli.github.com/) (`gh auth
  login`) and builds what is **pushed**, not local changes.
- Version: `make linux VERSION_NAME=1.2.0` (default `0.1.0`; desktop packages
  need a numeric `X.Y.Z`). Android also takes `VERSION_CODE`.

The first build downloads Gradle, the SDK pieces and dependencies (several
minutes); later builds take about a minute.

## Android remote

`apps/androidApp` is a standalone Kotlin/Jetpack Compose app that only does remote
control: it does not play music itself. It talks HiByLink directly to the
player over:

- **Wi-Fi**: players are discovered from their UDP beacons (multicast
  `239.0.0.5:4001` and broadcast `:4003`), or you type the IP address.
- **Bluetooth Classic**: RFCOMM to a paired player (pair it in the Android
  Bluetooth settings first).
- **Bluetooth LE**: scan for players advertising the HiByLink service `0xC501`.

Features: now playing (cover, title, artist, album, format), play/pause, stop,
previous/next, seek, volume (the phone's volume keys also drive the player's
volume while connected), play mode, and library browsing (queue, albums,
artists, genres, playlists, songs, favourites, recent, folders, CUE/ISO
tracks, search) to start playback of anything on the player. The last player is
remembered and reconnected at startup and after a lost link.

### Building

GitHub Actions (`.github/workflows/android.yml`) runs the unit tests and lint and
builds the debug and release APKs on each push touching `apps/`. The APKs are
uploaded as the `hiby-remote-apk` workflow artifact; pushing a `v*` tag also
attaches them to a GitHub release.

Release signing is optional. Add these repository secrets to sign with your own
key (otherwise the release APK is signed with the runner's throwaway debug key,
so successive CI builds cannot be installed over each other):

| secret | content |
|---|---|
| `HIBY_KEYSTORE_BASE64` | the keystore file, base64 encoded (`base64 -w0 release.jks`) |
| `HIBY_KEYSTORE_PASSWORD` | keystore password |
| `HIBY_KEY_ALIAS` | key alias |
| `HIBY_KEY_PASSWORD` | key password |

Locally: `make android` (see [Building](#building)).

## Desktop remote (Windows, Linux)

`apps/desktopApp` is the same remote as a Compose for Desktop window. It
supports **Wi-Fi only** (discovery or manual IP); Bluetooth is not available on
desktop. Besides the on-screen controls, keyboard shortcuts work while
connected: Space (play/pause), Ctrl+Right / Ctrl+Left (next / previous) and
Ctrl+Up / Ctrl+Down (volume). The shortcuts are inactive while a text field has
the focus. Enable HiByLink on the player and put the computer on the same
network.

### Install

Download the packages from the latest GitHub release (or from the artifacts of
the `Desktop app` workflow run). They bundle their own Java runtime, nothing
else to install.

- **Windows**: run `hiby-remote-<version>.msi`. It installs per user (no
  administrator rights) and adds a Start menu entry. The installer is not
  signed, so Windows SmartScreen shows "Windows protected your PC": click
  *More info*, then *Run anyway*. On first launch, Windows Defender Firewall
  asks whether to allow the app on the network: allow it on private networks,
  otherwise player discovery cannot receive beacons (typing the player's IP
  address still works). Alternative without installer: unzip
  `hiby-remote-<version>-windows.zip` and run `hiby-remote\hiby-remote.exe`.
- **Linux** (64-bit, Debian 12+ / Ubuntu 24.04+): `sudo apt install ./hiby-remote_<version>_amd64.deb`,
  then start "hiby-remote" from the application menu (Audio/Video) or run
  `/opt/hiby-remote/bin/hiby-remote`. Other distributions: extract
  `hiby-remote-<version>-linux.tar.gz` and run `hiby-remote/bin/hiby-remote`.
  If discovery finds nothing, check that your firewall allows UDP ports 4001
  and 4003; the manual IP field does not need them.

The app remembers the last player and its window position and size.

### Building

`make linux` and `make windows` (see [Building](#building)). The underlying
Gradle tasks, for development with a local JDK:

```sh
cd apps
./gradlew :desktopApp:run                 # start the app (needs a desktop session)
./gradlew :shared:desktopTest             # unit tests
./gradlew :desktopApp:createDistributable # self-contained app folder
./gradlew :desktopApp:packageDeb          # Linux, needs dpkg and fakeroot
./gradlew :desktopApp:packageMsi          # Windows only (needs WiX, present on the CI runners)
```

Native packages must be built on their target OS, which the `Desktop app`
workflow (`.github/workflows/desktop.yml`) does on `ubuntu-latest` and
`windows-latest`; pushing a `v*` tag attaches the `.msi`, `.deb`, Windows `.zip`
and Linux `.tar.gz` to the same GitHub release as the APKs. Outputs are in
`apps/desktopApp/build/compose/binaries/main/`. The package version is
`VERSION_NAME` when it is numeric `X.Y.Z` (releases), otherwise `1.0.<run number>`.

Project layout: `apps/shared` (all shared code: protocol, link, controller and
Compose UI), `apps/androidApp`, `apps/desktopApp`.

## Web remote with Docker

On the player, enable HiByLink in its settings so that it accepts
connections over WiFi on TCP port 4001.

```sh
docker compose up -d --build
```

Then open `http://<docker-host>:8080`. Pick the player in the discovered list
or type its IP address.

The compose file uses `network_mode: host`, because discovery listens on UDP
multicast `239.0.0.5:4001` and on UDP broadcast port `4003`. If you run the
container without host networking, discovery is unavailable: map the port with
`-p 8080:8080` and type the player's IP address yourself.

Environment variables:

| variable | default | meaning |
|---|---|---|
| `WEB_PORT` | `8080` | HTTP port |
| `WEB_BIND` | `0.0.0.0` | HTTP bind address |
| `HIBY_HOST` | empty | player to connect to at startup (`ip` or `ip:port`) |
| `HIBY_AUTO_RECONNECT` | `1` | reconnect every 5 s after a lost link |
| `HIBY_DISCOVERY` | `1` | listen for player beacons |
| `HIBY_VERBOSE` | `0` | log protocol traffic |

The web remote has no authentication. Only expose it on a trusted network.

Without Docker: `python3 webapp/server.py`.

## Limitations

The protocol was recovered from the Android app (see
[docs/APK_ANALYSIS.md](docs/APK_ANALYSIS.md)) and tested against a simulated
player only. The Bluetooth transports of the Android app follow the app's own
implementation but have not been tested against a real player yet. Bluetooth LE
is very slow by design of the player (about 45 bytes/s from the player), so the
Android app does not request covers over BLE and library browsing there is slow.
