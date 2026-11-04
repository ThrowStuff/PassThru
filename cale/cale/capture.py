# SPDX-License-Identifier: GPL-3.0-only
"""10 ms, 48 kHz stereo signed little-endian PCM capture."""
import math
import struct
import subprocess
import threading
import time

FRAME_BYTES = 1920
SILENCE = bytes(FRAME_BYTES)


class Capture:
    def __iter__(self):
        raise NotImplementedError

    def stop(self):
        raise NotImplementedError


class ToneCapture(Capture):
    def __init__(self, frequency=440, clock=time):
        self.frequency = frequency
        self.clock = clock
        self.stopped = threading.Event()

    def __iter__(self):
        sample = 0
        deadline = self.clock.monotonic()
        while not self.stopped.is_set():
            self.clock.sleep(max(0, deadline - self.clock.monotonic()))
            if self.stopped.is_set():
                break
            pcm = bytearray()
            for i in range(480):
                value = round(8192 * math.sin(2 * math.pi * self.frequency *
                                              (sample + i) / 48000))
                pcm.extend(struct.pack('<hh', value, value))
            yield bytes(pcm)
            sample += 480
            deadline += .01

    def stop(self):
        self.stopped.set()


def pipewire_command(target=None):
    command = ['pw-cat', '--record', '--raw', '--rate', '48000',
               '--channels', '2', '--format', 's16', '--latency', '10ms',
               '--properties', '{"stream.capture.sink": true}']
    if target:
        command += ['--target', target]
    return command + ['-']


class PipeWireCapture(Capture):
    def __init__(self, target=None):
        self.target = target
        self.process = None
        self.stopped = threading.Event()

    def __iter__(self):
        command = pipewire_command(self.target)
        if self.stopped.is_set():
            return
        self.process = subprocess.Popen(command, stdout=subprocess.PIPE)
        try:
            while not self.stopped.is_set():
                data = bytearray()
                while len(data) < FRAME_BYTES:
                    part = self.process.stdout.read(FRAME_BYTES - len(data))
                    if not part:
                        return
                    data.extend(part)
                yield bytes(data)
        finally:
            self.stop()
            self.process.stdout.close()
            self.process.wait()

    def stop(self):
        self.stopped.set()
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()


class _LinearResampler:
    """Stereo interpolation on an integer timebase; retain one boundary sample.

    No end padding: the final fractional sample waits for the next input block.
    Equal rates bypass interpolation without adding a sample of latency.
    """
    def __init__(self, rate):
        if rate <= 0:
            raise ValueError('sample rate must be positive')
        self.rate = rate
        self.samples = []
        self.position = 0

    def process(self, samples):
        if self.rate == 48000:
            return samples
        self.samples.extend(samples)
        output = []
        while self.position // 48000 < len(self.samples):
            index, fraction = divmod(self.position, 48000)
            if fraction and index + 1 >= len(self.samples):
                break
            a = self.samples[index]
            b = self.samples[index + 1] if fraction else a
            output.append(tuple(x + (y - x) * fraction / 48000
                                for x, y in zip(a, b)))
            self.position += self.rate
        consumed = min(self.position // 48000, len(self.samples))
        del self.samples[:consumed]
        self.position -= consumed * 48000
        return output


class _LoopbackPCM:
    """Convert interleaved float32 LE blocks to complete wire frames.

    Mono is duplicated. For multichannel input, average even channels into
    left and odd channels into right. This bounded, deterministic fold includes
    every channel without assuming a speaker mask from PortAudio.
    """
    def __init__(self, rate, channels):
        if channels < 1:
            raise ValueError('channel count must be positive')
        self.channels = channels
        self.resampler = _LinearResampler(rate)
        self.raw = bytearray()
        self.pcm = bytearray()

    def feed(self, block):
        self.raw.extend(block)
        width = self.channels * 4
        size = len(self.raw) // width * width
        samples = []
        for frame in struct.iter_unpack('<' + 'f' * self.channels, self.raw[:size]):
            # NaN becomes zero; infinities are clipped at quantization.
            frame = tuple(0.0 if math.isnan(x) else x for x in frame)
            if self.channels == 1:
                samples.append((frame[0], frame[0]))
            else:
                samples.append((sum(frame[::2]) / len(frame[::2]),
                                sum(frame[1::2]) / len(frame[1::2])))
        del self.raw[:size]
        for frame in self.resampler.process(samples):
            values = []
            for value in frame:
                if math.isnan(value):
                    value = 0.0
                values.append(round(max(-1.0, min(32767 / 32768, value)) * 32768))
            self.pcm.extend(struct.pack('<hh', *values))
        frames = []
        while len(self.pcm) >= FRAME_BYTES:
            frames.append(bytes(self.pcm[:FRAME_BYTES]))
            del self.pcm[:FRAME_BYTES]
        return frames


class WindowsLoopbackCapture(Capture):
    """Default WASAPI render endpoint via PyAudioWPatch (no NumPy).

    The endpoint is selected at start. Restart capture after changing devices.
    Callback blocks are queued, leaving conversion off the audio thread. Empty
    periods yield nothing; Sender owns silence fill. Stop/cleanup occurs on the
    iterator thread, including when the render endpoint is idle.
    """
    def __init__(self, backend=None):
        self.backend = backend
        self.stopped = threading.Event()
        self.glitches = 0

    def __iter__(self):
        import queue
        if self.stopped.is_set():
            return
        backend = self.backend
        if backend is None:
            try:
                import pyaudiowpatch as backend
            except ImportError as error:
                raise RuntimeError('Windows system audio requires PyAudioWPatch '
                                   '(Windows x64 wheel)') from error
        blocks = queue.Queue(maxsize=20)

        def callback(data, count, timing, status):
            # Status flags (e.g. a one-off input overflow) and a full queue are
            # glitches to count, never a reason to end capture.
            if self.stopped.is_set():
                return (None, backend.paComplete)
            if status:
                self.glitches += 1
            if data:
                try:
                    blocks.put_nowait(bytes(data))
                except queue.Full:
                    self.glitches += 1
                    try:
                        blocks.get_nowait()
                    except queue.Empty:
                        pass
                    try:
                        blocks.put_nowait(bytes(data))
                    except queue.Full:
                        pass
            return (None, backend.paContinue)

        audio = backend.PyAudio()
        stream = render = None
        # Only with the real library: test fakes model the loopback input alone.
        keepalive = self.backend is None
        try:
            device = audio.get_default_wasapi_loopback()
            rate = int(device['defaultSampleRate'])
            channels = int(device['maxInputChannels'])
            converter = _LoopbackPCM(rate, channels)
            stream = audio.open(format=backend.paFloat32, channels=channels,
                                rate=rate, input=True,
                                input_device_index=device['index'],
                                frames_per_buffer=max(1, round(rate / 100)),
                                stream_callback=callback, start=False)
            stream.start_stream()
            if keepalive:
                # WASAPI loopback delivers nothing while no app is playing,
                # which the phone would see as network gaps. A silent render
                # stream on the same endpoint keeps loopback running on the
                # device clock (common practice for loopback capture).
                silence = bytes(4 * max(1, round(rate / 100)) * channels)
                render = audio.open(format=backend.paFloat32, channels=channels,
                                    rate=rate, output=True,
                                    frames_per_buffer=max(1, round(rate / 100)),
                                    stream_callback=lambda *a: (silence, backend.paContinue))
            while not self.stopped.is_set():
                try:
                    block = blocks.get(timeout=.02)
                except queue.Empty:
                    if not stream.is_active():
                        raise RuntimeError('Windows audio device stopped; restart capture')
                    continue
                for frame in converter.feed(block):
                    if self.stopped.is_set():
                        return
                    yield frame
        finally:
            try:
                if render is not None:
                    render.close()
                if stream is not None:
                    stream.close()
            finally:
                audio.terminate()

    def stop(self):
        self.stopped.set()
