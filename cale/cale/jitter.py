# SPDX-License-Identifier: GPL-3.0-only
"""Reference receiver buffer; occupancy is queued PCM duration, in ms."""
from collections import deque
import threading
import time
from .capture import FRAME_BYTES, SILENCE


class JitterBuffer:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.frames = deque()
        self.lock = threading.Lock()
        self.underruns = self.overflow_drops = 0
        self.drift_drops = self.drift_repeats = 0
        self.ema = 30.0
        self.last_correction = self.clock()
        self.last_target_step = self.last_correction
        self._target_ms = 30
        self.rebuffering = False
        # Initial fill is not an underrun: no count, no target change.
        self.prefilling = True
        self.warmup_until = float('-inf')
        self.episode_in_warmup = False
        self.episode_target = self.silent_pops = 0
        self.window_min = 10 ** 9

    @property
    def occupancy_ms(self):
        with self.lock:
            return len(self.frames) * 10

    @property
    def target_ms(self):
        with self.lock:
            return self._target_ms

    def push(self, frame):
        if len(frame) != FRAME_BYTES:
            raise ValueError('expected 1920 bytes')
        with self.lock:
            self.frames.append(frame)
            if len(self.frames) * 10 > self._target_ms + 50:
                while len(self.frames) * 10 > self._target_ms:
                    self.frames.popleft()
                    self.overflow_drops += 1

    def pop(self):
        with self.lock:
            now = self.clock()
            # Decay one step per quiet 30 s window, and only with headroom:
            # occupancy must never have fallen below 30 ms (the 10 ms step
            # plus a 20 ms margin for ordinary jitter). Near-misses block it.
            occupancy = len(self.frames) * 10
            if not self.rebuffering:
                self.window_min = min(self.window_min, occupancy)
                if now - self.last_target_step >= 30:
                    if self.window_min >= 30:
                        self._target_ms = max(30, self._target_ms - 10)
                    self.last_target_step = now
                    self.window_min = 10 ** 9
            if self.prefilling:
                if len(self.frames) * 10 < self._target_ms:
                    self.last_target_step = now
                    return SILENCE
                self.prefilling = False
                # Early underruns come from session setup (track priming,
                # buffer tuning), not the network: do not learn from them.
                self.warmup_until = now + 2
            self.ema += .01 * (len(self.frames) * 10 - self.ema)
            if self.rebuffering:
                if len(self.frames) * 10 < self._target_ms:
                    self.silent_pops += 1
                    self.last_target_step = now
                    self.window_min = 10 ** 9
                    return SILENCE
                if not self.episode_in_warmup:
                    self._target_ms = min(100, self.episode_target + 10 * self.silent_pops + 10)
                self.rebuffering = False
            if not self.frames:
                self.underruns += 1
                self.episode_target = self._target_ms
                self.episode_in_warmup = now < self.warmup_until
                self.silent_pops = 1
                self.last_target_step = now
                self.window_min = 10 ** 9
                self.rebuffering = True
                return SILENCE
            if now - self.last_correction >= 1:
                if self.ema > self._target_ms + 10 and len(self.frames) > 1:
                    self.frames.popleft()
                    self.drift_drops += 1
                    self.last_correction = now
                elif self.ema < self._target_ms - 10:
                    self.drift_repeats += 1
                    self.last_correction = now
                    return self.frames[0]
            return self.frames.popleft()
