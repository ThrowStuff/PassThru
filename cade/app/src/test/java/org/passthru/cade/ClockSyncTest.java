// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClockSyncTest {
    @Test public void mathAndMedianWindow() {
        ClockSync c=new ClockSync(); assertEquals(0,c.rttMicros(),0);
        c.record(1000,2050,1100); assertEquals(100,c.rttMicros(),0); assertEquals(1000,c.offsetMicros(),0);
        c.record(2000,3030,2060); assertEquals(80,c.rttMicros(),0); assertEquals(1000,c.offsetMicros(),0);
        for (int i=0;i<8;i++) c.record(10000,10000+i*10,10000+i*2);
        assertEquals(7,c.rttMicros(),0); assertEquals(31.5,c.offsetMicros(),0);
        assertThrows(IllegalArgumentException.class,()->c.record(3,4,2));
    }
}
