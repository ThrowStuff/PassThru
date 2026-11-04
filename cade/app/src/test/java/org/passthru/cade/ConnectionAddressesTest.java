// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionAddressesTest {
    @Test public void missingDiscoveryUsesSavedAddress() {
        ConnectionAddresses.Address[] a=ConnectionAddresses.choices(null,0,"saved",48200);
        assertEquals(1,a.length); assertEquals("saved",a[0].host); assertEquals(48200,a[0].port);
    }
    @Test public void discoveredAddressPrecedesSavedFallback() {
        ConnectionAddresses.Address[] a=ConnectionAddresses.choices("new",1234,"saved",48200);
        assertEquals(2,a.length); assertEquals("new",a[0].host); assertEquals(1234,a[0].port);
        assertEquals("saved",a[1].host); assertEquals(48200,a[1].port);
    }
    @Test public void sameHostDifferentPortStillHasFallback() {
        assertEquals(2,ConnectionAddresses.choices("same",1234,"same",48200).length);
    }
    @Test public void duplicateAddressIsTriedOnce() {
        assertEquals(1,ConnectionAddresses.choices("same",48200,"same",48200).length);
    }
    @Test public void unusableDiscoveryUsesSavedAddress() {
        for (int port:new int[]{0,-1,65536})
            assertEquals("saved",ConnectionAddresses.choices("new",port,"saved",48200)[0].host);
        assertEquals("saved",ConnectionAddresses.choices("",1234,"saved",48200)[0].host);
    }
}
