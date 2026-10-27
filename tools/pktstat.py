#!/usr/bin/env python3
"""
pktstat - packet-id observability for the sfus Minecraft bot.

The bot talks raw protocol 777 and every packet id in it is a guess that was
either verified against a live server or never confirmed at all. This tool
closes that loop:

  analyze  read the BOT_DEBUG logs the bot already writes and report which
           inbound ids actually appear, how big they are, and which declared
           ids never show up (i.e. are probably wrong)
  probe    connect to a server yourself, log every inbound packet, and write a
           log in the same format `analyze` understands
  ids      dump the id table as declared in the Java sources

The declared table is parsed out of src/mcbot/net/PacketIds.java and the
`case 0xNN:` labels in src/mcbot/modules/*.java on every run. Nothing is
hardcoded here, so this file cannot drift from the bot.

Stdlib only. Python 3.9+.
"""

import argparse
import hashlib
import json
import os
import re
import socket
import struct
import sys
import time
import zlib
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
PACKET_IDS_JAVA = os.path.join(SRC, "mcbot", "net", "PacketIds.java")
MODULES_DIR = os.path.join(SRC, "mcbot", "modules")

# [pkt] in state=PLAY id=0x67 len=9      (written by MinecraftBot when BOT_DEBUG is set)
LOG_RE = re.compile(r"\[pkt\] in state=(\w+) id=0x([0-9A-Fa-f]+) len=(\d+)")
# public static final int SET_HEALTH = id("set_health", 0x5B);
DECL_RE = re.compile(
    r'public\s+static\s+final\s+int\s+(\w+)\s*=\s*id\("([\w.]+)",\s*(0x[0-9A-Fa-f]+|\d+)\)')
SECTION_RE = re.compile(r"//\s*-+\s*(clientbound|serverbound)")
# case 0x2E: { // level_chunk_with_light
CASE_RE = re.compile(r"case\s+(0x[0-9A-Fa-f]+|\d+)\s*:\s*\{?\s*(?://\s*(.+))?")
# private static final int MOVE_ENTITY_POS = 0x36;   (modules name their ids)
CONST_RE = re.compile(
    r"static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*(0x[0-9A-Fa-f]+|\d+)\s*;")
# Module constants that look like ids but are not packet ids.
NOT_A_PACKET = ("BREAK_", "ACTION_", "MAX_", "DELTA_", "JUMP_", "TICK_", "REACH_",
                "EYE_", "GOAL_", "INPUT_", "WALK_", "STEP_", "POLL_", "MOVE_INTERVAL",
                "HAND_", "FACE_", "DIR_", "STATUS_", "TYPE_", "SEARCH_", "SCAN_")
# if (packetId != 0x42) return;   // the modules also guard on raw literals
EQ_RE = re.compile(r"packetId\s*(?:==|!=)\s*(0x[0-9A-Fa-f]+|\d+)")
COMMENT_RE = re.compile(r"//\s*(.+)$")


def parse_int(tok):
    return int(tok, 16) if tok.lower().startswith("0x") else int(tok)


# ---------------------------------------------------------------- declared ids

def declared_ids():
    """Map (direction, id) -> (name, source) parsed from the Java sources."""
    table = {}

    if os.path.exists(PACKET_IDS_JAVA):
        direction = "clientbound"
        with open(PACKET_IDS_JAVA, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = SECTION_RE.search(line)
                if m:
                    direction = m.group(1)
                    continue
                m = DECL_RE.search(line)
                if m:
                    const, key, tok = m.groups()
                    table[(direction, parse_int(tok))] = (key, "PacketIds." + const)

    if os.path.isdir(MODULES_DIR):
        for name in sorted(os.listdir(MODULES_DIR)):
            if not name.endswith(".java"):
                continue
            path = os.path.join(MODULES_DIR, name)
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    m = CASE_RE.search(line)
                    if m:
                        pid = parse_int(m.group(1))
                        label = (m.group(2) or "").strip()
                        if label:
                            table.setdefault(("case", pid), (label, name[:-5]))
                        continue
                    m = EQ_RE.search(line)
                    if m:
                        pid = parse_int(m.group(1))
                        cm = COMMENT_RE.search(line)
                        label = cm.group(1).strip() if cm else name[:-5]
                        table.setdefault(("case", pid), (label, name[:-5]))
                        continue
                    # A module-level constant is a weaker claim than a case label:
                    # it may also be serverbound, so it only fills gaps.
                    m = CONST_RE.search(line)
                    if m:
                        const, tok = m.groups()
                        if const.startswith(NOT_A_PACKET):
                            continue
                        pid = parse_int(tok)
                        if 0 <= pid <= 0xFF:
                            table.setdefault(("module", pid),
                                             (const.lower(), name[:-5]))
    return table


def lookup(table, pid):
    """Best label for an inbound id: case label, then PacketIds, then constant."""
    hit = table.get(("case", pid))
    if hit:
        return hit
    hit = table.get(("clientbound", pid))
    if hit:
        return hit
    return table.get(("module", pid))


# -------------------------------------------------------------------- analyze

class Stats:
    __slots__ = ("count", "total_len", "min_len", "max_len", "first_ts", "last_ts")

    def __init__(self):
        self.count = 0
        self.total_len = 0
        self.min_len = 1 << 30
        self.max_len = 0

    def add(self, length):
        self.count += 1
        self.total_len += length
        if length < self.min_len:
            self.min_len = length
        if length > self.max_len:
            self.max_len = length

    @property
    def avg(self):
        return self.total_len / self.count if self.count else 0.0


def analyze(paths, state_filter=None):
    per_state = defaultdict(dict)          # state -> id -> Stats
    lines_read = 0
    matched = 0
    files = []

    for path in paths:
        file_lines = 0
        try:
            fh = open(path, "r", encoding="utf-8", errors="replace")
        except OSError as exc:
            print("skip %s: %s" % (path, exc), file=sys.stderr)
            continue
        with fh:
            for line in fh:
                file_lines += 1
                if "[pkt] in" not in line:
                    continue
                m = LOG_RE.search(line)
                if not m:
                    continue
                state, hexid, length = m.groups()
                if state_filter and state != state_filter:
                    continue
                pid = int(hexid, 16)
                bucket = per_state[state]
                st = bucket.get(pid)
                if st is None:
                    st = bucket[pid] = Stats()
                st.add(int(length))
                matched += 1
        lines_read += file_lines
        files.append((path, file_lines))

    return per_state, matched, lines_read, files


def cmd_analyze(args):
    paths = args.logs
    if not paths:
        paths = sorted(
            os.path.join(ROOT, n) for n in os.listdir(ROOT)
            if n.endswith(".log") and os.path.getsize(os.path.join(ROOT, n)) < 64 << 20
        )
    per_state, matched, lines_read, files = analyze(paths, args.state)
    table = declared_ids()

    if args.json:
        out = {"files": [{"path": p, "lines": n} for p, n in files],
               "records": matched,
               "states": {}}
        for state, bucket in sorted(per_state.items()):
            out["states"][state] = {
                "0x%02X" % pid: {
                    "count": st.count, "min": st.min_len,
                    "avg": round(st.avg, 2), "max": st.max_len,
                    "name": (lookup(table, pid) or ("", ""))[0],
                } for pid, st in sorted(bucket.items(), key=lambda kv: -kv[1].count)
            }
        print(json.dumps(out, indent=2))
        return 0

    print("=" * 78)
    print("pktstat analyze")
    print("=" * 78)
    print("records parsed : %d" % matched)
    print("lines scanned  : %d" % lines_read)
    for path, n in files:
        print("  %-28s %9d lines" % (os.path.basename(path), n))
    if not matched:
        print("\nNo '[pkt] in ...' records found. Run the bot with BOT_DEBUG=1,")
        print("or capture with: pktstat.py probe --host <ip> --port <port>")
        return 1

    seen = set()
    for state, bucket in sorted(per_state.items()):
        rows = sorted(bucket.items(), key=lambda kv: -kv[1].count)
        print()
        print("-" * 78)
        print("%s  (%d distinct ids, %d packets)"
              % (state, len(rows), sum(s.count for _, s in rows)))
        print("-" * 78)
        print("%-6s %9s %6s %7s %7s  %s" % ("id", "count", "min", "avg", "max", "declared"))
        for pid, st in rows[:args.top]:
            seen.add((state, pid))
            name = lookup(table, pid)
            if name is None:
                label = "-- undeclared --"
            elif name[0] == name[1]:
                label = name[0]
            else:
                label = "%s (%s)" % (name[0], name[1])
            print("0x%02X   %9d %6d %7.1f %7d  %s"
                  % (pid, st.count, st.min_len, st.avg, st.max_len, label))
        if len(rows) > args.top:
            rest = sum(s.count for _, s in rows[args.top:])
            print("... %d more ids, %d packets (use --top to see them)"
                  % (len(rows) - args.top, rest))

    # Undeclared: traffic the bot parses nowhere. These are the expansion targets.
    undeclared = []
    for state, bucket in per_state.items():
        if state != "PLAY":
            continue
        for pid, st in sorted(bucket.items(), key=lambda kv: -kv[1].count):
            if lookup(table, pid) is None:
                undeclared.append((pid, st))
    if undeclared:
        print()
        print("-" * 78)
        print("UNDECLARED inbound ids in PLAY - the bot currently ignores these")
        print("-" * 78)
        for pid, st in undeclared[:args.top]:
            print("0x%02X   %9d packets  avg %5.1f B  max %6d B"
                  % (pid, st.count, st.avg, st.max_len))

    # Declared but never observed: these ids are probably wrong for this server.
    if "PLAY" in per_state and args.state in (None, "PLAY"):
        observed = set(per_state["PLAY"])
        missing = []
        for (direction, pid), (name, src) in table.items():
            if direction != "clientbound" or pid in observed:
                continue
            missing.append((pid, name, src))
        if missing:
            print()
            print("-" * 78)
            print("DECLARED clientbound ids never seen in this capture")
            print("(wrong id for this server build, or simply never sent)")
            print("-" * 78)
            for pid, name, src in sorted(missing):
                print("0x%02X   %-24s %s" % (pid, name, src))

    return 0


# ---------------------------------------------------------------------- burst

def cmd_burst(args):
    """
    The ordered inbound sequence from the start of a session. The join burst is
    a fingerprint: a packet's body length at a known point in the sequence is
    strong evidence for its id (player_abilities is 1+4+4 = 9 bytes, game_event
    is 1+4 = 5, forget_level_chunk is exactly 8, ...).
    """
    table = declared_ids()
    shown = 0
    state = None
    for path in args.logs:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if "[pkt] in" not in line:
                    continue
                m = LOG_RE.search(line)
                if not m:
                    continue
                st, hexid, length = m.groups()
                if args.state and st != args.state:
                    continue
                if state is None:
                    state = st
                pid = int(hexid, 16)
                name = lookup(table, pid)
                label = ("%s" % name[0]) if name else "?"
                print("%3d  %-6s 0x%02X  %6s B   %s"
                      % (shown + 1, st, pid, length, label))
                shown += 1
                if shown >= args.n:
                    return 0
    return 0


# ------------------------------------------------------------------------ ids

def cmd_ids(args):
    table = declared_ids()
    if not table:
        print("No ids parsed - is src/ present?", file=sys.stderr)
        return 1
    by_dir = defaultdict(list)
    for (direction, pid), (name, src) in table.items():
        by_dir[direction].append((pid, name, src))
    for direction in ("clientbound", "serverbound", "case", "module"):
        if direction not in by_dir:
            continue
        print()
        print("%s (%d)" % (direction.upper(), len(by_dir[direction])))
        print("-" * 60)
        for pid, name, src in sorted(by_dir[direction]):
            print("0x%02X  %-26s %s" % (pid, name, src))
    return 0


# ---------------------------------------------------------------------- probe

def write_varint(value):
    out = bytearray()
    value &= 0xFFFFFFFF
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def offline_uuid(name):
    """Java's UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)) - a v3 UUID."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return bytes(digest)


class Conn:
    def __init__(self, host, port, timeout):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.buf = b""
        self.compression = False

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("connection closed by server")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _read_varint(self):
        result = 0
        shift = 0
        while True:
            b = self._recv(1)[0]
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7
            if shift > 35:
                raise ValueError("VarInt too long")

    def read_packet(self):
        length = self._read_varint()
        frame = self._recv(length)
        if not self.compression:
            return frame
        pos = 0
        data_len = 0
        shift = 0
        while True:
            b = frame[pos]
            pos += 1
            data_len |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        if data_len == 0:
            return frame[pos:]
        return zlib.decompress(frame[pos:])

    def send(self, pid, body=b""):
        payload = write_varint(pid) + body
        if self.compression:
            if len(payload) >= 256:
                comp = zlib.compress(payload)
                frame = write_varint(len(payload)) + comp
            else:
                frame = b"\x00" + payload
        else:
            frame = payload
        self.sock.sendall(write_varint(len(frame)) + frame)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def mc_string(text):
    raw = text.encode("utf-8")
    return write_varint(len(raw)) + raw


def cmd_probe(args):
    """
    Minimal offline-mode client: enough of the 26.3 handshake to reach PLAY and
    then just record what arrives. Every id it prints is ground truth for this
    server build.
    """
    log = open(args.out, "w", encoding="utf-8") if args.out else sys.stdout
    counts = defaultdict(int)
    deadline = time.time() + args.seconds
    state = "LOGIN"

    def emit(line):
        log.write(line + "\n")
        if args.out:
            log.flush()
        if not args.quiet:
            print(line)

    try:
        conn = Conn(args.host, args.port, args.timeout)
    except OSError as exc:
        print("connect failed: %s" % exc, file=sys.stderr)
        return 1

    try:
        # handshake (next state = 2, login)
        body = write_varint(777) + mc_string(args.host) + struct.pack(">H", args.port) + write_varint(2)
        conn.send(0x00, body)
        conn.send(0x00, mc_string(args.user) + offline_uuid(args.user))

        while time.time() < deadline:
            try:
                payload = conn.read_packet()
            except socket.timeout:
                continue
            except EOFError as exc:
                print("server closed: %s" % exc, file=sys.stderr)
                break

            pos = 0
            pid = 0
            shift = 0
            while True:
                b = payload[pos]
                pos += 1
                pid |= (b & 0x7F) << shift
                if not b & 0x80:
                    break
                shift += 7
            body = payload[pos:]
            emit("[pkt] in state=%s id=0x%02X len=%d" % (state, pid, len(body)))
            counts[(state, pid)] += 1

            if state == "LOGIN":
                if pid == 0x03:                      # set_compression
                    threshold = 0
                    shift = 0
                    i = 0
                    while True:
                        b = body[i]
                        i += 1
                        threshold |= (b & 0x7F) << shift
                        if not b & 0x80:
                            break
                        shift += 7
                    conn.compression = True
                    emit("[probe] compression on, threshold=%d" % threshold)
                elif pid == 0x02:                    # login_success
                    conn.send(0x03)                  # login_acknowledged
                    state = "CONFIG"
                    emit("[probe] -> config")
                    # client_information, byte-for-byte what the bot sends
                    conn.send(0x00, mc_string("en_US") + bytes([8]) + write_varint(0)
                              + bytes([1, 0x7F]) + write_varint(1) + bytes([0, 0])
                              + write_varint(0))
                elif pid == 0x00:
                    print("login disconnect: %s" % body.decode("utf-8", "replace"), file=sys.stderr)
                    break

            elif state == "CONFIG":
                if pid == 0x04:                      # keep_alive
                    conn.send(0x04, body)
                elif pid == 0x0E:                    # select_known_packs (clientbound)
                    conn.send(0x07, body)
                elif pid == 0x03:                    # finish_configuration
                    conn.send(0x03)
                    state = "PLAY"
                    emit("[probe] -> play")
                elif pid == 0x02:
                    print("config disconnect: %s" % body.decode("utf-8", "replace"), file=sys.stderr)
                    break

            elif state == "PLAY":
                if pid == 0x2D:                      # keep_alive
                    conn.send(0x1C, body)
                elif pid == 0x49 and len(body) >= 8:  # player_position -> accept
                    # echo id + x,y,z,yaw,pitch so the server stops correcting us
                    i = 0
                    tid = 0
                    shift = 0
                    while True:
                        b = body[i]
                        i += 1
                        tid |= (b & 0x7F) << shift
                        if not b & 0x80:
                            break
                        shift += 7
                    x, y, z, yaw, pitch = struct.unpack_from(">dddff", body, i)
                    conn.send(0x00, write_varint(tid) + struct.pack(">dddff", x, y, z, yaw, pitch))
                elif pid == 0x20:                    # play disconnect
                    print("play disconnect", file=sys.stderr)
                    break
    except (OSError, EOFError, ValueError, zlib.error) as exc:
        print("probe error: %s" % exc, file=sys.stderr)
    finally:
        conn.close()
        if args.out:
            log.close()

    total = sum(counts.values())
    print("\ncaptured %d packets, %d distinct ids" % (total, len(counts)))
    for (st, pid), n in sorted(counts.items(), key=lambda kv: -kv[1])[:20]:
        print("  %-6s 0x%02X  %d" % (st, pid, n))
    if args.out:
        print("log written to %s" % args.out)
    return 0


# ----------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(
        prog="pktstat",
        description="Packet-id observability for the sfus bot (protocol 777).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("analyze", help="histogram of inbound ids from BOT_DEBUG logs")
    a.add_argument("logs", nargs="*", help="log files (default: *.log in the project root)")
    a.add_argument("--state", help="only this protocol state (LOGIN/CONFIG/PLAY)")
    a.add_argument("--top", type=int, default=40, help="rows per table (default 40)")
    a.add_argument("--json", action="store_true", help="machine-readable output")
    a.set_defaults(func=cmd_analyze)

    i = sub.add_parser("ids", help="dump the id table declared in src/")
    i.set_defaults(func=cmd_ids)

    b = sub.add_parser("burst", help="ordered packet sequence (join fingerprint)")
    b.add_argument("logs", nargs="+")
    b.add_argument("--n", type=int, default=40, help="how many packets to print")
    b.add_argument("--state", default="PLAY", help="protocol state (default PLAY)")
    b.set_defaults(func=cmd_burst)

    p = sub.add_parser("probe", help="capture live inbound ids from a server")
    p.add_argument("--host", default="51.195.191.92")
    p.add_argument("--port", type=int, default=10679)
    p.add_argument("--user", default="ProbeBot")
    p.add_argument("--seconds", type=float, default=20.0)
    p.add_argument("--timeout", type=float, default=15.0)
    p.add_argument("--out", help="write the capture to this file (BOT_DEBUG format)")
    p.add_argument("--quiet", action="store_true", help="do not echo packets")
    p.set_defaults(func=cmd_probe)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
