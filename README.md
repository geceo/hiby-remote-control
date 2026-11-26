# HiByLink tools

Reverse-engineered client for the HiByLink remote-control protocol used by the
HiBy Music app and HiBy players. See [PROTOCOL.md](PROTOCOL.md) for the spec.

- `hibylink.py`: stdlib-only Python client and CLI (`./hibylink.py -h`).
- `webapp/`: a small web remote (now playing, transport, volume, seek,
  play mode, library browsing, search, folders, playing albums/playlists/files).

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

The protocol was recovered from the Android app and tested against a simulated
player only. Some points are still unverified:

- the unit of the track duration (the UI assumes seconds below 30000, otherwise ms);
- the meaning of play states 4 and 5.
