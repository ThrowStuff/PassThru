// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.passthru.cade.AidAvailability.State.*;
import static org.passthru.cade.ReconnectPrompt.Action.*;

public class ReconnectPromptTest {
    @Test public void enteringConnectedNotActivePostsOnce() {
        assertEquals(NONE,ReconnectPrompt.decide(false,true,NOT_CONNECTED));
        assertEquals(POST,ReconnectPrompt.decide(false,true,CONNECTED_NOT_ACTIVE));
        for (int i=0;i<10;i++) assertEquals(NONE,ReconnectPrompt.decide(true,true,CONNECTED_NOT_ACTIVE));
    }
    @Test public void availableCancelsAndAllowsNextReconnect() {
        assertEquals(CANCEL,ReconnectPrompt.decide(true,true,AVAILABLE));
        assertEquals(NONE,ReconnectPrompt.decide(false,true,AVAILABLE));
        assertEquals(POST,ReconnectPrompt.decide(false,true,CONNECTED_NOT_ACTIVE));
    }
    @Test public void stopCancelsInEveryAvailabilityState() {
        for (AidAvailability.State state:AidAvailability.State.values()) {
            assertEquals(CANCEL,ReconnectPrompt.decide(true,false,state));
            assertEquals(NONE,ReconnectPrompt.decide(false,false,state));
        }
    }
    @Test public void uncertainProfileSnapshotsDoNotRepeatPrompt() {
        assertEquals(NONE,ReconnectPrompt.decide(true,true,NOT_CONNECTED));
        assertEquals(NONE,ReconnectPrompt.decide(true,true,CONNECTED_NOT_ACTIVE));
    }
}
