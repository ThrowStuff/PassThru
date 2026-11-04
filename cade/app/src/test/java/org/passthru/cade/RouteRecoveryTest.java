// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;
import org.junit.Test;
import static org.junit.Assert.*;
public class RouteRecoveryTest {
    @Test public void missingOutputWaitsButRouteMismatchRetries() {
        assertTrue(RouteRecovery.waitForAddition(true,false));
        assertFalse(RouteRecovery.waitForAddition(true,true));
        assertFalse(RouteRecovery.waitForAddition(false,false));
        long delay=500;
        for (long expected:new long[]{1000,2000,4000,5000,5000}) {
            delay=RouteRecovery.nextBackoff(delay); assertEquals(expected,delay);
        }
    }
}
