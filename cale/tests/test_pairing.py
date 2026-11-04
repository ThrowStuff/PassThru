# SPDX-License-Identifier: GPL-3.0-only
import sys
import hashlib
import io
import json
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch, Mock
from cale import protocol as p
from cale.capture import ToneCapture
from cale.pairing import DeviceStore, RateLimiter, code, pair_key_payload
from cale.sender import Sender, FrameQueue
from cale.mdns import Advertisement

# Shared with PairingCryptoTest: raw fp/nc/ns, not ASCII hex.
FP = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'
NC = '202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f'
NS = '404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f'


class PairingTests(unittest.TestCase):
    def test_pair_key_hostname(self):
        key=bytes(range(32))
        for name,expected in [('desktop','desktop'), ('a'*63+'é','a'*63), ('界'*22,'界'*21), ('','')]:
            payload=pair_key_payload(key,name)
            self.assertEqual(payload[:32],key)
            self.assertEqual(payload[32:].decode('utf-8'),expected)
            self.assertLessEqual(len(payload),96)
        with self.assertRaises(ValueError): pair_key_payload(b'', 'desktop')

    def test_golden(self):
        self.assertEqual(code(*(bytes.fromhex(x) for x in (FP, NC, NS))), '784550')
        self.assertEqual([getattr(p,n) for n in ('PAIR_COMMIT','PAIR_NONCE','PAIR_REVEAL',
            'PAIR_CONFIRM','PAIR_KEY','AUTH','AUTH_OK','AUTH_FAIL')], list(range(6,14)))
        self.assertEqual(p.PAIR_FAIL,13)

    def test_store(self):
        with tempfile.TemporaryDirectory() as path:
            store=DeviceStore(path)
            # POSIX modes only; on Windows the store lives in the per-user profile.
            if sys.platform != 'win32':
                for name in ('cert.pem','key.pem','devices.json'):
                    self.assertEqual((Path(path)/name).stat().st_mode & 0o777,0o600)
            key=bytes(range(32)); device=store.add(key)
            self.assertNotIn(key.hex(),store.file.read_text())
            self.assertEqual(store.list()[0]['key_sha256'],hashlib.sha256(key).hexdigest())
            self.assertEqual(DeviceStore(path).authenticate(key), device['id'])
            self.assertIsNone(store.authenticate(b'x'*32))
            store.revoke(device['id']); self.assertIsNone(store.authenticate(key))

    def test_limiter(self):
        now=[0]; limiter=RateLimiter(lambda:now[0])
        for _ in range(2): limiter.fail('a')
        self.assertFalse(limiter.blocked('a'))
        limiter.fail('a'); self.assertTrue(limiter.blocked('a'))
        self.assertFalse(limiter.blocked('b'))
        now[0]=60; self.assertFalse(limiter.blocked('a'))
        limiter.fail('a'); self.assertFalse(limiter.blocked('a'))

    def test_mdns(self):
        with patch.dict('sys.modules', {'zeroconf': None}), \
                patch('cale.mdns.subprocess.Popen') as spawn, patch('cale.mdns.sys.platform','linux'):
            ad=Advertisement(42,'a'*64,'host'); ad.start(); ad.stop()
            self.assertEqual(spawn.call_args.args[0],['avahi-publish-service','host','_passthru._tcp','42','fp='+'a'*16,'name=host'])
            spawn.return_value.terminate.assert_called_once()
            spawn.return_value.wait.assert_called_once_with(timeout=2)
        with patch.dict('sys.modules', {'zeroconf': None}), \
                patch('cale.mdns.sys.platform', 'linux'), \
                patch('cale.mdns.subprocess.Popen',side_effect=FileNotFoundError), self.assertLogs(level='WARNING'):
            Advertisement(42,'a'*64).start()


class AuthorizationTests(unittest.TestCase):
    def authorize(self, frames, store=None, locked=False, confirm=None):
        server=Sender.__new__(Sender)
        server.stopped=threading.Event(); server.limiter=RateLimiter(); server.queue=FrameQueue()
        server.pair_lock=threading.Lock(); server.fp=bytes.fromhex(FP)
        server.store=store or Mock(); server.confirm=confirm or (lambda c:True)
        if locked: server.pair_lock.acquire()
        stream=io.BytesIO(b''.join(p.encode(f) for f in frames)); sent=[]
        class Connection:
            def settimeout(self,t): pass
            def pending(self): return len(stream.getvalue())-stream.tell()
            def recv(self,size): return stream.read(size)
            def sendall(self,data): sent.append(p.decode(data))
        with patch('cale.sender.select.select', return_value=([1],[],[])):
            result=server._authorize(Connection(),'ip')
        return result,sent,server

    def test_pairing_handoff(self):
        owner=threading.get_ident()
        released=threading.Event()
        server=Sender.__new__(Sender)
        server.stopped=threading.Event(); server.limiter=RateLimiter(); server.queue=FrameQueue()
        server.pair_lock=threading.Lock(); server.fp=bytes.fromhex(FP)
        server.store=Mock(); server.store.add.return_value = {'id': 'device-x'}; seen=[]; sent=[]
        nc=bytes.fromhex(NC)
        stream=io.BytesIO(b''.join(p.encode(f) for f in [
            p.Frame(p.PAIR_COMMIT,payload=hashlib.sha256(nc).digest()),
            p.Frame(p.PAIR_REVEAL,payload=nc),p.Frame(p.PAIR_CONFIRM,payload=b'\x01')]))
        def confirm(value):
            self.assertNotEqual(threading.get_ident(),owner)
            seen.append(value)
            # Session must consume the remote confirmation while this waits.
            return released.wait(2)
        server.confirm=confirm
        class Connection:
            def settimeout(_,t): self.assertEqual(threading.get_ident(),owner)
            def pending(_): return len(stream.getvalue())-stream.tell()
            def recv(_,size):
                self.assertEqual(threading.get_ident(),owner)
                value=stream.read(size)
                if stream.tell()==len(stream.getvalue()): released.set()
                return value
            def sendall(_,data):
                self.assertEqual(threading.get_ident(),owner); sent.append(p.decode(data))
        # No socket required. Yield to the callback only once all input is read.
        def ready(*args):
            server.stopped.wait(.001)
            return ([],[],[])
        with patch('cale.sender.select.select',side_effect=ready):
            self.assertTrue(server._authorize(Connection(),'ip'))
        self.assertEqual([f.type for f in sent],[p.PAIR_NONCE,p.PAIR_KEY])
        self.assertEqual(seen,[code(server.fp,nc,sent[0].payload)])
        self.assertEqual(sent[-1].payload[32:].decode(), socket.gethostname().encode()[:64].decode(errors="ignore"))
        server.store.add.assert_called_once_with(sent[-1].payload[:32])
        self.assertFalse(server.pair_lock.locked())

    def test_commit_mismatch(self):
        result,sent,_=self.authorize([p.Frame(p.PAIR_COMMIT,payload=b'x'*32),p.Frame(p.PAIR_REVEAL,payload=b'y'*32)])
        self.assertFalse(result); self.assertEqual(sent[-1].type,p.PAIR_FAIL)
        self.assertEqual(sent[-1].payload,b'commit mismatch')

    def test_auth(self):
        for success in ('device-x', None):
            store=Mock(); store.authenticate.return_value=success
            result,sent,_=self.authorize([p.Frame(p.AUTH,payload=b'k'*32)],store)
            self.assertEqual(result,success if success is not None else False)
            self.assertEqual(sent[0].type,p.AUTH_OK if success else p.AUTH_FAIL)

    def test_rejected_confirmation_never_creates_key(self):
        nc=bytes.fromhex(NC)
        for local,remote in [(False,b'\x01'),(True,b'\x00')]:
            result,sent,server=self.authorize([
                p.Frame(p.PAIR_COMMIT,payload=hashlib.sha256(nc).digest()),
                p.Frame(p.PAIR_REVEAL,payload=nc),p.Frame(p.PAIR_CONFIRM,payload=remote)],
                confirm=lambda c:local)
            self.assertFalse(result)
            self.assertEqual(sent[-1].type,p.PAIR_FAIL)
            server.store.add.assert_not_called()

    def test_initial_deadline_counts_as_failure(self):
        with patch('cale.sender.time.monotonic',side_effect=[0,6]):
            result,sent,server=self.authorize([])
        self.assertFalse(result); self.assertEqual(sent[-1].type,p.AUTH_FAIL)
        self.assertEqual(len(server.limiter.failures['ip']),1)

    def test_first_frame_and_busy(self):
        for frame,locked,reason in [(p.Frame(p.AUDIO),False,b'expected AUTH or PAIR_COMMIT'),
                (p.Frame(p.PAIR_COMMIT,payload=b'x'*32),True,b'busy')]:
            result,sent,_=self.authorize([frame],locked=locked)
            self.assertFalse(result); self.assertEqual(sent[-1].payload,reason)


class LoopbackTests(unittest.TestCase):
    def setUp(self):
        try:
            probe=socket.socket(); probe.bind(('127.0.0.1',0)); probe.close()
        except OSError as e: self.skipTest('loopback unavailable: '+str(e))
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.store=DeviceStore(self.temp.name)
        self.codes=[]
        self.server=Sender(ToneCapture(),self.store.cert,self.store.key,host='127.0.0.1',
            store=self.store,confirm=lambda value:self.codes.append(value) or True)
        self.server.start(); self.addCleanup(self.server.stop)

    def connect(self):
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname=False; context.verify_mode=ssl.CERT_NONE
        context.minimum_version=ssl.TLSVersion.TLSv1_3
        conn=context.wrap_socket(socket.create_connection(('127.0.0.1',self.server.port)),server_hostname='localhost')
        conn.settimeout(3); return conn

    def test_no_audio_before_auth(self):
        with self.connect() as conn:
            conn.settimeout(1)
            with self.assertRaises(socket.timeout): p.read_frame(conn)

    def test_pair_and_reconnect(self):
        nc=bytes.fromhex(NC)
        with self.connect() as conn:
            fp=hashlib.sha256(conn.getpeercert(True)).digest()
            conn.sendall(p.encode(p.Frame(p.PAIR_COMMIT,payload=hashlib.sha256(nc).digest())))
            nonce=p.read_frame(conn); self.assertEqual(nonce.type,p.PAIR_NONCE)
            conn.sendall(p.encode(p.Frame(p.PAIR_REVEAL,payload=nc)))
            conn.sendall(p.encode(p.Frame(p.PAIR_CONFIRM,payload=b'\x01')))
            key=p.read_frame(conn); self.assertEqual(key.type,p.PAIR_KEY)
            self.assertEqual(self.codes,[code(fp,nc,nonce.payload)])
            self.assertEqual(p.read_frame(conn).type,p.AUDIO)
            conn.sendall(p.encode(p.Frame(p.BYE)))
        with self.connect() as conn:
            conn.sendall(p.encode(p.Frame(p.AUTH,payload=key.payload[:32])))
            self.assertEqual(p.read_frame(conn).type,p.AUTH_OK)
            self.assertEqual(p.read_frame(conn).type,p.AUDIO)
        with self.connect() as conn:
            conn.sendall(p.encode(p.Frame(p.AUTH,payload=b'?'*32)))
            self.assertEqual(p.read_frame(conn).type,p.AUTH_FAIL)
            self.assertIsNone(p.read_frame(conn))


    def test_revoke_disconnects_active_device(self):
        key = b'x' * 32
        device = self.store.add(key)
        with self.connect() as conn:
            conn.sendall(p.encode(p.Frame(p.AUTH, payload=key)))
            self.assertEqual(p.read_frame(conn).type, p.AUTH_OK)
            self.assertEqual(p.read_frame(conn).type, p.AUDIO)
            self.assertEqual(self.server.active_device, device['id'])
            self.server.disconnect_device('another-device')
            self.assertEqual(p.read_frame(conn).type, p.AUDIO)
            self.store.revoke(device['id'])
            started = time.monotonic()
            self.server.disconnect_device(device['id'])
            conn.settimeout(2)
            while p.read_frame(conn) is not None:
                self.assertLess(time.monotonic() - started, 2)
            self.assertLess(time.monotonic() - started, 2)
        with self.connect() as conn:
            conn.sendall(p.encode(p.Frame(p.AUTH, payload=key)))
            self.assertEqual(p.read_frame(conn).type, p.AUTH_FAIL)

    def test_newest_session_replaces_older(self):
        key=b'k'*32; self.store.add(key)
        first=self.connect()
        first.sendall(p.encode(p.Frame(p.AUTH,payload=key)))
        self.assertEqual(p.read_frame(first).type,p.AUTH_OK)
        with self.connect() as second:
            second.sendall(p.encode(p.Frame(p.AUTH,payload=key)))
            self.assertEqual(p.read_frame(second).type,p.AUTH_OK)
            # The older session is closed, so frames are never split.
            first.settimeout(3)
            try:
                while p.read_frame(first) is not None: pass
            except (OSError, p.ProtocolError): pass
            sequences=[p.read_frame(second).sequence for _ in range(30)]
            self.assertEqual(sequences, list(range(sequences[0], sequences[0]+30)))
        first.close()


if __name__=='__main__': unittest.main()


class SessionStateTests(unittest.TestCase):
    def test_capture_termination(self):
        class Capture:
            def __iter__(self):
                return iter(())
        class BrokenCapture:
            def __iter__(self):
                raise RuntimeError('capture failed')
        for capture, reason in [(Capture(), 'capture iterator ended'),
                                (BrokenCapture(), 'capture failed')]:
            server = Sender.__new__(Sender)
            server.capture = capture
            server.stopped = threading.Event()
            server.capture_error = None
            server._capture()
            self.assertEqual(server.capture_error, reason)
            server.capture_error = None
            server.stopped.set()
            server._capture()
            self.assertIsNone(server.capture_error)

    def test_connected_phone_snapshot(self):
        server = Sender.__new__(Sender)
        server.connections_lock = threading.Lock()
        server.active = None
        self.assertIsNone(server.connected_phone)
        server.active = Mock()
        server.active.getpeername.return_value = ('192.0.2.10', 1234)
        self.assertEqual(server.connected_phone, '192.0.2.10')
        server.active.getpeername.side_effect = OSError('closed')
        self.assertIsNone(server.connected_phone)
