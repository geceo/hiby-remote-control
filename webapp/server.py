#!/usr/bin/env python3
"""HiByLink web remote: a small HTTP server bridging a browser UI to a HiBy
player over the HiByLink protocol (TCP 4001).

Stdlib only. Endpoints (JSON unless noted):
  GET  /                      web UI
  GET  /api/state             current player state
  GET  /api/events            server-sent events, one "state" event per change
  GET  /api/devices           players seen by WiFi discovery
  GET  /api/cover             current cover (image/jpeg)
  GET  /api/list?uri=&start=&end=   one page of a library list (+ size)
  GET  /api/meta?uri=&uuid=   full metadata of a song
  GET  /api/tracks?dir=&file= sub-tracks of a CUE/ISO file
  POST /api/connect {host}    connect to a player ("ip" or "ip:port")
  POST /api/disconnect
  POST /api/control {cmd, value}   play|pause|toggle|stop|next|prev|volume|seek|mode
  POST /api/play {uri, uuid} | {uri, index} | {dir, file, track}
"""
import json
import os
import queue
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import hibylink as hl  # noqa: E402

STATIC = Path(__file__).resolve().parent / "static"
WEB_PORT = int(os.environ.get("WEB_PORT", "8080"))
WEB_BIND = os.environ.get("WEB_BIND", "0.0.0.0")
DEFAULT_HOST = os.environ.get("HIBY_HOST", "").strip()
AUTO_RECONNECT = os.environ.get("HIBY_AUTO_RECONNECT", "1") != "0"
DISCOVERY = os.environ.get("HIBY_DISCOVERY", "1") != "0"
VERBOSE = os.environ.get("HIBY_VERBOSE", "0") == "1"

MI = hl.SVC_MEDIA_INFO
STATUS_ACTIONS = (0x1101, 0x1102, 0x1103, 0x1105, 0x1106, 0x1107, 0x1108)
DEVINFO = {"manufacturer": 0x1001, "model": 0x1002, "firmware": 0x1004, "max_volume": 0x1008}


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


# ---------------------------------------------------------------- event hub
class Hub:
    """Fan-out of state snapshots to SSE clients."""

    def __init__(self):
        self.clients = set()
        self.lock = threading.Lock()

    def subscribe(self):
        q = queue.Queue(maxsize=50)
        with self.lock:
            self.clients.add(q)
        return q

    def unsubscribe(self, q):
        with self.lock:
            self.clients.discard(q)

    def publish(self, payload):
        data = json.dumps(payload, ensure_ascii=False)
        with self.lock:
            for q in list(self.clients):
                try:
                    q.put_nowait(data)
                except queue.Full:  # slow client: drop the oldest snapshot
                    try:
                        q.get_nowait()
                        q.put_nowait(data)
                    except queue.Empty:
                        pass


# ---------------------------------------------------------------- player session
class Player:
    def __init__(self, hub):
        self.hub = hub
        self.lock = threading.RLock()
        self.link = None
        self.host = DEFAULT_HOST or None
        self.want_connected = bool(DEFAULT_HOST)
        self.cover = None
        self.state = self._blank()
        threading.Thread(target=self._worker, daemon=True).start()

    @staticmethod
    def _blank():
        return {"connected": False, "connecting": False, "host": None, "error": None,
                "device": {}, "play_state": None, "play_mode": None, "volume": None,
                "progress": None, "progress_at": None, "playlist": None, "uuid": None,
                "meta": None, "cover_version": 0, "play_error": None}

    # --- state helpers
    def snapshot(self):
        with self.lock:
            s = dict(self.state)
        s["host"] = self.host
        return s

    def _update(self, **kw):
        with self.lock:
            self.state.update(kw)
        self.hub.publish(self.snapshot())

    # --- connection
    def connect(self, host):
        with self.lock:
            self._close_link()
            self.host = host
            self.want_connected = True
        return self._open()

    def disconnect(self):
        with self.lock:
            self.want_connected = False
            self._close_link()
        self._update(connected=False, connecting=False, error=None)

    def _close_link(self):
        link, self.link = self.link, None
        if link:
            link.on_close = None
            threading.Thread(target=link.disconnect, daemon=True).start()

    def _open(self):
        host = self.host
        if not host:
            return False
        self._update(connecting=True, error=None)
        name, _, port = host.partition(":")
        try:
            sock = socket.create_connection((name, int(port or hl.WIFI_PORT)), timeout=4)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            sock.settimeout(None)
            link = hl.HiByLink(sock, verbose=VERBOSE)
            link.on_signal = self._on_signal
            link.on_close = lambda: self._on_close(link)
            link.connect()
            link.discover_services()
        except Exception as e:
            log("connect to", host, "failed:", e)
            self._update(connected=False, connecting=False, error=f"{host}: {e}")
            return False
        with self.lock:
            if self.host != host:  # superseded by another connect
                link.disconnect()
                return False
            self.link = link
            self.cover = None
            self.state = self._blank()
        log("connected to", host)
        self._update(connected=True, connecting=False)
        threading.Thread(target=self._initial_sync, args=(link,), daemon=True).start()
        return True

    def _initial_sync(self, link):
        dev = {}
        for key, act in DEVINFO.items():
            try:
                r = link.call(hl.aid(hl.SVC_DEV_INFO, act))
                if r and r[0] >= 0 and len(r) > 1:
                    dev[key] = r[1]
            except Exception as e:
                log("device info", key, "failed:", e)
        self._update(device=dev)
        try:
            link.call(hl.aid(hl.SVC_MEDIA_CTRL, 0x1211), [("q", 720), ("q", 720)], wait=0)
        except OSError:
            pass
        self.request_status(link, cover=True)

    def request_status(self, link=None, cover=False):
        link = link or self.link
        if not link:
            return
        acts = STATUS_ACTIONS + ((0x1104,) if cover else ())
        try:
            for a in acts:
                link.call(hl.aid(MI, a), wait=0)
        except OSError as e:
            log("status request failed:", e)

    def _on_close(self, link):
        with self.lock:
            if self.link is not link:
                return
            self.link = None
        log("link to", self.host, "closed")
        self._update(connected=False, connecting=False, error="connection closed by player")

    def _on_signal(self, name, vals):
        v = vals[0] if vals else None
        if name == "PlayState":
            self._update(play_state=v)
        elif name == "PlayMode":
            self._update(play_mode=v)
        elif name == "Volume":
            self._update(volume=v)
        elif name == "Progress":
            self._update(progress=v, progress_at=time.time())
        elif name == "CurPlaylistName":
            self._update(playlist=v)
        elif name == "CurPlayUuid":
            changed = v != self.state.get("uuid")
            self._update(uuid=v)
            if changed and self.link:  # new track: refresh meta and cover
                for a in (0x1108, 0x1104, 0x1103):
                    try:
                        self.link.call(hl.aid(MI, a), wait=0)
                    except OSError:
                        pass
        elif name == "CurMetaInfo":
            meta = dict(zip(hl.META_FIELDS, v[0])) if isinstance(v, list) and v else None
            self._update(meta=meta)
        elif name == "Cover":
            with self.lock:
                self.cover = v if isinstance(v, (bytes, bytearray)) and v else None
                ver = self.state["cover_version"] + 1
            self._update(cover_version=ver)
        elif name == "PlayErrorCode":
            self._update(play_error=v)
        elif name == "ResetData":
            self.request_status(cover=True)
        elif VERBOSE:
            log("signal", name, vals)

    def _worker(self):
        """Reconnect when needed; poll progress while playing."""
        last_try = last_full = 0
        while True:
            time.sleep(1)
            now = time.time()
            link = self.link
            if link is None:
                if self.want_connected and AUTO_RECONNECT and self.host and now - last_try > 5 \
                        and not self.state.get("connecting"):
                    last_try = now
                    self._open()
                continue
            try:
                if self.state.get("play_state") == 1:
                    link.call(hl.aid(MI, 0x1103), wait=0)
                if now - last_full > 30:
                    last_full = now
                    self.request_status(link)
            except OSError as e:
                log("poll failed:", e)

    # --- commands
    def require(self):
        link = self.link
        if not link:
            raise RuntimeError("not connected to a player")
        return link

    def control(self, cmd, value=None):
        link = self.require()
        st = self.state.get("play_state")
        if cmd == "toggle":
            cmd = "pause" if st == 1 else "play"
        if cmd == "play":
            r = link.set_play_state(1)
        elif cmd == "pause":
            r = link.set_play_state(2)
        elif cmd == "stop":
            r = link.set_play_state(3)
        elif cmd == "next":
            r = link.next()
        elif cmd == "prev":
            r = link.previous()
        elif cmd == "volume":
            r = link.set_volume(int(value))
            self._update(volume=int(value))
        elif cmd == "seek":
            r = link.seek(int(value))
            self._update(progress=int(value), progress_at=time.time())
        elif cmd == "mode":
            r = link.set_play_mode(int(value))
        elif cmd == "refresh":
            self.request_status(cover=True)
            return {"ok": True}
        else:
            raise ValueError(f"unknown command {cmd!r}")
        # Some players do not push signals after a command: ask explicitly.
        for a in (0x1101, 0x1102, 0x1105):
            link.call(hl.aid(MI, a), wait=0)
        return {"ok": r[0] >= 0, "code": r[0]}


# ---------------------------------------------------------------- discovery
class Discovery:
    """Listens permanently to HiByLink beacons (multicast + broadcast).
    Requires host networking when run in Docker."""

    TTL = 30

    def __init__(self):
        self.devices = {}
        self.lock = threading.Lock()
        self.errors = []
        if DISCOVERY:
            threading.Thread(target=self._mcast, daemon=True).start()
            threading.Thread(target=self._bcast, daemon=True).start()

    def _mcast(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", hl.WIFI_PORT))
            mreq = struct.pack("4s4s", socket.inet_aton(hl.MCAST_GROUP), socket.inet_aton("0.0.0.0"))
            s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        except OSError as e:
            self.errors.append(f"multicast: {e}")
            log("multicast discovery disabled:", e)
            return
        self._listen(s)

    def _bcast(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", hl.BCAST_PORT))
        except OSError as e:
            self.errors.append(f"broadcast: {e}")
            log("broadcast discovery disabled:", e)
            return
        self._listen(s)

    def _listen(self, s):
        while True:
            try:
                data, (ip, _) = s.recvfrom(2048)
            except OSError:
                continue
            info = hl.parse_beacon(data, ip)
            name = info.get("name") or info.get("deviceName") or ip
            with self.lock:
                self.devices[ip] = {"host": ip, "name": name, "address": info.get("address"),
                                    "seen": time.time()}

    def list(self):
        now = time.time()
        with self.lock:
            for k in [k for k, d in self.devices.items() if now - d["seen"] > self.TTL]:
                del self.devices[k]
            return sorted(self.devices.values(), key=lambda d: d["name"])


# ---------------------------------------------------------------- HTTP
HUB = Hub()
PLAYER = Player(HUB)
DISCO = Discovery()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "HiByLinkWeb/1.0"

    def log_message(self, fmt, *args):
        if VERBOSE:
            log("http", fmt % args)

    # --- helpers
    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        if not isinstance(body, (bytes, bytearray)):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}") if n else {}

    def _route(self, routes):
        u = urlparse(self.path)
        q = {k: v[-1] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
        fn = routes.get(u.path)
        if not fn:
            return self._send(404, {"error": "not found"})
        try:
            fn(q)
        except (RuntimeError, ValueError, KeyError, TimeoutError, ConnectionError, OSError) as e:
            self._send(400 if isinstance(e, (ValueError, KeyError)) else 502, {"error": str(e)})

    # --- GET
    def do_GET(self):
        self._route({
            "/": lambda q: self._static("index.html"),
            "/index.html": lambda q: self._static("index.html"),
            "/api/state": lambda q: self._send(200, PLAYER.snapshot()),
            "/api/devices": lambda q: self._send(200, {"devices": DISCO.list(),
                                                       "errors": DISCO.errors}),
            "/api/events": self._events,
            "/api/cover": self._cover,
            "/api/list": self._list,
            "/api/meta": self._meta,
            "/api/tracks": self._tracks,
        })

    def _static(self, name):
        self._send(200, (STATIC / name).read_bytes(), "text/html; charset=utf-8")

    def _cover(self, q):
        img = PLAYER.cover
        if not img:
            return self._send(404, {"error": "no cover"})
        ctype = "image/png" if img[:4] == b"\x89PNG" else "image/jpeg"
        self._send(200, bytes(img), ctype)

    def _events(self, q):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self.close_connection = True
        sub = HUB.subscribe()
        try:
            self.wfile.write(f"event: state\ndata: {json.dumps(PLAYER.snapshot())}\n\n".encode())
            self.wfile.flush()
            while True:
                try:
                    data = sub.get(timeout=15)
                    self.wfile.write(f"event: state\ndata: {data}\n\n".encode())
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            HUB.unsubscribe(sub)

    def _list(self, q):
        link = PLAYER.require()
        uri = q["uri"]
        start = int(q.get("start") or 0)
        count = min(int(q.get("count") or 60), 200)
        size = int(q["size"]) if q.get("size") else link.list_size(uri)
        items = []
        if size > 0 and start < size:
            end = min(start + count, size) - 1
            for s in range(start, end + 1, 30):  # the player expects small pages
                items += link.list_page(uri, s, min(s + 29, end))
        self._send(200, {"uri": uri, "size": max(size, 0), "start": start,
                         "kind": "lists" if hl.is_list_of_lists(uri) else "songs",
                         "items": items})

    def _meta(self, q):
        self._send(200, {"meta": PLAYER.require().meta_info(q["uri"], q["uuid"])})

    def _tracks(self, q):
        link = PLAYER.require()
        self._send(200, {"tracks": link.track_info(hl.Uri.folder(q["dir"]), q["file"])})

    # --- POST
    def do_POST(self):
        self._route({
            "/api/connect": self._connect,
            "/api/disconnect": lambda q: (PLAYER.disconnect(), self._send(200, {"ok": True})),
            "/api/control": self._control,
            "/api/play": self._play,
        })

    def _connect(self, q):
        host = (self._json_body().get("host") or "").strip()
        if not host:
            raise ValueError("host is required")
        ok = PLAYER.connect(host)
        self._send(200 if ok else 502, PLAYER.snapshot())

    def _control(self, q):
        b = self._json_body()
        self._send(200, PLAYER.control(b["cmd"], b.get("value")))

    def _play(self, q):
        b = self._json_body()
        link = PLAYER.require()
        if b.get("track"):
            r = link.play_track_uuid(hl.Uri.folder(b["dir"]), b["file"], b["track"])
        elif b.get("uuid"):
            r = link.play_uuid(b["uri"], b["uuid"])
        else:
            r = link.play_list(b["uri"], int(b.get("index") or 0))
        PLAYER.request_status(cover=True)
        self._send(200, {"ok": r[0] >= 0, "code": r[0]})


def main():
    srv = ThreadingHTTPServer((WEB_BIND, WEB_PORT), Handler)
    srv.daemon_threads = True
    log(f"HiByLink web remote on http://{WEB_BIND}:{WEB_PORT}"
        + (f" (player {DEFAULT_HOST})" if DEFAULT_HOST else ""))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
