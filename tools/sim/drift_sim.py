# SPDX-License-Identifier: GPL-3.0-only
"""Deterministic event simulation; no wall-clock sleeping or audio devices."""
import heapq
import math
from pathlib import Path
import random
import sys
import struct

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'cale'))
from cale.jitter import JitterBuffer


def simulate(rate, seconds=600, seed=73, stalls=False, adaptive=False):
    # A repeating 100 Hz stereo tone: exactly one period per 10 ms block.
    tone = b''.join(struct.pack('<hh', value, value) for value in
                    (round(8192 * math.sin(2 * math.pi * i / 480))
                     for i in range(480)))
    class Captured(bytes):
        def __new__(cls, capture):
            frame = super().__new__(cls, tone)
            frame.capture = capture
            return frame

    rng = random.Random(seed)
    now = [0.0]
    buffer = JitterBuffer(clock=lambda: now[0])
    events = []
    delivery = 0
    injected_stalls = 0
    for index in range(int(seconds * 100)):
        sent = index * .01
        delay = rng.uniform(0, .015)
        if stalls and index and index % 1700 == 0:
            delay += .05
            injected_stalls += 1
        if adaptive and index and index % 1000 == 0 and sent < 180:
            delay += .06
            injected_stalls += 1
        # TCP preserves order: a stalled frame holds up later frames too.
        delivery = max(delivery, sent + delay)
        heapq.heappush(events, (delivery, 0, sent))
    interval = .01 / rate
    tick = .03  # Initial 30 ms prefill, then ordinary underrun semantics.
    while tick < seconds:
        heapq.heappush(events, (tick, 1, 0))
        tick += interval
    max_true = 0.0
    maximum = area = previous = 0
    occupancy = 0
    warm_underruns = warm_overflows = 0
    spike_underruns = late_spike_underruns = 0
    while events:
        moment, kind, capture = heapq.heappop(events)
        if moment > seconds:
            break
        area += occupancy * (moment - previous)
        previous = moment
        now[0] = moment
        if kind == 0:
            buffer.push(Captured(capture))
        else:
            before = buffer.underruns
            played = buffer.pop()
            if isinstance(played, Captured):
                max_true = max(max_true, (moment - played.capture) * 1000)
            if adaptive and moment < 180:
                spike_underruns += buffer.underruns - before
                if moment >= 60:
                    late_spike_underruns += buffer.underruns - before
        if moment <= 5:
            warm_underruns = buffer.underruns
            warm_overflows = buffer.overflow_drops
        occupancy = buffer.occupancy_ms
        maximum = max(maximum, occupancy)
    area += occupancy * (seconds - previous)
    return dict(max_ms=maximum, max_true_ms=max_true, mean_ms=area / seconds,
                underruns=buffer.underruns,
                overflow_drops=buffer.overflow_drops,
                after5_underruns=buffer.underruns - warm_underruns,
                after5_overflows=buffer.overflow_drops - warm_overflows,
                drift_drops=buffer.drift_drops,
                drift_repeats=buffer.drift_repeats, stalls=injected_stalls,
                target_ms=buffer.target_ms, spike_underruns=spike_underruns,
                late_spike_underruns=late_spike_underruns)


def passes(result, stalls):
    if stalls:
        return (result['max_ms'] <= 100 and result['max_true_ms'] <= 100 and
                result['underruns'] <= 2 * result['stalls'])
    corrections = result['drift_drops'] + result['drift_repeats']
    return (result['max_ms'] <= 80 and result['max_true_ms'] <= 80 and result['after5_underruns'] == 0 and
            result['after5_overflows'] == 0 and 150 <= corrections <= 600)


def passes_adaptive(result):
    return (result['spike_underruns'] <= 2 and
            result['late_spike_underruns'] == 0 and
            result['target_ms'] == 30 and result['max_ms'] <= 150 and result['max_true_ms'] <= 150)


def main():
    failed = False
    print('scenario       max_ms max_true_ms mean_ms underruns overflow after5_U after5_O '
          'drift_drop drift_repeat stalls result')
    for stalls in (False, True):
        for name, rate in [('slow', .995), ('fast', 1.005)]:
            result = simulate(rate, stalls=stalls)
            passed = passes(result, stalls)
            label = name + ('-stall' if stalls else '')
            print(f"{label:14} {result['max_ms']:6.0f} {result['max_true_ms']:11.2f} {result['mean_ms']:7.2f} "
                  f"{result['underruns']:9} {result['overflow_drops']:8} "
                  f"{result['after5_underruns']:8} {result['after5_overflows']:8} "
                  f"{result['drift_drops']:10} {result['drift_repeats']:12} "
                  f"{result['stalls']:6} {'PASS' if passed else 'FAIL'}")
            failed |= not passed
    result = simulate(.995, seconds=360, adaptive=True)
    passed = passes_adaptive(result)
    print(f"adaptive: max_true_ms={result['max_true_ms']:.2f} max_ms={result['max_ms']} mean_ms={result['mean_ms']:.2f} "
          f"spike_U={result['spike_underruns']} after60_spike_U={result['late_spike_underruns']} "
          f"total_U={result['underruns']} target_ms={result['target_ms']} "
          f"overflow={result['overflow_drops']} stalls={result['stalls']} "
          f"{'PASS' if passed else 'FAIL'}")
    failed |= not passed
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
