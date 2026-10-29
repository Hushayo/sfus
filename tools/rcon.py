#!/usr/bin/env python3
"""Minimal RCON client for driving the local test server.

Usage:
    python tools/rcon.py "<command>" ["<command>" ...]

Reads host/port/password from the environment (defaults suit .tmp/server):
    RCON_HOST=127.0.0.1 RCON_PORT=25575 RCON_PASS=test
"""
import os
import socket
import struct
import sys

HOST = os.environ.get("RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("RCON_PORT", "25575"))
PASS = os.environ.get("RCON_PASS", "test")

SERVERDATA_AUTH = 3
SERVERDATA_AUTH_RESPONSE = 2
SERVERDATA_EXECCOMMAND = 2


def _send(sock, req_id, ptype, body):
    payload = struct.pack("<ii", req_id, ptype) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def _recv(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError("server closed the connection")
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("server closed the connection")
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:-2].decode("utf-8", "replace")


def run(commands):
    with socket.create_connection((HOST, PORT), timeout=10) as sock:
        _send(sock, 1, SERVERDATA_AUTH, PASS)
        req_id, ptype, _ = _recv(sock)
        if req_id == -1:
            raise SystemExit("RCON auth failed")
        out = []
        for i, cmd in enumerate(commands, start=10):
            _send(sock, i, SERVERDATA_EXECCOMMAND, cmd)
            _, _, body = _recv(sock)
            out.append((cmd, body))
        return out


if __name__ == "__main__":
    cmds = sys.argv[1:]
    if not cmds:
        raise SystemExit(__doc__)
    for cmd, body in run(cmds):
        print("> " + cmd)
        if body.strip():
            print(body)
