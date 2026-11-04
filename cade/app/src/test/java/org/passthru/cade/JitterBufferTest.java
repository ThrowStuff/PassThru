// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class JitterBufferTest {
    private long now;
    private final JitterBuffer b=new JitterBuffer(()->now);
    { b.prefilling=false; } // steady-state tests start after the initial prefill
    @Test public void warmupUnderrunDoesNotTeachTarget() {
        JitterBuffer fresh=new JitterBuffer(()->now);
        for (int i=0;i<3;i++) fresh.push(frame(i));
        for (int i=0;i<4;i++) fresh.pop();
        org.junit.Assert.assertArrayEquals(new byte[1920],fresh.pop());
        for (int i=0;i<3;i++) fresh.push(frame(i));
        fresh.pop();
        assertEquals(1,fresh.counters()[0]); assertEquals(30,fresh.targetMs());
        now=5_000_000_000L;
        while (fresh.occupancyMs()>0) fresh.pop();
        fresh.pop();
        for (int i=0;i<3;i++) fresh.push(frame(i));
        fresh.pop();
        assertEquals(2,fresh.counters()[0]); assertEquals(50,fresh.targetMs());
    }
    @Test public void initialPrefillIsNotAnUnderrun() {
        JitterBuffer fresh=new JitterBuffer(()->now);
        for (int i=0;i<5;i++) org.junit.Assert.assertArrayEquals(new byte[1920],fresh.pop());
        fresh.push(frame('a')); fresh.push(frame('b'));
        org.junit.Assert.assertArrayEquals(new byte[1920],fresh.pop());
        fresh.push(frame('c'));
        org.junit.Assert.assertArrayEquals(frame('a'),fresh.pop());
        assertEquals(0,fresh.counters()[0]); assertEquals(30,fresh.targetMs());
    }
    private byte[] frame(int value) { byte[] f=new byte[1920]; Arrays.fill(f,(byte)value); return f; }
    private void fill(int n) { for (int i=0;i<n;i++) b.push(frame(i)); }
    @Test public void overflowAndOrder() {
        fill(8); assertEquals(80,b.occupancyMs()); b.push(frame('z'));
        assertEquals(30,b.occupancyMs()); assertEquals(6,b.overflowDrops);
        assertArrayEquals(frame(6),b.pop());
        assertThrows(IllegalArgumentException.class,()->b.push(new byte[3]));
    }
    @Test public void underrun() { assertArrayEquals(frame(0),b.pop()); assertEquals(1,b.underruns); }
    @Test public void rebuffer() {
        fill(3); for (int i=0;i<3;i++) b.pop(); assertArrayEquals(frame(0),b.pop());
        fill(2); assertArrayEquals(frame(0),b.pop()); assertEquals(20,b.occupancyMs());
        assertEquals(1,b.underruns); b.push(frame('z')); b.pop();
        assertEquals(20,b.occupancyMs()); assertFalse(b.rebuffering); assertEquals(60,b.targetMs());
    }
    private void underrunEvent(int silentPops) {
        while (b.occupancyMs()>0) b.pop();
        int initial=b.targetMs();
        for (int i=0;i<silentPops;i++) {
            assertArrayEquals(frame(0),b.pop()); assertEquals(initial,b.targetMs());
        }
        fill(initial/10); b.pop();
    }
    @Test public void targetRaiseAndMax() {
        assertEquals(30,b.targetMs());
        int[] counts={1,2,1,12}, expected={50,80,100,100};
        for (int i=0;i<counts.length;i++) {
            long count=b.underruns; underrunEvent(counts[i]);
            assertEquals(expected[i],b.targetMs()); assertEquals(count+1,b.underruns);
            assertFalse(b.rebuffering);
        }
    }
    @Test public void targetDecayAndMin() {
        underrunEvent(1); now=29_000_000_000L; underrunEvent(1);
        long[] moments={30_000_000_000L,58_999_000_000L,59_000_000_000L,88_999_000_000L,
            89_000_000_000L,119_000_000_000L,149_000_000_000L,359_000_000_000L};
        int[] expected={70,70,60,60,50,40,30,30};
        for (int i=0;i<moments.length;i++) {
            now=moments[i]; fill(4); b.pop(); assertEquals(expected[i],b.targetMs());
        }
    }
    @Test public void nearMissBlocksDecay() {
        underrunEvent(1); now=29_000_000_000L; underrunEvent(1);
        now=40_000_000_000L; fill(4);
        while (b.occupancyMs()>20) b.pop();
        b.pop();
        now=59_000_000_000L; fill(4); b.pop(); assertEquals(70,b.targetMs());
        now=89_000_000_000L; fill(4); b.pop(); assertEquals(60,b.targetMs());
    }
    @Test public void noDecayDuringRebuffer() {
        underrunEvent(1); while (b.occupancyMs()>0) b.pop(); b.pop();
        now=90_000_000_000L; b.pop(); assertEquals(50,b.targetMs());
        fill(5); b.pop(); assertEquals(80,b.targetMs());
        now=119_999_000_000L; b.pop(); assertEquals(80,b.targetMs());
        now=120_000_000_000L; b.pop(); assertEquals(70,b.targetMs());
    }
    @Test public void adaptiveOverflowCap() {
        for (int target:new int[]{50,70,90,100}) {
            underrunEvent(1); assertEquals(target,b.targetMs());
            while (b.occupancyMs()>0) b.pop();
            fill((target+50)/10); assertEquals(target+50,b.occupancyMs());
            long drops=b.overflowDrops; b.push(frame('z'));
            assertEquals(target,b.occupancyMs()); assertEquals(6,b.overflowDrops-drops);
        }
    }
    @Test public void adaptiveDriftThresholds() {
        underrunEvent(1); fill(2); now=1_000_000_000L; b.ema=61; b.pop();
        assertEquals(1,b.driftDrops);
        now=2_000_000_000L; b.ema=39; int occupancy=b.occupancyMs(); b.pop();
        assertEquals(1,b.driftRepeats); assertEquals(occupancy,b.occupancyMs());
    }
    @Test public void emaAndCorrectionInterval() {
        fill(6); now=1_000_000_000L; b.pop(); assertEquals(30.3,b.ema,1e-9); assertEquals(0,b.driftDrops);
        b.ema=41; b.pop(); assertEquals(1,b.driftDrops); assertEquals(0,b.overflowDrops);
        b.ema=41; now=1_999_000_000L; b.pop(); assertEquals(1,b.driftDrops);
        b.push(frame(0)); b.ema=19; now=2_000_000_000L; int occupancy=b.occupancyMs();
        b.pop(); assertEquals(1,b.driftRepeats); assertEquals(occupancy,b.occupancyMs()); assertEquals(0,b.underruns);
    }
    @Test public void emaThresholds() {
        for (int occupancy:new int[]{40,20}) {
            JitterBuffer buffer=new JitterBuffer(()->now);
            for (int i=0;i<occupancy/10;i++) buffer.push(frame(0));
            buffer.ema=occupancy; now+=2_000_000_000L; buffer.pop();
            assertEquals(0,buffer.driftDrops+buffer.driftRepeats);
        }
    }
    @Test public void repeatReturnsHeadAndCopyIsolation() {
        byte[] input=frame(7); b.push(input); input[0]=0; b.ema=19; now=1_000_000_000L;
        byte[] repeated=b.pop(); assertArrayEquals(frame(7),repeated); repeated[0]=0;
        assertArrayEquals(frame(7),b.pop()); assertEquals(1,b.driftRepeats);
    }
}
