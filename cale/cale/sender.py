# SPDX-License-Identifier: GPL-3.0-only
"""Single-receiver TLS sender with independently running capture."""
from collections import deque
import secrets
import hmac
import hashlib
import os
import json
import logging
import select
import struct
import shutil
import socket
import ssl
import subprocess
import threading
import time
from pathlib import Path
from .capture import SILENCE
from .protocol import (AUDIO, PING, PONG, STATS, BYE, PAIR_COMMIT, PAIR_NONCE,
                       PAIR_REVEAL, PAIR_CONFIRM, PAIR_KEY, AUTH, AUTH_OK,
                       AUTH_FAIL, PAIR_FAIL, Frame, encode, read_frame)
from .pairing import DeviceStore, RateLimiter, code, pair_key_payload


def generate_certificate(cert, key):
    """Self-signed identity certificate. Phones pin its SHA-256, so the
    validity window is not what grants trust; make it long so a paired phone
    never breaks because the certificate expired."""
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.x509.oid import NameOID
    except ImportError:
        x509 = None
    if x509 is not None:
        import datetime
        private = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'PassThru')])
        now = datetime.datetime.now(datetime.timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                       .public_key(private.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - datetime.timedelta(days=1))
                       .not_valid_after(now + datetime.timedelta(days=365 * 20))
                       .sign(private, hashes.SHA256()))
        key_bytes = private.private_bytes(serialization.Encoding.PEM,
                                          serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption())
        fd = os.open(str(key), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, 'wb') as output:
            output.write(key_bytes)
        with open(cert, 'wb') as output:
            output.write(certificate.public_bytes(serialization.Encoding.PEM))
        return
    openssl = shutil.which('openssl')
    if not openssl:
        raise RuntimeError('creating a certificate needs the cryptography package or openssl')
    subprocess.run([openssl, 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                    '-keyout', str(key), '-out', str(cert), '-days', '7300',
                    '-subj', '/CN=PassThru'], check=True, capture_output=True)


def fingerprint(cert):
    with open(cert, encoding='ascii') as source:
        der = ssl.PEM_cert_to_DER_cert(source.read())
    return hashlib.sha256(der).hexdigest()


class FrameQueue:
    def __init__(self):
        self.frames = deque()
        self.condition = threading.Condition()
        self.drops = 0

    def put(self, frame):
        with self.condition:
            if len(self.frames) == 3:
                self.frames.popleft()
                self.drops += 1
            self.frames.append(frame)
            self.condition.notify()

    def get(self, timeout=.02):
        with self.condition:
            self.condition.wait_for(lambda: self.frames, timeout=timeout)
            return self.frames.popleft() if self.frames else None

    def clear(self):
        with self.condition:
            self.drops += len(self.frames)
            self.frames.clear()


class Sender:
    def __init__(self, capture, cert, key, port=0, host='0.0.0.0',
                 clock=time.monotonic, store=None, confirm=None):
        self.capture, self.clock = capture, clock
        self.store = store or DeviceStore(Path(cert).parent)
        self.confirm = confirm or (lambda value: False)
        self.fp = bytes.fromhex(fingerprint(cert))
        self.limiter = RateLimiter()
        self.pair_lock = threading.Lock()
        self.connections = set()
        self.active = None
        self.active_device = None
        self.capture_error: str | None = None
        self.slots = threading.BoundedSemaphore(8)
        self.connections_lock = threading.Lock()
        self.queue = FrameQueue()
        self.last_stats = None
        self.stopped = threading.Event()
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_3
        self.context.load_cert_chain(cert, key)
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind((host, port))
        self.listener.listen(1)
        self.listener.settimeout(.1)
        self.port = self.listener.getsockname()[1]
        self.threads = []

    def start(self):
        for task in (self._capture, self._serve):
            thread = threading.Thread(target=task, daemon=True)
            self.threads.append(thread)
            thread.start()

    @property
    def connected_phone(self):
        """Snapshot of the authenticated session, identified by peer address."""
        with self.connections_lock:
            if self.active is None:
                return None
            try:
                return self.active.getpeername()[0]
            except OSError:
                return None

    def _capture(self):
        try:
            for pcm in self.capture:
                if self.stopped.is_set():
                    break
                self.queue.put((int(self.clock() * 1000000), pcm))
        except Exception as error:
            if not self.stopped.is_set():
                self.capture_error = str(error) or type(error).__name__
        else:
            if not self.stopped.is_set():
                self.capture_error = 'capture iterator ended'

    def revoke_device(self, device_id):
        # Registration and GUI revocation share this lock.
        with self.connections_lock:
            self.store.revoke(device_id)
            self._disconnect_device(device_id)

    def disconnect_device(self, device_id):
        with self.connections_lock:
            self._disconnect_device(device_id)

    def _disconnect_device(self, device_id):
        if self.active is not None and self.active_device == device_id:
            try:
                self.active.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _serve(self):
        while not self.stopped.is_set():
            try:
                raw, address = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            if not self.slots.acquire(False):
                # At most 8 connections at once (handshake, auth or audio).
                raw.close()
                continue
            raw.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
            raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            raw.settimeout(5)
            thread = threading.Thread(target=self._handle, args=(raw, address[0]), daemon=True)
            self.threads = [t for t in self.threads if t.is_alive()]
            self.threads.append(thread)
            thread.start()

    def _handle(self, raw, ip):
        conn = raw
        with self.connections_lock:
            self.connections.add(raw)
        try:
            conn = self.context.wrap_socket(raw, server_side=True)
            with self.connections_lock:
                self.connections.discard(raw)
                self.connections.add(conn)
            device_id = self._authorize(conn, ip)
            if device_id:
                # One audio session at a time; the newest authenticated phone
                # replaces any older session (e.g. a reconnect before the old
                # socket timed out), so sessions never split the frame queue.
                with self.connections_lock:
                    if not self.store.contains(device_id):
                        return
                    previous, self.active = self.active, conn
                    self.active_device = device_id
                    self.last_stats = None
                if previous is not None:
                    try:
                        previous.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                self._session(conn)
        except (OSError, ValueError):
            pass
        finally:
            conn.close()
            with self.connections_lock:
                self.connections.discard(raw)
                self.connections.discard(conn)
                if self.active is conn:
                    self.active = None
                    self.active_device = None
            self.slots.release()

    def _authorize(self, conn, ip):
        def send(kind, payload=b''):
            conn.settimeout(1)
            conn.sendall(encode(Frame(kind, payload=payload)))
        def fail(reason):
            self.limiter.fail(ip)
            try:
                send(PAIR_FAIL, reason.encode())
            except OSError:
                pass
            return False
        def receive(deadline):
            # One absolute deadline covers partial headers and payloads too.
            while not self.stopped.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('pairing timeout')
                if conn.pending() or select.select([conn], [], [], min(.2, remaining))[0]:
                    class Budget:
                        timeout = 2
                        def gettimeout(self):
                            return self.timeout
                        def settimeout(self, value):
                            self.timeout = value
                        def recv(self, size):
                            left = deadline - time.monotonic()
                            if left <= 0:
                                raise TimeoutError('frame deadline')
                            conn.settimeout(min(self.timeout, left))
                            return conn.recv(size)
                    return read_frame(Budget())
            raise OSError('stopped')
        if self.limiter.blocked(ip):
            send(AUTH_FAIL, b'rate limited')
            return False
        acquired = False
        try:
            first = receive(time.monotonic() + 5)
            if first is None:
                return fail('missing first frame')
            if first.type == AUTH:
                device_id = self.store.authenticate(first.payload)
                if device_id is None:
                    return fail('invalid key')
                self.queue.clear()
                send(AUTH_OK)
                return device_id
            if first.type != PAIR_COMMIT or len(first.payload) != 32:
                return fail('expected AUTH or PAIR_COMMIT')
            acquired = self.pair_lock.acquire(False)
            if not acquired:
                return fail('busy')
            ns = secrets.token_bytes(32)
            send(PAIR_NONCE, ns)
            reveal = receive(time.monotonic() + 5)
            if (reveal is None or reveal.type != PAIR_REVEAL or len(reveal.payload) != 32
                    or not hmac.compare_digest(hashlib.sha256(reveal.payload).digest(), first.payload)):
                return fail('commit mismatch')
            value = code(self.fp, reveal.payload, ns)
            done, result = threading.Event(), []
            def confirm():
                try:
                    result.append(bool(self.confirm(value)))
                except Exception:
                    result.append(False)
                finally:
                    done.set()
            threading.Thread(target=confirm, daemon=True).start()
            deadline, remote = time.monotonic() + 120, False
            while not self.stopped.is_set():
                if time.monotonic() >= deadline:
                    return fail('confirmation timeout')
                if done.is_set() and not result[0]:
                    return fail('local mismatch')
                if remote and done.is_set():
                    key = secrets.token_bytes(32)
                    device = self.store.add(key)
                    self.queue.clear()
                    send(PAIR_KEY, pair_key_payload(key, socket.gethostname()))
                    return device['id']
                if conn.pending() or select.select([conn], [], [], .2)[0]:
                    frame = receive(deadline)
                    if frame is None or frame.type != PAIR_CONFIRM or frame.payload != b'\x01' or remote:
                        return fail('confirmation rejected')
                    remote = True
            return False
        except (OSError, ValueError):
            return fail('invalid or timed out pairing')
        finally:
            if acquired:
                self.pair_lock.release()

    def _session(self, conn):
        # This session thread owns every TLS read and write.
        pong = None
        sequence = 0
        filling = False
        due = 0.0
        with self.connections_lock:
            identity = self.active_device if self.active is conn else None
        def validate():
            if identity and not self.store.contains(identity):
                raise OSError('device revoked')
        class CheckedReader:
            def recv(self, size):
                validate()
                return conn.recv(size)
            def gettimeout(self):
                return conn.gettimeout() if hasattr(conn, 'gettimeout') else None
            def settimeout(self, timeout):
                conn.settimeout(timeout)
        reader = CheckedReader()
        try:
            while not self.stopped.is_set():
                validate()
                if pong is not None:
                    frame = pong
                    pong = None
                else:
                    # Fill only after a real 100 ms capture gap (PipeWire may
                    # deliver ~21 ms quanta, so shorter gaps are normal and
                    # filling them would add surplus frames); then keep 10 ms pace.
                    # While filling, pace by due time, not by sleeping 10 ms:
                    # Windows timers wake ~15.6 ms late, which would send only
                    # ~64 frames/s. Overdue silent frames go out back to back.
                    if filling:
                        captured = self.queue.get(max(0.0, due - self.clock()))
                    else:
                        captured = self.queue.get(.1)
                        if captured is None:
                            due = self.clock()
                    filling = captured is None
                    if filling:
                        due += .01
                    timestamp, pcm = captured if captured is not None else (
                        int(self.clock() * 1000000), SILENCE)
                    frame = Frame(AUDIO, sequence, timestamp, pcm)
                    sequence = (sequence + 1) & 0xffffffff
                if frame is not None:
                    conn.settimeout(1)
                    started = self.clock()
                    conn.sendall(encode(frame))
                    if self.clock() - started > .03:
                        self.queue.clear()
                while conn.pending() or select.select([conn], [], [], 0)[0]:
                    validate()
                    conn.settimeout(2)
                    frame = read_frame(reader)
                    if frame is None or frame.type == BYE:
                        return
                    if frame.type == PING:
                        pong = Frame(PONG, frame.sequence,
                                     int(self.clock() * 1000000),
                                     struct.pack('!Q', frame.timestamp))
                        # Send this reply before reading another PING.
                        break
                    if frame.type == STATS:
                        self.last_stats = json.loads(frame.payload)
                        logging.info('receiver stats: %s', self.last_stats)
        except OSError:
            # A partially written TLS frame cannot be skipped on this stream.
            self.queue.clear()

    def stop(self):
        self.stopped.set()
        self.capture.stop()
        self.listener.close()
        with self.connections_lock:
            connections = list(self.connections)
        for conn in connections:
            try:
                conn.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        for thread in self.threads:
            thread.join(2)
