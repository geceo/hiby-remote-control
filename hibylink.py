#!/usr/bin/env python3
"""
Minimal HiByLink client (remote control of a HiBy DAP), reverse engineered
from HiBy Music for Android (com.hiby.music, libhibylink.so).

Transports: WiFi (TCP 4001) and Bluetooth Classic RFCOMM (Linux only).
BLE framing is documented in PROTOCOL.md but not implemented here.

UNTESTED against real hardware: the encoding is derived from static analysis.

Examples:
    ./hibylink.py discover
    ./hibylink.py --host 192.168.1.42 status
    ./hibylink.py --host 192.168.1.42 play | pause | stop | next | prev
    ./hibylink.py --host 192.168.1.42 volume 30
    ./hibylink.py --host 192.168.1.42 seek 60000
    ./hibylink.py --host 192.168.1.42 mode 4
    ./hibylink.py --rfcomm AA:BB:CC:DD:EE:FF status

Library browsing / playing specific content:
    ./hibylink.py --host IP albums
    ./hibylink.py --host IP album "Kind of Blue"
    ./hibylink.py --host IP artist "Miles Davis"
    ./hibylink.py --host IP search "blue" --kind albums
    ./hibylink.py --host IP files /storage/sd_0/Music
    ./hibylink.py --host IP play-album "Kind of Blue" [--artist "Miles Davis"] [--index 2]
    ./hibylink.py --host IP play-playlist "My playlist"
    ./hibylink.py --host IP play-file "/storage/sd_0/Music/x.flac"
    ./hibylink.py --host IP play-uuid "2#1@[05]Kind of Blue" "<uuid from album listing>"
"""
import argparse
import json
import socket
import struct
import sys
import threading
import time

# ---------------------------------------------------------------- constants
WIFI_PORT = 4001          # TCP control channel; also UDP multicast discovery port
HEARTBEAT_PORT = 4002     # optional TCP heartbeat ("HLREQ: HEARTBEAT")
BCAST_PORT = 4003         # UDP broadcast discovery (hotspot / AP mode)
MCAST_GROUP = "239.0.0.5"

FRAME_MAGIC = 0x5AA5AA55
FRAME_VERSION = 0x0100
FRAME_HDR = 24
FRAME_MAX_PAYLOAD = 0x7D8  # 2008 bytes; full frame <= 0x7F0

MSG_MAGIC = 0xA5A5
MSG_ENDIAN = 0x6C          # 'l' (little endian, D-Bus style)
MSG_CALL, MSG_RETURN, MSG_SIGNAL = 1, 2, 4

SVC_SDP = 0xC500
SVC_DEV_INFO = 0xC501
SVC_MEDIA_INFO = 0xC502
SVC_MEDIA_CTRL = 0xC503
SVC_MEDIA_LIST = 0xC504
SVC_DEV_CFG = 0xC505
SVC_SCAN_CTRL = 0xC506


def aid(svc, act):
    return (svc << 16) | act


# Action id -> human name (subset relevant for remote control)
ACTIONS = {
    aid(SVC_SDP, 3): "Connect", aid(SVC_SDP, 4): "Disconnect",
    aid(SVC_SDP, 5): "DiscoveryServices", aid(SVC_SDP, 6): "DiscoveryActions",
    aid(SVC_DEV_INFO, 0x1001): "ManufacturerName", aid(SVC_DEV_INFO, 0x1002): "ModelNumber",
    aid(SVC_DEV_INFO, 0x1003): "SerialNumber", aid(SVC_DEV_INFO, 0x1004): "FirmwareRevision",
    aid(SVC_DEV_INFO, 0x1005): "HardwareRevision", aid(SVC_DEV_INFO, 0x1006): "SoftwareRevision",
    aid(SVC_DEV_INFO, 0x1007): "SystemId", aid(SVC_DEV_INFO, 0x1008): "MaxVolume",
    aid(SVC_DEV_INFO, 0x1009): "PowerState",
    aid(SVC_MEDIA_INFO, 0x1101): "PlayState", aid(SVC_MEDIA_INFO, 0x1102): "PlayMode",
    aid(SVC_MEDIA_INFO, 0x1103): "Progress", aid(SVC_MEDIA_INFO, 0x1104): "Cover",
    aid(SVC_MEDIA_INFO, 0x1105): "Volume", aid(SVC_MEDIA_INFO, 0x1106): "CurPlaylistName",
    aid(SVC_MEDIA_INFO, 0x1107): "CurPlayUuid", aid(SVC_MEDIA_INFO, 0x1108): "CurMetaInfo",
    aid(SVC_MEDIA_INFO, 0x1109): "ResetData", aid(SVC_MEDIA_INFO, 0x110A): "PlayErrorCode",
    aid(SVC_MEDIA_CTRL, 0x1201): "SetPlayState", aid(SVC_MEDIA_CTRL, 0x1202): "SetPlayMode",
    aid(SVC_MEDIA_CTRL, 0x1203): "SetVolume", aid(SVC_MEDIA_CTRL, 0x1204): "PlayUuid",
    aid(SVC_MEDIA_CTRL, 0x1205): "PlayPrevious", aid(SVC_MEDIA_CTRL, 0x1206): "PlayNext",
    aid(SVC_MEDIA_CTRL, 0x1207): "SetProgress", aid(SVC_MEDIA_CTRL, 0x1211): "SetCoverSize",
}

ACTIONS.update({
    aid(SVC_MEDIA_CTRL, 0x120A): "PlayTrackUuid",
    aid(SVC_MEDIA_LIST, 0x1301): "GetPlaylistSize", aid(SVC_MEDIA_LIST, 0x1302): "GetPlaylistItems",
    aid(SVC_MEDIA_LIST, 0x1303): "GetPlaylistLists", aid(SVC_MEDIA_LIST, 0x1304): "GetMetaInfo",
    aid(SVC_MEDIA_LIST, 0x1305): "GetTrackInfo",
})

# Field order on the wire (mapped through the JNI constructors in libhibylink.so)
SONG_FIELDS = ("name", "artist", "uuid", "index", "sample_rate", "quality", "sample_bits")  # a(sssuuyy)
LIST_FIELDS = ("name", "uuid", "index", "data")  # a(ssuu); data = song count, or 0=dir / 80,81=file
TRACK_FIELDS = ("name", "uuid", "number")         # a(ssq)
META_FIELDS = ("title", "artist", "album", "year", "genre", "path", "note", "duration",
               "file_size", "bitrate", "sample_rate", "format", "sample_bits", "channels",
               "quality")                         # a(sssssssuuuuqyyy)

# Library URIs: "<depth>#<0=list of lists|1=song list>@[<category>]<name>..."
CAT_CURRENT, CAT_ALLSONGS, CAT_PLAYLIST, CAT_ARTIST = "00", "01", "02", "03"
CAT_GENRE, CAT_ALBUM, CAT_FILES, CAT_SEARCH = "04", "05", "06", "07"
CAT_FAV, CAT_RECENT = "08", "09"


class Uri:
    ALL_SONGS = "1#1@[01]"
    CURRENT_QUEUE = "1#1@[00]"
    FAVORITES = "1#1@[08]"
    RECENT = "1#1@[09]"
    RECENTLY_ADDED = "1#1@[07]"
    ALBUMS = "1#0@[05]"
    ARTISTS = "1#0@[03]"
    GENRES = "1#0@[04]"
    PLAYLISTS = "1#0@[02]"
    FILES_ROOT = "2#0@[06]\\"

    @staticmethod
    def album(name): return "2#1@[05]" + name

    @staticmethod
    def artist_songs(name): return "2#1@[03]" + name

    @staticmethod
    def artist_albums(name): return "3#0@[03]" + name + "@[05]"

    @staticmethod
    def artist_album(artist, album): return "4#1@[03]" + artist + "@[05]" + album

    @staticmethod
    def genre_songs(name): return "2#1@[04]" + name

    @staticmethod
    def genre_albums(name): return "3#0@[04]" + name + "@[05]"

    @staticmethod
    def genre_album(genre, album): return "4#1@[04]" + genre + "@[05]" + album

    @staticmethod
    def playlist(name): return "2#1@[02]" + name

    @staticmethod
    def folder(path): return "2#0@[06]" + path

    @staticmethod
    def search(query, kind):
        """kind: songs | albums | artists | genres"""
        if kind == "songs":
            return "3#1@[07]" + query + "@[01]"
        return "3#0@[07]" + query + {"albums": "@[05]", "artists": "@[03]", "genres": "@[04]"}[kind]


def is_list_of_lists(uri):
    return len(uri) > 2 and uri[2] == "0"


PLAY_STATES = {1: "playing", 2: "paused", 3: "stopped"}
PLAY_MODES = {1: "order", 2: "repeat-all", 3: "repeat-one", 4: "shuffle"}


# ---------------------------------------------------------------- CRC
def crc16(data):
    """CRC-16/MODBUS (reflected poly 0xA001, init 0xFFFF, no final xor)."""
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


# ---------------------------------------------------------------- marshalling
_FIXED = {"y": "<B", "b": "<B", "n": "<h", "q": "<H", "i": "<i", "u": "<I",
          "x": "<q", "t": "<Q", "d": "<d"}


def encode_args(args):
    """args: list of (sig, value). Supports basic types, 's' and 'a<basic>'."""
    sig, body = "", bytearray()
    for t, v in args:
        if t in _FIXED:
            body += struct.pack(_FIXED[t], v)
        elif t == "s":
            raw = v.encode("utf-8") + b"\0"
            body += struct.pack("<I", len(raw)) + raw
        elif t.startswith("a") and t[1] in _FIXED:
            body += struct.pack("<I", len(v))
            if t[1] in "yb" and isinstance(v, (bytes, bytearray)):
                body += v
            else:
                for e in v:
                    body += struct.pack(_FIXED[t[1]], e)
        else:
            raise ValueError("unsupported type " + t)
        sig += t
    return sig, bytes(body)


def _split_sig(sig):
    """Split a signature into complete types: 'ua(ssu)y' -> ['u','a(ssu)','y']."""
    out, i = [], 0
    while i < len(sig):
        if sig[i] == "a":
            if sig[i + 1] == "(":
                j = sig.index(")", i)
                out.append(sig[i:j + 1])
                i = j + 1
            else:
                out.append(sig[i:i + 2])
                i += 2
        else:
            out.append(sig[i])
            i += 1
    return out


def decode_args(sig, body):
    vals, off = [], 0

    def one(t):
        nonlocal off
        if t in _FIXED:
            fmt = _FIXED[t]
            (v,) = struct.unpack_from(fmt, body, off)
            off += struct.calcsize(fmt)
            return v
        if t == "s":
            (n,) = struct.unpack_from("<I", body, off)
            off += 4
            v = body[off:off + n].rstrip(b"\0").decode("utf-8", "replace")
            off += n
            return v
        if t.startswith("a("):
            (n,) = struct.unpack_from("<I", body, off)
            off += 4
            fields = _split_sig(t[2:-1])
            return [[one(f) for f in fields] for _ in range(n)]
        if t.startswith("a"):
            (n,) = struct.unpack_from("<I", body, off)
            off += 4
            if t[1] in "yb":
                v = body[off:off + n]
                off += n
                return bytes(v)
            return [one(t[1]) for _ in range(n)]
        raise ValueError("unsupported type " + t)

    try:
        for t in _split_sig(sig):
            vals.append(one(t))
    except Exception as e:  # keep partial result, show the rest raw
        vals.append({"decode_error": str(e), "rest": body[off:].hex()})
    return vals


# ---------------------------------------------------------------- messages
class Message:
    def __init__(self, mtype, msg_id, prio, sig, body):
        self.type, self.id, self.prio, self.sig, self.body = mtype, msg_id, prio, sig, body

    def pack_header(self):
        sig = self.sig.encode() + b"\0"
        return struct.pack("<HBBBBHII", MSG_MAGIC, MSG_ENDIAN, self.type, self.prio, 1,
                           self.id, len(self.body), len(sig)) + sig

    @classmethod
    def parse(cls, header, body):
        magic, endian, mtype, prio, ver, mid, blen, slen = struct.unpack_from("<HBBBBHII", header)
        if magic != MSG_MAGIC:
            raise ValueError("bad message magic")
        sig = header[16:16 + slen].rstrip(b"\0").decode()
        return cls(mtype, mid, prio, sig, body[:blen])

    def args(self):
        return decode_args(self.sig, self.body)


def frames_for(msg, stream_seq):
    """Split a message into link-layer frames (header alone in frame 0)."""
    chunks = [msg.pack_header()]
    chunks += [msg.body[i:i + FRAME_MAX_PAYLOAD] for i in range(0, len(msg.body), FRAME_MAX_PAYLOAD)]
    remaining = sum(len(c) + FRAME_HDR for c in chunks)
    out = []
    for idx, c in enumerate(chunks):
        flen = len(c) + FRAME_HDR
        tail = struct.pack("<HIIII", FRAME_VERSION, flen, idx, stream_seq, remaining) + c
        out.append(struct.pack("<IH", FRAME_MAGIC, crc16(tail)) + tail)
        remaining -= flen
    return out


class FrameReader:
    """Reassembles frames from a byte stream into Messages."""

    def __init__(self):
        self.buf = bytearray()
        self.partial = {}  # stream_seq -> list of payloads

    def feed(self, data):
        self.buf += data
        msgs = []
        while True:
            i = self.buf.find(struct.pack("<I", FRAME_MAGIC))
            if i < 0:
                del self.buf[:-3]
                return msgs
            del self.buf[:i]
            if len(self.buf) < FRAME_HDR:
                return msgs
            _, crc, ver, flen, idx, seq, remaining = struct.unpack_from("<IHHIIII", self.buf)
            if ver != FRAME_VERSION or flen < FRAME_HDR or flen > 0x800:
                del self.buf[:4]
                continue
            if len(self.buf) < flen:
                return msgs
            frame = bytes(self.buf[:flen])
            del self.buf[:flen]
            if crc16(frame[6:]) != crc:
                print("! CRC mismatch, frame dropped", file=sys.stderr)
                continue
            parts = self.partial.setdefault(seq, [])
            parts.append(frame[FRAME_HDR:])
            if flen == remaining:  # last frame of this message
                del self.partial[seq]
                try:
                    msgs.append(Message.parse(parts[0], b"".join(parts[1:])))
                except Exception as e:
                    print("! bad message:", e, file=sys.stderr)


# ---------------------------------------------------------------- client
class HiByLink:
    def __init__(self, sock, verbose=False):
        self.sock = sock
        self.verbose = verbose
        self.reader = FrameReader()
        self.msg_id = 11       # the native lib starts its counters at 11
        self.stream_seq = 11
        self.pending = {}      # msg id -> (action id, Event, [reply])
        self.lock = threading.Lock()
        self.on_signal = None
        self.on_close = None   # called once when the device closes the link
        self.closed = False
        threading.Thread(target=self._rx, daemon=True).start()

    def _rx(self):
        while True:
            try:
                data = self.sock.recv(65536)
            except OSError:
                break
            if not data:
                print("! connection closed by device", file=sys.stderr)
                break
            for m in self.reader.feed(data):
                self._dispatch(m)
        self.closed = True
        for _, ev, _slot in list(self.pending.values()):
            ev.set()  # wake up callers; they get an empty slot
        if self.on_close:
            self.on_close()

    def _dispatch(self, m):
        args = m.args()
        if self.verbose:
            print(f"<< type={m.type} id={m.id} sig={m.sig!r} args={args}", file=sys.stderr)
        if m.type == MSG_RETURN and m.id in self.pending:
            _, ev, slot = self.pending.pop(m.id)
            slot.append(args)
            ev.set()
        elif m.type == MSG_SIGNAL and args:
            name = ACTIONS.get(args[0], hex(args[0]))
            if self.on_signal:
                self.on_signal(name, args[1:])

    def call(self, action_id, args=(), prio=1, wait=3.0):
        sig, body = encode_args([("u", action_id)] + list(args))
        with self.lock:
            mid, seq = self.msg_id, self.stream_seq
            self.msg_id = (self.msg_id + 1) & 0xFFFF or 11
            self.stream_seq += 1
            ev, slot = threading.Event(), []
            self.pending[mid] = (action_id, ev, slot)
            msg = Message(MSG_CALL, mid, prio, sig, body)
            if self.verbose:
                print(f">> {ACTIONS.get(action_id, hex(action_id))} id={mid} sig={sig!r}", file=sys.stderr)
            for f in frames_for(msg, seq):
                self.sock.sendall(f)
        if not wait:
            return None
        if not ev.wait(wait):
            self.pending.pop(mid, None)
            raise TimeoutError(f"no reply to {ACTIONS.get(action_id, hex(action_id))}")
        if not slot:
            raise ConnectionError("link closed")
        return slot[0]  # [error_code, outputs...]

    # --- high level
    def connect(self, user="Guest", password=""):
        r = self.call(aid(SVC_SDP, 3), [("s", user), ("s", password), ("i", 0)])
        if r[0] < 0:
            raise RuntimeError(f"Connect refused, error {r[0]}")
        return r

    def discover_services(self):
        return self.call(aid(SVC_SDP, 5))

    def set_play_state(self, s):      # 1=play 2=pause 3=stop
        return self.call(aid(SVC_MEDIA_CTRL, 0x1201), [("y", s)])

    def set_play_mode(self, m):       # 1=order 2=all 3=one 4=shuffle
        return self.call(aid(SVC_MEDIA_CTRL, 0x1202), [("y", m)])

    def set_volume(self, v):
        return self.call(aid(SVC_MEDIA_CTRL, 0x1203), [("y", v)])

    def next(self):
        return self.call(aid(SVC_MEDIA_CTRL, 0x1206))

    def previous(self):
        return self.call(aid(SVC_MEDIA_CTRL, 0x1205))

    def seek(self, pos):
        return self.call(aid(SVC_MEDIA_CTRL, 0x1207), [("u", pos)])

    def request_status(self):
        """GET_* calls only ack; values come back as signals."""
        for a in (0x1101, 0x1102, 0x1103, 0x1105, 0x1106, 0x1107, 0x1108):
            self.call(aid(SVC_MEDIA_INFO, a), wait=0)
        for a in (0x1001, 0x1002, 0x1004, 0x1008):
            r = self.call(aid(SVC_DEV_INFO, a))
            print(f"{ACTIONS[aid(SVC_DEV_INFO, a)]}: {r[1:] if len(r) > 1 else r}")

    # --- library browsing (service 0xC504) and playback by uuid
    def list_size(self, uri):
        """GetPlaylistSize: the size is returned in the error-code slot."""
        return self.call(aid(SVC_MEDIA_LIST, 0x1301), [("s", uri)], wait=15)[0]

    def list_page(self, uri, start, end):
        """One page [start..end] (end inclusive). Uses GetPlaylistLists for
        '#0' URIs (list of lists) and GetPlaylistItems for '#1' (songs)."""
        act = 0x1303 if is_list_of_lists(uri) else 0x1302
        r = self.call(aid(SVC_MEDIA_LIST, act), [("s", uri), ("u", start), ("u", end)], wait=15)
        if r[0] < 0 or len(r) < 2:
            return []
        fields = LIST_FIELDS if act == 0x1303 else SONG_FIELDS
        return [dict(zip(fields, row)) for row in r[1]]

    def list_all(self, uri, page=30, limit=None):
        size = self.list_size(uri)
        if size <= 0:
            return []
        if limit:
            size = min(size, limit)
        out = []
        for start in range(0, size, page):
            out += self.list_page(uri, start, min(start + page, size) - 1)
        return out

    def meta_info(self, uri, uuid):
        r = self.call(aid(SVC_MEDIA_LIST, 0x1304), [("s", uri), ("s", uuid)], wait=15)
        return [dict(zip(META_FIELDS, row)) for row in r[1]] if len(r) > 1 else r

    def track_info(self, dir_uri, file_path):
        """Sub-tracks of a CUE/ISO file in the file browser."""
        r = self.call(aid(SVC_MEDIA_LIST, 0x1305), [("s", dir_uri), ("s", file_path)], wait=15)
        return [dict(zip(TRACK_FIELDS, row)) for row in r[1]] if len(r) > 1 else r

    def play_uuid(self, uri, uuid):
        """Play `uuid` with list `uri` as the play queue context.
        For the file browser: uri = '2#0@[06]<dir>', uuid = full file path."""
        return self.call(aid(SVC_MEDIA_CTRL, 0x1204), [("s", uri), ("s", uuid)], wait=15)

    def play_track_uuid(self, dir_uri, file_path, track_uuid):
        return self.call(aid(SVC_MEDIA_CTRL, 0x120A),
                         [("s", dir_uri), ("s", file_path), ("s", track_uuid)], wait=15)

    def play_list(self, uri, index=0):
        """Play a whole song list (album, artist, playlist...) from `index`."""
        items = self.list_page(uri, index, index)
        if not items:
            raise RuntimeError(f"empty or unknown list: {uri}")
        return self.play_uuid(uri, items[0]["uuid"])

    def disconnect(self):
        try:
            self.call(aid(SVC_SDP, 4), wait=1.0)
        except Exception:
            pass
        self.sock.close()


# ---------------------------------------------------------------- discovery
def parse_beacon(data, src):
    if len(data) >= 0x14E and data[:4] == b"HLBM":
        (crc,) = struct.unpack_from("<H", data, 4)
        ok = crc16(data[6:0x14E]) == crc
        addr = data[14:78].split(b"\0")[0].decode(errors="replace")
        name = data[78:334].split(b"\0")[0].decode(errors="replace")
        return {"source": src, "address": addr, "name": name, "crc_ok": ok}
    try:
        j = json.loads(data.decode())
        return {"source": src, **j}
    except Exception:
        return {"source": src, "raw": data.hex()}


def discover(timeout):
    found = {}

    def mcast():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", WIFI_PORT))
        mreq = struct.pack("4s4s", socket.inet_aton(MCAST_GROUP), socket.inet_aton("0.0.0.0"))
        s.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        listen(s, "multicast")

    def bcast():
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("", BCAST_PORT))
        listen(s, "broadcast")

    def listen(s, kind):
        s.settimeout(0.5)
        end = time.time() + timeout
        while time.time() < end:
            try:
                data, (ip, _) = s.recvfrom(2048)
            except socket.timeout:
                continue
            info = parse_beacon(data, f"{kind} {ip}")
            key = json.dumps(info, sort_keys=True)
            if key not in found:
                found[key] = info
                print(json.dumps(info, ensure_ascii=False))
        s.close()

    ts = [threading.Thread(target=mcast), threading.Thread(target=bcast)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    if not found:
        print("no device found (is HiByLink server enabled on the DAP?)", file=sys.stderr)


# ---------------------------------------------------------------- CLI
LIST_SHORTCUTS = {
    "albums": Uri.ALBUMS, "artists": Uri.ARTISTS, "genres": Uri.GENRES,
    "playlists": Uri.PLAYLISTS, "songs": Uri.ALL_SONGS, "favorites": Uri.FAVORITES,
    "recent": Uri.RECENT, "queue": Uri.CURRENT_QUEUE,
}


def dump(rows):
    for r in rows:
        print(json.dumps(r, ensure_ascii=False))


def open_socket(a):
    if a.host:
        host, _, port = a.host.partition(":")
        return socket.create_connection((host, int(port or WIFI_PORT)), timeout=3)
    if a.rfcomm:
        s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        s.connect((a.rfcomm, a.channel))
        return s
    sys.exit("need --host or --rfcomm")


def main():
    p = argparse.ArgumentParser(description="HiByLink remote control client")
    p.add_argument("--host", help="device IP[:port] (WiFi, default port 4001)")
    p.add_argument("--rfcomm", help="device Bluetooth MAC (classic RFCOMM)")
    p.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("discover").add_argument("--timeout", type=float, default=5)
    for c in ("status", "play", "pause", "stop", "next", "prev", "monitor"):
        sub.add_parser(c)
    sub.add_parser("volume").add_argument("level", type=int)
    sub.add_parser("seek").add_argument("position", type=int)
    sub.add_parser("mode").add_argument("mode", type=int, choices=[1, 2, 3, 4])

    # library browsing
    for c in ("albums", "artists", "genres", "playlists", "songs", "favorites", "recent", "queue"):
        sub.add_parser(c).add_argument("--limit", type=int)
    sp = sub.add_parser("ls", help="list any library URI (see PROTOCOL.md)")
    sp.add_argument("uri")
    sp.add_argument("--start", type=int)
    sp.add_argument("--end", type=int, help="inclusive")
    sp.add_argument("--limit", type=int)
    sp = sub.add_parser("album", help="songs of an album")
    sp.add_argument("name")
    sp.add_argument("--artist")
    sub.add_parser("artist", help="albums of an artist").add_argument("name")
    sp = sub.add_parser("search")
    sp.add_argument("query")
    sp.add_argument("--kind", default="songs", choices=["songs", "albums", "artists", "genres"])
    sub.add_parser("files", help="browse storage").add_argument("path", nargs="?")
    sp = sub.add_parser("meta", help="detailed metadata of one song")
    sp.add_argument("uri")
    sp.add_argument("uuid")
    sp = sub.add_parser("tracks", help="sub-tracks of a CUE/ISO file")
    sp.add_argument("dir")
    sp.add_argument("file")

    # playback of specific content
    sp = sub.add_parser("play-uuid", help="play song <uuid> within list <uri>")
    sp.add_argument("uri")
    sp.add_argument("uuid")
    sp = sub.add_parser("play-list", help="play a song list from an index")
    sp.add_argument("uri")
    sp.add_argument("--index", type=int, default=0)
    sp = sub.add_parser("play-album")
    sp.add_argument("name")
    sp.add_argument("--artist")
    sp.add_argument("--index", type=int, default=0)
    sub.add_parser("play-playlist").add_argument("name")
    sub.add_parser("play-file", help="play a file by absolute path").add_argument("path")
    sp = sub.add_parser("play-track", help="play a CUE/ISO sub-track")
    sp.add_argument("dir")
    sp.add_argument("file")
    sp.add_argument("track_uuid")
    a = p.parse_args()

    if a.cmd == "discover":
        return discover(a.timeout)

    sock = open_socket(a)
    sock.settimeout(None)
    hl = HiByLink(sock, verbose=a.verbose)

    def show(name, vals):
        v = vals[0] if len(vals) == 1 else vals
        if name == "PlayState":
            v = PLAY_STATES.get(v, v)
        elif name == "PlayMode":
            v = PLAY_MODES.get(v, v)
        elif name == "Cover":
            v = f"<{len(v)} bytes image>"
        print(f"[signal] {name}: {v}")

    hl.on_signal = show
    hl.connect()
    hl.discover_services()

    cmd = a.cmd
    if cmd == "play":
        hl.set_play_state(1)
    elif cmd == "pause":
        hl.set_play_state(2)
    elif cmd == "stop":
        hl.set_play_state(3)
    elif cmd == "next":
        hl.next()
    elif cmd == "prev":
        hl.previous()
    elif cmd == "volume":
        hl.set_volume(a.level)
    elif cmd == "seek":
        hl.seek(a.position)
    elif cmd == "mode":
        hl.set_play_mode(a.mode)
    elif cmd == "status":
        hl.request_status()
        time.sleep(2)
    elif cmd in LIST_SHORTCUTS:
        dump(hl.list_all(LIST_SHORTCUTS[cmd], limit=a.limit))
    elif cmd == "ls":
        if a.start is not None:
            dump(hl.list_page(a.uri, a.start, a.end if a.end is not None else a.start + 29))
        else:
            dump(hl.list_all(a.uri, limit=a.limit))
    elif cmd == "album":
        dump(hl.list_all(Uri.artist_album(a.artist, a.name) if a.artist else Uri.album(a.name)))
    elif cmd == "artist":
        dump(hl.list_all(Uri.artist_albums(a.name)))
    elif cmd == "search":
        dump(hl.list_all(Uri.search(a.query, a.kind)))
    elif cmd == "files":
        dump(hl.list_all(Uri.folder(a.path) if a.path else Uri.FILES_ROOT))
    elif cmd == "meta":
        dump(hl.meta_info(a.uri, a.uuid))
    elif cmd == "tracks":
        dump(hl.track_info(Uri.folder(a.dir), a.file))
    elif cmd == "play-uuid":
        print(hl.play_uuid(a.uri, a.uuid))
    elif cmd == "play-list":
        print(hl.play_list(a.uri, a.index))
    elif cmd == "play-album":
        uri = Uri.artist_album(a.artist, a.name) if a.artist else Uri.album(a.name)
        print(hl.play_list(uri, a.index))
    elif cmd == "play-playlist":
        print(hl.play_list(Uri.playlist(a.name)))
    elif cmd == "play-file":
        parent = a.path.rsplit("/", 1)[0]
        print(hl.play_uuid(Uri.folder(parent), a.path))
    elif cmd == "play-track":
        print(hl.play_track_uuid(Uri.folder(a.dir), a.file, a.track_uuid))
    elif cmd == "monitor":
        hl.request_status()
        print("listening for signals, Ctrl+C to quit", file=sys.stderr)
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            pass
    hl.disconnect()


if __name__ == "__main__":
    main()
