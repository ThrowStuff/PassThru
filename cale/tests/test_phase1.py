# SPDX-License-Identifier: GPL-3.0-only
import importlib.util
import io
from pathlib import Path
import shutil
import socket
import ssl
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch, Mock

from cale import protocol as p
from cale.capture import ToneCapture, SILENCE, pipewire_command
from cale.jitter import JitterBuffer
from cale.sender import FrameQueue, Sender, generate_certificate, fingerprint

ROOT = Path(__file__).resolve().parents[2]


class Clock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class ProtocolTests(unittest.TestCase):
    def test_wire_and_roundtrip(self):
        for kind in [1, 2, 3, 4, 5, 199]:
            frame = p.Frame(kind, 0x12345678, 0x123456789abcdef0, b'abc')
            wire = p.encode(frame)
            self.assertEqual(wire[:4], bytes([0x50, 0x54, 2, kind]))
            self.assertEqual(wire[4:8], b'\x12\x34\x56\x78')
            self.assertEqual(len(wire), 23)
            self.assertEqual(p.decode(wire), frame)
        for payload in [b'', bytes(65536)]:
            frame = p.Frame(p.STATS, payload=payload)
            self.assertEqual(p.decode(p.encode(frame)), frame)

    def test_rejections(self):
        valid = p.encode(p.Frame(p.AUDIO, payload=b'abc'))
        cases = [b'', valid[:19], valid[:-1], valid + b'x']
        for offset, value in [(0, b'XX'), (2, b'\x01'),
                              (16, struct.pack('!I', 65537))]:
            cases.append(valid[:offset] + value + valid[offset + len(value):])
        for data in cases:
            with self.subTest(data=data):
                with self.assertRaises(p.ProtocolError):
                    p.decode(data)
        for frame in [p.Frame(1, payload=bytes(65537)), p.Frame(256),
                      p.Frame(1, -1), p.Frame(1, timestamp=2**64)]:
            with self.assertRaises(p.ProtocolError):
                p.encode(frame)

    def test_partial_and_eof(self):
        class Partial:
            def __init__(self, data):
                self.source = io.BytesIO(data)

            def recv(self, size):
                return self.source.read(min(3, size))
        frame = p.Frame(p.AUDIO, 42, 91, bytes(1920))
        wire = p.encode(frame)
        stream = Partial(wire * 2)
        self.assertEqual(p.read_frame(stream), frame)
        self.assertEqual(p.read_frame(stream), frame)
        self.assertIsNone(p.read_frame(stream))
        for end in [1, 19, 20, len(wire) - 1]:
            with self.assertRaises(p.ProtocolError):
                p.read_frame(Partial(wire[:end]))
        for offset, value in [(0, b'XX'), (2, b'\x03'),
                              (16, struct.pack('!I', 65537))]:
            bad = wire[:offset] + value + wire[offset + len(value):]
            with self.assertRaises(p.ProtocolError):
                p.read_frame(Partial(bad))


class BufferTests(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.buffer = JitterBuffer(self.clock.monotonic)
        # Steady-state tests start after the initial (uncounted) prefill.
        self.buffer.prefilling = False

    def test_warmup_underrun_does_not_teach_target(self):
        buffer = JitterBuffer(self.clock.monotonic)
        for name in b'abc':
            buffer.push(bytes([name]) * 1920)
        for _ in range(4):            # ends prefill at t=0, then drains to empty
            buffer.pop()
        self.assertEqual(buffer.pop(), SILENCE)   # underrun inside warm-up
        for name in b'def':
            buffer.push(bytes([name]) * 1920)
        buffer.pop()
        self.assertEqual((buffer.underruns, buffer.target_ms), (1, 30))
        self.clock.now = 5                          # after warm-up: learns again
        while buffer.occupancy_ms:
            buffer.pop()
        buffer.pop()
        for name in b'ghi':
            buffer.push(bytes([name]) * 1920)
        buffer.pop()
        self.assertEqual((buffer.underruns, buffer.target_ms), (2, 50))

    def test_initial_prefill_is_not_an_underrun(self):
        buffer = JitterBuffer(self.clock.monotonic)
        for _ in range(5):
            self.assertEqual(buffer.pop(), SILENCE)
        buffer.push(b'a' * 1920); buffer.push(b'b' * 1920)
        self.assertEqual(buffer.pop(), SILENCE)  # still below 30 ms target
        buffer.push(b'c' * 1920)
        self.assertEqual(buffer.pop(), b'a' * 1920)
        self.assertEqual((buffer.underruns, buffer.target_ms), (0, 30))

    def fill(self, n):
        for i in range(n):
            self.buffer.push(bytes([i]) * 1920)

    def test_overflow_and_order(self):
        self.fill(8)
        self.assertEqual(self.buffer.occupancy_ms, 80)
        self.buffer.push(b'z' * 1920)
        self.assertEqual(self.buffer.occupancy_ms, 30)
        self.assertEqual(self.buffer.overflow_drops, 6)
        self.assertEqual(self.buffer.pop(), bytes([6]) * 1920)
        with self.assertRaises(ValueError):
            self.buffer.push(b'bad')

    def test_underrun(self):
        self.assertEqual(self.buffer.pop(), SILENCE)
        self.assertEqual(self.buffer.underruns, 1)

    def test_rebuffer(self):
        self.fill(3)
        for _ in range(3):
            self.buffer.pop()
        self.assertEqual(self.buffer.pop(), SILENCE)
        self.fill(2)
        self.assertEqual(self.buffer.pop(), SILENCE)
        self.assertEqual(self.buffer.occupancy_ms, 20)
        self.assertEqual(self.buffer.underruns, 1)
        self.buffer.push(b'z' * 1920)
        self.buffer.pop()
        self.assertEqual(self.buffer.occupancy_ms, 20)
        self.assertFalse(self.buffer.rebuffering)
        self.assertEqual(self.buffer.target_ms, 60)

    def underrun_event(self, silent_pops=1):
        while self.buffer.occupancy_ms:
            self.buffer.pop()
        initial = self.buffer.target_ms
        for _ in range(silent_pops):
            self.assertEqual(self.buffer.pop(), SILENCE)
            self.assertEqual(self.buffer.target_ms, initial)
        self.fill(initial // 10)
        self.buffer.pop()

    def test_target_raise_and_max(self):
        self.assertEqual(self.buffer.target_ms, 30)
        for silent_pops, expected in [(1, 50), (2, 80), (1, 100), (12, 100)]:
            count = self.buffer.underruns
            self.underrun_event(silent_pops)
            self.assertEqual(self.buffer.target_ms, expected)
            self.assertEqual(self.buffer.underruns, count + 1)
            self.assertFalse(self.buffer.rebuffering)

    def test_target_decay_and_min(self):
        self.underrun_event()
        self.clock.now = 29
        self.underrun_event()  # New episode resets the quiet interval; target 70.
        # One 10 ms step per quiet 30 s window while occupancy stays >= 30 ms.
        for moment, expected in [(30, 70), (58.999, 70), (59, 60), (88.999, 60),
                                 (89, 50), (119, 40), (149, 30), (359, 30)]:
            self.clock.now = moment
            self.fill(4)
            self.buffer.pop()
            self.assertEqual(self.buffer.target_ms, expected)

    def test_near_miss_blocks_decay(self):
        self.underrun_event()
        self.clock.now = 29
        self.underrun_event()  # target 70
        self.clock.now = 40
        self.fill(4)
        while self.buffer.occupancy_ms > 20:
            self.buffer.pop()
        self.buffer.pop()  # observed 20 ms: less than 30 ms headroom
        self.clock.now = 59
        self.fill(4)
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 70)
        self.clock.now = 89  # next window had headroom
        self.fill(4)
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 60)

    def test_no_decay_during_rebuffer(self):
        self.underrun_event()
        while self.buffer.occupancy_ms:
            self.buffer.pop()
        self.buffer.pop()
        self.clock.now = 90
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 50)
        self.fill(5)
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 80)
        self.clock.now = 119.999
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 80)
        self.clock.now = 120
        self.buffer.pop()
        self.assertEqual(self.buffer.target_ms, 70)

    def test_adaptive_overflow_cap(self):
        for target in [50, 70, 90, 100]:
            self.underrun_event()
            self.assertEqual(self.buffer.target_ms, target)
            while self.buffer.occupancy_ms:
                self.buffer.pop()
            self.fill((target + 50) // 10)
            self.assertEqual(self.buffer.occupancy_ms, target + 50)
            drops = self.buffer.overflow_drops
            self.buffer.push(b'z' * 1920)
            self.assertEqual(self.buffer.occupancy_ms, target)
            self.assertEqual(self.buffer.overflow_drops - drops, 6)

    def test_adaptive_drift_thresholds(self):
        self.underrun_event()  # target 50
        self.fill(2)
        self.clock.now = 1
        self.buffer.ema = 61
        self.buffer.pop()
        self.assertEqual(self.buffer.drift_drops, 1)
        self.clock.now = 2
        self.buffer.ema = 39
        occupancy = self.buffer.occupancy_ms
        self.buffer.pop()
        self.assertEqual(self.buffer.drift_repeats, 1)
        self.assertEqual(self.buffer.occupancy_ms, occupancy)

    def test_ema_and_correction_interval(self):
        self.fill(6)
        self.clock.now = 1
        self.buffer.pop()  # Instantaneous 60 ms must not trigger a drop.
        self.assertAlmostEqual(self.buffer.ema, 30.3)
        self.assertEqual(self.buffer.drift_drops, 0)
        self.buffer.ema = 41
        self.buffer.pop()
        self.assertEqual(self.buffer.drift_drops, 1)
        self.assertEqual(self.buffer.overflow_drops, 0)
        self.buffer.ema = 41
        self.clock.now = 1.999
        self.buffer.pop()
        self.assertEqual(self.buffer.drift_drops, 1)
        self.buffer.push(SILENCE)
        self.buffer.ema = 19
        self.clock.now = 2
        occupancy = self.buffer.occupancy_ms
        self.buffer.pop()
        self.assertEqual(self.buffer.drift_repeats, 1)
        self.assertEqual(self.buffer.occupancy_ms, occupancy)
        self.assertEqual(self.buffer.underruns, 0)

    def test_ema_thresholds(self):
        for occupancy, ema in [(40, 40), (20, 20)]:
            with self.subTest(ema=ema):
                buffer = JitterBuffer(self.clock.monotonic)
                buffer.rebuffering = False
                for _ in range(occupancy // 10):
                    buffer.push(SILENCE)
                buffer.ema = ema
                self.clock.now += 2
                buffer.pop()
                self.assertEqual(buffer.drift_drops + buffer.drift_repeats, 0)

    def test_pipewire_command(self):
        expected = ['pw-cat', '--record', '--raw', '--rate', '48000',
                    '--channels', '2', '--format', 's16', '--latency', '10ms',
                    '--properties', '{"stream.capture.sink": true}']
        self.assertEqual(pipewire_command(), expected + ['-'])
        self.assertEqual(pipewire_command('sink.name'),
                         expected + ['--target', 'sink.name', '-'])

    def test_queue(self):
        queue = FrameQueue()
        for value in range(5):
            queue.put(value)
        self.assertEqual([queue.get() for _ in range(3)], [2, 3, 4])
        self.assertEqual(queue.drops, 2)


    def test_stalled_write_clears_queue(self):
        # Exercise the session without creating sockets or using a real clock.
        class StalledConnection:
            def settimeout(self, timeout):
                assert timeout == 1

            def pending(self):
                return 0

            def sendall(self, data):
                raise socket.timeout('1 s write budget exhausted')

            def shutdown(self, how):
                pass

        server = Sender.__new__(Sender)
        server.connections_lock = threading.Lock()
        server.active = None
        server.active_device = None
        server.store = Mock()
        server.stopped = threading.Event()
        server.clock = lambda: 0
        server.queue = FrameQueue()
        for sequence in range(3):
            server.queue.put((0, SILENCE))
        with patch('select.select', return_value=([], [], [])):
            server._session(StalledConnection())
        self.assertEqual(len(server.queue.frames), 0)
        self.assertEqual(server.queue.drops, 2)

    def test_session_owns_io_and_control_timeouts(self):
        owner = threading.get_ident()
        incoming = io.BytesIO(p.encode(p.Frame(p.PING, 7, 123)) +
                              p.encode(p.Frame(p.BYE)))
        sent = []
        timeouts = []

        class Connection:
            def settimeout(self, timeout):
                self.timeout = timeout
                timeouts.append(timeout)

            def pending(self):
                self.assert_owner()
                return len(incoming.getvalue()) - incoming.tell()

            def assert_owner(self):
                if threading.get_ident() != owner:
                    raise AssertionError('TLS I/O changed threads')

            def recv(self, size):
                self.assert_owner()
                if not 0 < self.timeout <= 2:
                    raise AssertionError('read timeout must be 2 seconds')
                return incoming.read(min(size, 3))

            def sendall(self, data):
                self.assert_owner()
                if self.timeout != 1:
                    raise AssertionError('write timeout must be 1 second')
                sent.append(p.decode(data))

        server = Sender.__new__(Sender)
        server.connections_lock = threading.Lock()
        server.active = None
        server.active_device = None
        server.store = Mock()
        server.stopped = threading.Event()
        server.queue = FrameQueue()
        server.queue.put((0, SILENCE))
        server.clock = lambda: 12.5
        server._session(Connection())
        self.assertEqual(sent, [p.Frame(p.AUDIO, payload=SILENCE),
                                p.Frame(p.PONG, 7, 12500000,
                                        struct.pack('!Q', 123))])
        self.assertEqual(timeouts.count(1), 2)
        self.assertTrue(all(t is None or 0 < t <= 2 for t in timeouts))

    def test_silence_fill_until_capture_resumes(self):
        clock = Clock()
        server = Sender.__new__(Sender)
        server.connections_lock = threading.Lock()
        server.active = None
        server.active_device = None
        server.store = Mock()
        server.stopped = threading.Event()
        server.clock = clock.monotonic
        server.queue = FrameQueue()
        real = b'\x01\x02' * 960

        def capture():
            self.assertAlmostEqual(clock.now, .2)
            yield real
            yield real

        server.capture = capture()
        sent = []

        def wait_for(predicate, timeout):
            if not predicate():
                clock.now = round(min(.2, clock.now + timeout), 6)
                if clock.now == .2:
                    server._capture()
            return bool(predicate())

        class Connection:
            def settimeout(self, timeout):
                pass

            def pending(self):
                return 0

            def sendall(self, data):
                sent.append(p.decode(data))
                if len(sent) == 12:
                    server.stopped.set()

        # Advance the capture gap with simulated time, without audio or sleeps.
        with patch.object(server.queue.condition, 'wait_for', side_effect=wait_for), \
                patch('select.select', return_value=([], [], [])):
            server._session(Connection())
        self.assertEqual([f.type for f in sent], [p.AUDIO] * 12)
        self.assertEqual([f.sequence for f in sent], list(range(12)))
        self.assertEqual([f.payload for f in sent], [SILENCE] * 10 + [real] * 2)
        self.assertEqual([f.timestamp for f in sent],
                         [100000 + 10000 * i for i in range(10)] + [200000, 200000])

    def test_handshake_timeout_and_send_buffer(self):
        from unittest.mock import Mock
        server = Sender.__new__(Sender)
        server.connections_lock = threading.Lock()
        server.active = None
        server.active_device = None
        server.store = Mock()
        server.stopped = threading.Event()
        server.slots = threading.BoundedSemaphore(8)
        server.active = None
        server.connections, server.connections_lock = set(), threading.Lock()
        raw, conn = Mock(), Mock()
        server.listener = Mock()
        server.listener.accept.return_value = (raw, ('127.0.0.1', 1234))
        server.context = Mock()
        server.context.wrap_socket.return_value = conn
        server.threads = []
        server.connections = set()
        server.connections_lock = threading.Lock()
        server._authorize = Mock(return_value=True)
        server.queue = FrameQueue()
        server._session = Mock(side_effect=lambda connection: server.stopped.set())
        server._serve()
        for thread in server.threads:
            thread.join(2)
        raw.settimeout.assert_called_once_with(5)
        raw.setsockopt.assert_any_call(socket.SOL_SOCKET, socket.SO_SNDBUF, 4096)
        raw.setsockopt.assert_any_call(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        server.context.wrap_socket.assert_called_once_with(raw, server_side=True)
        server._session.assert_called_once_with(conn)
        conn.close.assert_called_once()

    def test_tone(self):
        clock = Clock()
        tone = ToneCapture(clock=clock)
        frames = iter(tone)
        first, second = next(frames), next(frames)
        self.assertEqual(len(first), 1920)
        self.assertEqual(clock.now, .01)
        samples = struct.unpack('<960h', first)
        self.assertEqual(samples[::2], samples[1::2])
        other = iter(ToneCapture(clock=Clock()))
        self.assertEqual((first, second), (next(other), next(other)))
        tone.stop()
        with self.assertRaises(StopIteration):
            next(frames)


@unittest.skipUnless(shutil.which('openssl'), 'openssl unavailable')
class TLSTests(unittest.TestCase):
    def setUp(self):
        try:
            probe = socket.socket()
        except PermissionError as error:
            self.skipTest(f'loopback sockets blocked by sandbox: {error}')
        else:
            probe.close()

    def test_receiver_pause_survives(self):
        tone = ToneCapture()
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            cert, key = Path(directory) / 'cert.pem', Path(directory) / 'key.pem'
            generate_certificate(cert, key)
            server = Sender(tone, cert, key, host='127.0.0.1')
            credential = b't' * 32
            server.store.add(credential)
            server.start()
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = ssl.TLSVersion.TLSv1_3
            try:
                with socket.create_connection(('127.0.0.1', server.port), timeout=3) as raw:
                    with context.wrap_socket(raw, server_hostname='localhost') as conn:
                        conn.settimeout(3)
                        conn.sendall(p.encode(p.Frame(p.AUTH, payload=credential)))
                        self.assertEqual(p.read_frame(conn).type, p.AUTH_OK)
                        first = p.read_frame(conn)
                        self.assertEqual(first.type, p.AUDIO)
                        for _ in range(4):
                            self.assertEqual(p.read_frame(conn).type, p.AUDIO)
                        # Stop reading for 300 ms: Cale's sends back up against
                        # its small send buffer. A sub-second write timeout
                        # would end the session here.
                        time.sleep(.3)
                        ping = p.encode(p.Frame(p.PING, 91, 1234))
                        # Pause both receipt and a started control frame. The old
                        # 30 ms socket timeout disconnected during this pause.
                        conn.sendall(ping[:1])
                        time.sleep(.3)
                        conn.sendall(ping[1:])
                        deadline = time.monotonic() + 3
                        while time.monotonic() < deadline:
                            frame = p.read_frame(conn)
                            self.assertIsNotNone(frame, 'session disconnected during pause')
                            if frame.type == p.PONG:
                                break
                        else:
                            self.fail('no PONG after receiver pause')
                        self.assertEqual(frame.sequence, 91)
                        self.assertEqual(frame.payload, struct.pack('!Q', 1234))
                        audio = p.read_frame(conn)
                        self.assertEqual(audio.type, p.AUDIO)
                        self.assertGreater(audio.sequence, first.sequence)
                        conn.sendall(p.encode(p.Frame(p.BYE)))
                        # Frames already in flight may arrive before EOF.
                        for _ in range(200):
                            frame = p.read_frame(conn)
                            if frame is None:
                                break
                            self.assertEqual(frame.type, p.AUDIO)
                        else:
                            self.fail('session did not end after BYE')
            finally:
                server.stop()
            self.assertTrue(all(not thread.is_alive() for thread in server.threads))

    def test_tone_sequence_ping_stats_bye(self):
        class GatedClock(Clock):
            def __init__(self):
                super().__init__()
                self.gate = threading.Semaphore(0)

            def sleep(self, seconds):
                if not self.gate.acquire(timeout=3):
                    raise RuntimeError('test failed to advance capture clock')
                super().sleep(seconds)

        clock = GatedClock()
        tone = ToneCapture(clock=clock)
        # Keep all temporary artifacts inside the repository.
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            cert, key = Path(directory) / 'cert.pem', Path(directory) / 'key.pem'
            generate_certificate(cert, key)
            server = Sender(tone, cert, key, host='127.0.0.1', clock=clock.monotonic)
            credential = b't' * 32
            server.store.add(credential)
            server.start()
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = ssl.TLSVersion.TLSv1_3
            try:
                with socket.create_connection(('127.0.0.1', server.port), timeout=3) as raw:
                    with context.wrap_socket(raw, server_hostname='localhost') as conn:
                        conn.settimeout(3)
                        conn.sendall(p.encode(p.Frame(p.AUTH, payload=credential)))
                        self.assertEqual(p.read_frame(conn).type, p.AUTH_OK)
                        import hashlib
                        self.assertEqual(hashlib.sha256(conn.getpeercert(True)).hexdigest(),
                                         fingerprint(cert))
                        self.assertEqual(conn.version(), 'TLSv1.3')
                        for sequence in range(8):
                            clock.gate.release()
                            frame = p.read_frame(conn)
                            self.assertEqual((frame.type, frame.sequence), (p.AUDIO, sequence))
                            self.assertEqual(len(frame.payload), 1920)
                            if sequence == 0:
                                initial_timestamp = frame.timestamp
                            self.assertAlmostEqual(frame.timestamp, initial_timestamp + sequence * 10000, delta=1)
                        conn.sendall(p.encode(p.Frame(p.PING, 76, 123456)))
                        # Idle capture now sends silence while waiting for control.
                        for _ in range(200):
                            pong = p.read_frame(conn)
                            self.assertIsNotNone(pong)
                            if pong.type == p.PONG:
                                break
                            self.assertEqual(pong.type, p.AUDIO)
                            self.assertEqual(pong.payload, SILENCE)
                        else:
                            self.fail('no PONG while capture is idle')
                        self.assertEqual(pong.sequence, 76)
                        self.assertEqual(pong.payload, struct.pack('!Q', 123456))
                        self.assertAlmostEqual(pong.timestamp, initial_timestamp + 70000, delta=1)
                        conn.sendall(p.encode(p.Frame(199)))
                        conn.sendall(p.encode(p.Frame(p.STATS, payload=b'{"underruns": 2}')))
                        conn.sendall(p.encode(p.Frame(p.BYE)))
                        # Frames already in flight may arrive before EOF.
                        for _ in range(200):
                            frame = p.read_frame(conn)
                            if frame is None:
                                break
                            self.assertEqual(frame.type, p.AUDIO)
                        else:
                            self.fail('session did not end after BYE')
                        self.assertEqual(server.last_stats, {'underruns': 2})
            finally:
                tone.stop()
                clock.gate.release()
                server.stop()
            self.assertTrue(all(not thread.is_alive() for thread in server.threads))


class SimulationTests(unittest.TestCase):
    def test_pass_criteria_boundaries(self):
        spec = importlib.util.spec_from_file_location('drift_sim', ROOT / 'tools/sim/drift_sim.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        result = dict(max_ms=80, max_true_ms=80, after5_underruns=0, after5_overflows=0,
                      drift_drops=150, drift_repeats=0, underruns=2, stalls=1)
        self.assertTrue(module.passes(result, False))
        for field, value in [('max_ms', 81), ('max_true_ms', 81), ('after5_underruns', 1),
                             ('after5_overflows', 1), ('drift_drops', 149),
                             ('drift_drops', 601)]:
            with self.subTest(field=field, value=value):
                self.assertFalse(module.passes(dict(result, **{field: value}), False))
        self.assertTrue(module.passes(dict(result, drift_drops=600), False))
        self.assertTrue(module.passes(dict(result, max_ms=100), True))
        self.assertFalse(module.passes(dict(result, max_ms=101), True))
        self.assertFalse(module.passes(dict(result, max_true_ms=101), True))
        self.assertFalse(module.passes(dict(result, underruns=3), True))

    def test_adaptive_pass_criteria_boundaries(self):
        spec = importlib.util.spec_from_file_location('drift_sim', ROOT / 'tools/sim/drift_sim.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        result = dict(max_ms=150, max_true_ms=150, spike_underruns=2,
                      late_spike_underruns=0, target_ms=30)
        self.assertTrue(module.passes_adaptive(result))
        for field, value in [('max_ms', 151), ('max_true_ms', 151), ('spike_underruns', 3),
                             ('late_spike_underruns', 1), ('target_ms', 40)]:
            with self.subTest(field=field):
                self.assertFalse(module.passes_adaptive(dict(result, **{field: value})))

    def test_both_clock_rates(self):
        spec = importlib.util.spec_from_file_location('drift_sim', ROOT / 'tools/sim/drift_sim.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for stalls in (False, True):
            for rate in (.995, 1.005):
                with self.subTest(rate=rate, stalls=stalls):
                    result = module.simulate(rate, stalls=stalls)
                    self.assertTrue(module.passes(result, stalls), result)
        result = module.simulate(.995, seconds=360, adaptive=True)
        self.assertTrue(module.passes_adaptive(result), result)



if __name__ == '__main__':
    unittest.main()
