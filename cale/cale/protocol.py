# SPDX-License-Identifier: GPL-3.0-only
"""PassThru v2 wire framing. Unknown types remain available to be ignored."""
import struct
import time
from dataclasses import dataclass

AUDIO, PING, PONG, STATS, BYE = range(1, 6)
PAIR_COMMIT, PAIR_NONCE, PAIR_REVEAL, PAIR_CONFIRM, PAIR_KEY, AUTH, AUTH_OK, AUTH_FAIL = range(6, 14)
PAIR_FAIL = AUTH_FAIL
HEADER = struct.Struct('!HBBIQI')
MAX_PAYLOAD = 65536


class ProtocolError(ValueError):
    pass


@dataclass(frozen=True)
class Frame:
    type: int
    sequence: int = 0
    timestamp: int = 0
    payload: bytes = b''


def encode(frame):
    if len(frame.payload) > MAX_PAYLOAD:
        raise ProtocolError('payload too large')
    try:
        return HEADER.pack(0x5054, 2, frame.type, frame.sequence,
                           frame.timestamp, len(frame.payload)) + frame.payload
    except (struct.error, TypeError) as error:
        raise ProtocolError(str(error)) from error


def _header(data):
    if len(data) != HEADER.size:
        raise ProtocolError('header must be 20 bytes')
    magic, version, kind, sequence, timestamp, size = HEADER.unpack(data)
    if magic != 0x5054 or version != 2 or size > MAX_PAYLOAD:
        raise ProtocolError('bad magic, version, or length')
    return kind, sequence, timestamp, size


def decode(data):
    kind, sequence, timestamp, size = _header(data[:HEADER.size])
    if len(data) != HEADER.size + size:
        raise ProtocolError('payload length mismatch')
    return Frame(kind, sequence, timestamp, data[HEADER.size:])


def _read_exact(sock, size, allow_eof=False):
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            if allow_eof and not data:
                return None
            raise ProtocolError('truncated frame')
        data.extend(part)
    return bytes(data)


def read_frame(sock, clock=time.monotonic):
    """Return None on clean EOF; bound completion from the first byte to 2 s."""
    first = sock.recv(1)
    if not first:
        return None
    deadline = clock() + 2
    original = sock.gettimeout() if hasattr(sock, 'gettimeout') else None
    class Budget:
        def recv(self, size):
            remaining = deadline - clock()
            if remaining <= 0:
                raise TimeoutError('frame deadline')
            if hasattr(sock, 'settimeout'):
                sock.settimeout(min(remaining, original) if original else remaining)
            part = sock.recv(size)
            if clock() > deadline:
                raise TimeoutError('frame deadline')
            return part
    try:
        reader = Budget()
        header = first + _read_exact(reader, HEADER.size - 1)
        kind, sequence, timestamp, size = _header(header)
        return Frame(kind, sequence, timestamp, _read_exact(reader, size))
    finally:
        if hasattr(sock, 'settimeout'):
            sock.settimeout(original)
