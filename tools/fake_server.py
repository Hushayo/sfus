"""Fake protocol-777 server for offline testing.

Speaks just enough of the handshake (compression, login, config, play) to drive
`tools/pktstat.py probe` - or the bot itself - without touching a real server.
It is how the probe was verified: the probe must complete the state machine,
turn compression on at the right moment, and echo keep_alive at 0x1C.

    python tools/fake_server.py 25599 &
    python tools/pktstat.py probe --host 127.0.0.1 --port 25599 --seconds 4

Not a protocol reference: it sends the minimum it can get away with, and the
play-phase bodies are placeholders.
"""
import socket
import struct
import sys
import threading
import uuid
import zlib

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 25599
THRESHOLD = 256


def read_varint(sock, buf):
    val = 0
    shift = 0
    while True:
        while len(buf) == 0:
            buf.extend(sock.recv(4096))
        b = buf.pop(0)
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val
        shift += 7


def read_packet(sock, buf, compressed):
    length = read_varint(sock, buf)
    while len(buf) < length:
        buf.extend(sock.recv(65536))
    frame = bytes(buf[:length])
    del buf[:length]
    if not compressed:
        return frame
    pos = 0
    dlen = 0
    shift = 0
    while True:
        b = frame[pos]
        pos += 1
        dlen |= (b & 0x7F) << shift
        if not b & 0x80:
            break
        shift += 7
    return frame[pos:] if dlen == 0 else zlib.decompress(frame[pos:])


def wv(value):
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


def mstr(text):
    raw = text.encode()
    return wv(len(raw)) + raw


class Server:
    def __init__(self, sock):
        self.sock = sock
        self.buf = bytearray()
        self.compressed = False

    def send(self, pid, body=b""):
        payload = wv(pid) + body
        if self.compressed:
            if len(payload) >= THRESHOLD:
                frame = wv(len(payload)) + zlib.compress(payload)
            else:
                frame = b"\x00" + payload
        else:
            frame = payload
        self.sock.sendall(wv(len(frame)) + frame)

    def recv(self):
        return read_packet(self.sock, self.buf, self.compressed)


def handle(conn):
    srv = Server(conn)
    try:
        hs = srv.recv()                      # handshake
        print("  server: handshake id=0x%02X" % hs[0], flush=True)
        ls = srv.recv()                      # login start
        print("  server: login_start id=0x%02X" % ls[0], flush=True)

        srv.send(0x03, wv(THRESHOLD))        # set_compression (still uncompressed)
        srv.compressed = True
        print("  server: -> set_compression", flush=True)

        body = (uuid.uuid4().bytes + mstr("ProbeBot") + wv(0) + uuid.uuid4().bytes)
        srv.send(0x02, body)                 # login_success
        print("  server: -> login_success", flush=True)

        ack = srv.recv()
        print("  server: <- login_acknowledged id=0x%02X" % ack[0], flush=True)
        info = srv.recv()
        print("  server: <- client_information id=0x%02X" % info[0], flush=True)

        srv.send(0x03)                       # finish_configuration
        print("  server: -> finish_configuration", flush=True)
        ack = srv.recv()
        print("  server: <- config_ack id=0x%02X" % ack[0], flush=True)

        srv.send(0x32, b"\x00\x00\x01\x00" + mstr("minecraft:overworld")
                 + wv(20) + wv(10) + wv(10) + b"\x00\x00\x00")   # login (play)
        srv.send(0x6A, struct.pack(">f", 20.0) + b"\x14" + struct.pack(">f", 5.0))
        srv.send(0x69, struct.pack(">f", 0.0) + b"\x00\x00")
        srv.send(0x2D, struct.pack(">q", 1234))                  # keep_alive
        print("  server: -> login(play), set_health, set_experience, keep_alive",
              flush=True)

        ka = srv.recv()
        print("  server: <- keepalive_echo id=0x%02X body=%s"
              % (ka[0], ka[1:].hex()), flush=True)

        srv.send(0x2E, bytes(4000))          # a big chunk-ish packet
        srv.send(0x67, bytes(8))
        print("  server: -> chunk + entity packet", flush=True)

        while True:                          # drain until the probe hangs up
            if not srv.recv():
                break
    except Exception as exc:
        print("  server: connection ended (%s)" % exc, flush=True)
    finally:
        conn.close()


def main():
    lsock = socket.socket()
    lsock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    lsock.bind(("127.0.0.1", PORT))
    lsock.listen(1)
    print("fake server listening on 127.0.0.1:%d" % PORT, flush=True)
    conn, _ = lsock.accept()
    handle(conn)
    lsock.close()


if __name__ == "__main__":
    main()
