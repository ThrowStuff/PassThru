// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

public final class JitterBuffer {
    public static final int FRAME_BYTES=1920;
    private final ArrayDeque<byte[]> frames=new ArrayDeque<>();
    private final LongSupplier clock;
    long underruns, overflowDrops, driftDrops, driftRepeats;
    double ema=30;
    long lastCorrection, lastTargetStep;
    private int targetMs=30;
    private int episodeTarget, silentPops, windowMin=Integer.MAX_VALUE;
    boolean rebuffering;
    // Initial fill is not an underrun: no count, no target change.
    boolean prefilling=true;
    long warmupUntil=Long.MIN_VALUE;
    boolean episodeInWarmup;
    public JitterBuffer() { this(System::nanoTime); }
    /** Clock returns monotonic nanoseconds. */
    public JitterBuffer(LongSupplier clock) { this.clock=clock; lastCorrection=clock.getAsLong(); lastTargetStep=lastCorrection; }
    public synchronized int targetMs() { return targetMs; }
    public synchronized int occupancyMs() { return frames.size()*10; }
    public synchronized void push(byte[] frame) {
        if (frame.length!=FRAME_BYTES) throw new IllegalArgumentException("expected 1920 bytes");
        frames.addLast(frame.clone());
        if (frames.size()*10>targetMs+50) while (frames.size()*10>targetMs) { frames.removeFirst(); overflowDrops++; }
    }
    public synchronized byte[] pop() {
        long now=clock.getAsLong();
        // Decay one step per quiet 30 s window, only with >= 30 ms headroom
        // (mirrors cale/cale/jitter.py).
        if (!rebuffering) {
            windowMin=Math.min(windowMin,frames.size()*10);
            if (now-lastTargetStep>=30_000_000_000L) {
                if (windowMin>=30) targetMs=Math.max(30,targetMs-10);
                lastTargetStep=now; windowMin=Integer.MAX_VALUE;
            }
        }
        if (prefilling) {
            if (frames.size()*10<targetMs) { lastTargetStep=now; return new byte[FRAME_BYTES]; }
            prefilling=false;
            // Early underruns come from session setup, not the network.
            warmupUntil=now+2_000_000_000L;
        }
        ema+=.01*(frames.size()*10-ema);
        if (rebuffering) {
            if (frames.size()*10<targetMs) {
                silentPops++; lastTargetStep=now; windowMin=Integer.MAX_VALUE; return new byte[FRAME_BYTES];
            }
            if (!episodeInWarmup) targetMs=Math.min(100,episodeTarget+10*silentPops+10);
            rebuffering=false;
        }
        if (frames.isEmpty()) {
            underruns++; episodeTarget=targetMs; episodeInWarmup=now<warmupUntil; silentPops=1; lastTargetStep=now; windowMin=Integer.MAX_VALUE; rebuffering=true; return new byte[FRAME_BYTES];
        }
        if (now-lastCorrection>=1_000_000_000L) {
            if (ema>targetMs+10 && frames.size()>1) {
                frames.removeFirst(); driftDrops++; lastCorrection=now;
            } else if (ema<targetMs-10) {
                driftRepeats++; lastCorrection=now; return frames.getFirst().clone();
            }
        }
        return frames.removeFirst();
    }
    public synchronized long[] counters() {
        return new long[]{underruns,overflowDrops,driftDrops,driftRepeats};
    }
}
