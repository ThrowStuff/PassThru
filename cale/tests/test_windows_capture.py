# SPDX-License-Identifier: GPL-3.0-only
import struct
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from cale.capture import WindowsLoopbackCapture, _LoopbackPCM, _LinearResampler
from cale.mdns import Advertisement


def floats(values):
    return struct.pack('<' + 'f' * len(values), *values)


class ConversionTests(unittest.TestCase):
    def test_clipping(self):
        frame = _LoopbackPCM(48000, 2).feed(floats([-2, 2, -.5, .5] * 240))[0]
        self.assertEqual(len(frame), 1920)
        self.assertEqual(struct.unpack('<hhhh', frame[:8]),
                         (-32768, 32767, -16384, 16384))

    def test_channel_mapping(self):
        for channels, values, expected in [
                (1, [.25], (8192, 8192)),
                (4, [.25, -.25, .75, -.75], (16384, -16384)),
                (3, [.25, -.25, .75], (16384, -8192))]:
            with self.subTest(channels=channels):
                frame = _LoopbackPCM(48000, channels).feed(floats(values * 480))[0]
                self.assertEqual(struct.unpack('<hh', frame[:4]), expected)

    def test_odd_byte_blocks(self):
        raw = floats([i / 2000 for i in range(2000)])
        expected = _LoopbackPCM(48000, 2).feed(raw)
        converter = _LoopbackPCM(48000, 2)
        actual = []
        offset = 0
        while offset < len(raw):
            size = [1, 13, 777, 3, 2011, 19][offset % 6]
            actual.extend(converter.feed(raw[offset:offset + size]))
            offset += size
        self.assertEqual(actual, expected)
        self.assertEqual(len(actual), 2)
        self.assertTrue(all(len(frame) == 1920 for frame in actual))

    def test_44100_assembly(self):
        converter = _LoopbackPCM(44100, 2)
        frames = []
        for _ in range(100):
            frames.extend(converter.feed(floats([.25, -.25] * 441)))
        self.assertEqual(len(frames), 99)
        frames.extend(converter.feed(floats([.25, -.25])))
        self.assertEqual(len(frames), 100)
        self.assertTrue(all(len(frame) == 1920 for frame in frames))


class ResamplerTests(unittest.TestCase):
    def test_passthrough(self):
        converter = _LinearResampler(48000)
        for block in [[(0., 1.)], [], [(2., 3.), (4., 5.)]]:
            self.assertEqual(converter.process(block), block)

    def test_continuity_and_rate(self):
        source = [(float(i), -float(i)) for i in range(44101)]
        expected = _LinearResampler(44100).process(source)
        converter = _LinearResampler(44100)
        actual = []
        offset = 0
        for size in [1, 7, 101, 999, 3, 20000, 22990]:
            actual.extend(converter.process(source[offset:offset + size]))
            offset += size
        self.assertEqual(offset, len(source))
        self.assertEqual(actual, expected)
        self.assertEqual(len(actual), 48001)
        for i, (left, right) in enumerate(actual):
            self.assertAlmostEqual(left, i * 44100 / 48000, places=9)
            self.assertEqual(left, -right)
        self.assertLessEqual(len(converter.samples), 1)


class FakeAudio:
    def __init__(self, blocks):
        self.blocks = blocks
        self.closed = self.terminated = False

    def get_default_wasapi_loopback(self):
        return dict(defaultSampleRate=48000, maxInputChannels=2, index=7)

    def open(self, **kwargs):
        self.options = kwargs
        return self

    def start_stream(self):
        for block in self.blocks:
            self.options['stream_callback'](block, len(block) // 8, {}, 0)

    def is_active(self):
        return True

    def close(self):
        self.closed = True

    def terminate(self):
        self.terminated = True


def backend(audio):
    return SimpleNamespace(PyAudio=lambda: audio, paFloat32=1,
                           paContinue=0, paComplete=1, paAbort=2)


class BackendTests(unittest.TestCase):
    def test_callback_assembly_and_cleanup(self):
        audio = FakeAudio([floats([.5, -.5] * n) for n in [7, 200, 273]])
        capture = WindowsLoopbackCapture(backend(audio))
        iterator = iter(capture)
        self.assertEqual(next(iterator), struct.pack('<hh', 16384, -16384) * 480)
        self.assertEqual(audio.options['input_device_index'], 7)
        self.assertEqual(audio.options['frames_per_buffer'], 480)
        capture.stop()
        self.assertEqual(list(iterator), [])
        self.assertTrue(audio.closed and audio.terminated)

    def test_idle_stop(self):
        audio = FakeAudio([])
        started = threading.Event()
        audio.start_stream = started.set
        capture = WindowsLoopbackCapture(backend(audio))
        frames = []
        thread = threading.Thread(target=lambda: frames.extend(capture))
        thread.start()
        self.assertTrue(started.wait(1))
        capture.stop()
        thread.join(1)
        self.assertFalse(thread.is_alive())
        self.assertEqual(frames, [])
        self.assertTrue(audio.closed and audio.terminated)

    def test_open_failure_cleanup(self):
        audio = FakeAudio([])
        def fail(**kwargs):
            raise OSError('unplugged')
        audio.open = fail
        with self.assertRaisesRegex(OSError, 'unplugged'):
            next(iter(WindowsLoopbackCapture(backend(audio))))
        self.assertTrue(audio.terminated)

    def test_windows_mdns(self):
        with patch.dict('sys.modules', {'zeroconf': None}), \
                patch('cale.mdns.sys.platform', 'win32'), \
                patch('cale.mdns.subprocess.Popen') as spawn, \
                self.assertLogs(level='INFO') as logs:
            Advertisement(48200, 'abc').start()
        spawn.assert_not_called()
        self.assertIn('manual host entry', logs.output[-1])

    def test_stopped_device_reports_error(self):
        audio = FakeAudio([])
        audio.is_active = lambda: False
        with self.assertRaisesRegex(RuntimeError, 'device stopped'):
            next(iter(WindowsLoopbackCapture(backend(audio))))
        self.assertTrue(audio.closed and audio.terminated)

    def test_stop_before_start(self):
        audio = FakeAudio([])
        capture = WindowsLoopbackCapture(backend(audio))
        capture.stop()
        self.assertEqual(list(capture), [])
        self.assertFalse(audio.terminated)

    def test_status_flags_are_glitches_not_failures(self):
        audio = FakeAudio([])
        def start():
            callback = audio.options['stream_callback']
            self.assertEqual(callback(b'', 0, {}, 2)[1], 0)      # paContinue
            callback(floats([.5, -.5] * 480), 480, {}, 2)        # flagged, still used
        audio.start_stream = start
        capture = WindowsLoopbackCapture(backend(audio))
        iterator = iter(capture)
        self.assertEqual(next(iterator), struct.pack('<hh', 16384, -16384) * 480)
        self.assertEqual(capture.glitches, 2)
        capture.stop()
        self.assertEqual(list(iterator), [])
        self.assertTrue(audio.closed and audio.terminated)


class PlatformTests(unittest.TestCase):
    def test_cli_system_audio_selection(self):
        from cale import __main__ as cli
        for platform in ['win32', 'linux']:
            with self.subTest(platform=platform), \
                    patch.object(cli.sys, 'platform', platform), \
                    patch.object(cli.sys, 'argv', ['cale', '--system-audio']), \
                    patch.object(cli, 'DeviceStore'), \
                    patch.object(cli, 'Sender') as sender, \
                    patch.object(cli, 'Advertisement'), \
                    patch.object(cli, 'fingerprint', return_value='abc'), \
                    patch.object(cli, 'WindowsLoopbackCapture') as windows, \
                    patch.object(cli, 'PipeWireCapture') as linux, \
                    patch.object(cli.time, 'sleep', side_effect=KeyboardInterrupt), \
                    patch('builtins.print'):
                cli.main()
                chosen, other = (windows, linux) if platform == 'win32' else (linux, windows)
                chosen.assert_called_once()
                other.assert_not_called()
                self.assertIs(sender.call_args.args[0], chosen.return_value)
