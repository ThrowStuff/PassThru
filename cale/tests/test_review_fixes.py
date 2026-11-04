# SPDX-License-Identifier: GPL-3.0-only
import io
import socket
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch, Mock
from cale import protocol as p
from cale.__main__ import confirm
from cale.pairing import DeviceStore
from cale.sender import Sender, FrameQueue
from cale.capture import ToneCapture


class ReviewFixTests(unittest.TestCase):
    def test_interleaved_store_transactions(self):
        with tempfile.TemporaryDirectory() as path:
            first = DeviceStore(path)
            second = DeviceStore(path)
            old = first.add(b'a' * 32)
            reading, release, revoked = threading.Event(), threading.Event(), threading.Event()
            original = first.list
            def paused():
                snapshot = original()
                reading.set()
                self.assertTrue(release.wait(2))
                return snapshot
            with patch.object(first, 'list', paused):
                adding = threading.Thread(target=lambda: first.add(b'b' * 32))
                removing = threading.Thread(target=lambda: (second.revoke(old['id']), revoked.set()))
                adding.start()
                self.assertTrue(reading.wait(2))
                removing.start()
                self.assertFalse(revoked.wait(.05))
                release.set()
                adding.join(2); removing.join(2)
            self.assertTrue(revoked.is_set())
            self.assertIsNone(first.authenticate(b'a' * 32))
            self.assertIsNotNone(first.authenticate(b'b' * 32))
            self.assertFalse(list(first.path.glob('devices-*.tmp')))

    def test_timed_console(self):
        self.assertTrue(confirm('123456', io.StringIO('y\n')))
        self.assertFalse(confirm('123456', io.StringIO('n\n')))
        release = threading.Event()
        class Stdin:
            def readline(self):
                release.wait()
                return 'y\n'
        try:
            self.assertFalse(confirm('123456', Stdin(), timeout=.01))
        finally:
            release.set()

    def test_absolute_frame_deadline(self):
        for payload in (b'', b'abc'):
            wire = p.encode(p.Frame(p.STATS, payload=payload))
            now = [0.0]
            class Drip:
                def __init__(self): self.source = io.BytesIO(wire); self.budgets = []
                def recv(self, size):
                    now[0] += (.001 if self.source.tell() < 20 else .8) if payload else .3
                    return self.source.read(1)
                def settimeout(self, timeout): self.budgets.append(timeout)
                def gettimeout(self): return 2
            stream = Drip()
            with self.assertRaises(TimeoutError):
                p.read_frame(stream, clock=lambda: now[0])
            self.assertLess(now[0], 3)
            self.assertLess(stream.budgets[-2], stream.budgets[0])

    def test_revoke_between_authorization_and_registration(self):
        with tempfile.TemporaryDirectory() as path:
            store = DeviceStore(path)
            device = store.add(b'k' * 32)
            sender = Sender.__new__(Sender)
            sender.connections_lock = threading.Lock()
            sender.connections = set()
            sender.store = store
            sender.active = None
            sender.slots = threading.BoundedSemaphore(1)
            sender.slots.acquire()
            raw, conn = Mock(), Mock()
            sender.context = Mock()
            sender.context.wrap_socket.return_value = conn
            def authorize(connection, ip):
                # Authentication succeeded, then GUI revoke wins registration's lock.
                sender.revoke_device(device['id'])
                return device['id']
            sender._authorize = authorize
            sender._session = Mock()
            sender._handle(raw, '127.0.0.1')
            sender._session.assert_not_called()
            self.assertIsNone(sender.active)
            conn.close.assert_called_once()

    def test_session_reloads_external_revocation(self):
        with tempfile.TemporaryDirectory() as path:
            store = DeviceStore(path)
            second = DeviceStore(path)
            device = store.add(b'k' * 32)
            self.assertTrue(store.contains(device['id']))
            sender = Sender.__new__(Sender)
            sender.connections_lock = threading.Lock()
            sender.store = store
            sender.active_device = device['id']
            sender.stopped = threading.Event()
            sender.clock = time.monotonic
            sender.queue = FrameQueue()
            sender.queue.put((0, b'0' * 1920))
            conn = Mock()
            sender.active = conn
            conn.pending.return_value = 0
            conn.sendall.side_effect = lambda data: second.revoke(device['id'])
            with patch('cale.sender.select.select', return_value=([], [], [])):
                sender._session(conn)
            conn.sendall.assert_called_once()
            self.assertFalse(store.contains(device['id']))

    def test_cli_revoke_ends_tls_session(self):
        with tempfile.TemporaryDirectory() as path:
            store = DeviceStore(path)
            key = b'z' * 32
            device = store.add(key)
            try:
                sender = Sender(ToneCapture(), store.cert, store.key, host='127.0.0.1', store=store)
            except PermissionError as error:
                self.skipTest(f'loopback sockets blocked: {error}')
            sender.start()
            try:
                context = ssl._create_unverified_context()
                with context.wrap_socket(socket.create_connection(('127.0.0.1', sender.port)), server_hostname='localhost') as client:
                    client.settimeout(3)
                    client.sendall(p.encode(p.Frame(p.AUTH, payload=key)))
                    self.assertEqual(p.read_frame(client).type, p.AUTH_OK)
                    self.assertEqual(p.read_frame(client).type, p.AUDIO)
                    DeviceStore(path).revoke(device['id'])
                    started = time.monotonic()
                    while p.read_frame(client) is not None:
                        self.assertLess(time.monotonic() - started, 3)
                    self.assertLess(time.monotonic() - started, 3)
            finally:
                sender.stop()
