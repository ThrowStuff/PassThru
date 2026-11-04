# SPDX-License-Identifier: GPL-3.0-only
"""Persistent hashed credentials and numeric comparison primitives."""
from contextlib import contextmanager
import tempfile
import hashlib
import sys
import hmac
import json
import os
from pathlib import Path
import secrets
import threading
import time


def pair_key_payload(key, name):
    """Append a hostname, bounded without splitting a UTF-8 character."""
    if len(key) != 32:
        raise ValueError("expected 32-byte key")
    return key + name.encode("utf-8")[:64].decode("utf-8", errors="ignore").encode("utf-8")


def code(fp, nc, ns):
    if any(len(x) != 32 for x in (fp, nc, ns)):
        raise ValueError('expected 32-byte values')
    return f'{int.from_bytes(hashlib.sha256(b"PTPAIR" + fp + nc + ns).digest()[:4], "big") % 1000000:06d}'


class DeviceStore:
    def __init__(self, path=None):
        if path is None:
            appdata = os.environ.get('APPDATA') if sys.platform == 'win32' else None
            path = Path(appdata) / 'PassThru' if appdata else Path.home() / '.config/passthru'
        self.path = Path(path)
        self.path.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        with self.transaction():
            self.cert, self.key = self.path / 'cert.pem', self.path / 'key.pem'
            if self.cert.exists() != self.key.exists():
                raise RuntimeError('certificate and private key must both exist')
            if not self.cert.exists():
                from .sender import generate_certificate
                # Restrict creation permissions, including while openssl writes.
                for p in (self.cert, self.key):
                    p.touch(mode=0o600)
                try:
                    generate_certificate(self.cert, self.key)
                except Exception:
                    self.cert.unlink(missing_ok=True)
                    self.key.unlink(missing_ok=True)
                    raise
            for p in (self.cert, self.key):
                p.chmod(0o600)
            self.file = self.path / 'devices.json'
            if not self.file.exists():
                self._write([])
            self.file.chmod(0o600)

    @contextmanager
    def transaction(self):
        with self.lock:
            with open(self.path / 'devices.lock', 'a+b') as lockfile:
                if sys.platform == 'win32':
                    import msvcrt
                    lockfile.seek(0)
                    if not lockfile.read(1):
                        lockfile.write(b'0'); lockfile.flush()
                    lockfile.seek(0)
                    msvcrt.locking(lockfile.fileno(), msvcrt.LK_LOCK, 1)
                else:
                    import fcntl
                    fcntl.flock(lockfile, fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    if sys.platform == 'win32':
                        lockfile.seek(0)
                        msvcrt.locking(lockfile.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(lockfile, fcntl.LOCK_UN)

    def list(self):
        with self.lock:
            return json.loads(self.file.read_text())

    def _write(self, devices):
        fd, name = tempfile.mkstemp(dir=self.path, prefix='devices-', suffix='.tmp')
        temporary = Path(name)
        try:
            with os.fdopen(fd, 'w') as output:
                json.dump(devices, output)
            temporary.replace(self.file)
        finally:
            temporary.unlink(missing_ok=True)

    def contains(self, identity):
        with self.lock:
            stamp = self.file.stat()
            signature = (stamp.st_mtime_ns, stamp.st_size, stamp.st_ino)
            if getattr(self, '_signature', None) != signature:
                self._devices = self.list()
                self._signature = signature
            return any(d['id'] == identity for d in self._devices)

    def add(self, key, name='Phone'):
        with self.transaction():
            device = dict(id=secrets.token_hex(8), name=name,
                          key_sha256=hashlib.sha256(key).hexdigest(), paired_at=time.time())
            self._write(self.list() + [device])
            return device

    def revoke(self, identity):
        with self.transaction():
            self._write([d for d in self.list() if d['id'] != identity])

    def authenticate(self, key):
        digest = hashlib.sha256(key).hexdigest()
        with self.lock:
            matches = [d['id'] for d in self.list()
                       if hmac.compare_digest(digest, d['key_sha256'])]
        return matches[0] if len(key) == 32 and matches else None


class RateLimiter:
    def __init__(self, clock=time.monotonic):
        self.clock, self.failures, self.blocks = clock, {}, {}
        self.lock = threading.Lock()

    def blocked(self, ip):
        with self.lock:
            return self.blocks.get(ip, 0) > self.clock()

    def fail(self, ip):
        with self.lock:
            now = self.clock()
            failures = [t for t in self.failures.get(ip, []) if now - t < 60]
            failures.append(now)
            if len(failures) >= 3:
                self.blocks[ip] = now + 60
                failures = []
            self.failures[ip] = failures
