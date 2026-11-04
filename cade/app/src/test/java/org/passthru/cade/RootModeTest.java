// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class RootModeTest {
    @Test public void commandKeepsShellTextInSingleArgument() {
        String cmd="printf %s "+RootMode.quote("aid's $(id); name");
        assertEquals(List.of("/system/xbin/su","-c","printf %s 'aid'\\''s $(id); name'"),RootMode.command("/system/xbin/su",cmd));
        assertEquals("''",RootMode.quote(""));
    }
    @Test public void featureContract() {
        assertEquals("Rooted mode offers a few small quality-of-life improvements.",RootMode.DESCRIPTION);
        assertEquals(1,RootMode.FEATURES.size());
        RootMode.Feature feature=RootMode.FEATURES.get(0);
        assertEquals("Automatic aid reconnect",feature.name);
        assertEquals("Reconnects your hearing aids automatically — no need to pick them again.",feature.description);
        assertEquals("cmd appops set org.passthru.cade MEDIA_ROUTING_CONTROL allow",feature.setup);
        assertEquals("cmd appops set org.passthru.cade MEDIA_ROUTING_CONTROL default",feature.teardown);
        assertEquals("cmd appops get org.passthru.cade MEDIA_ROUTING_CONTROL",feature.verify);
    }
    @Test public void reconnectPolicy() {
        for (AidAvailability.State state:AidAvailability.State.values()) {
            assertEquals(state==AidAvailability.State.CONNECTED_NOT_ACTIVE?RootMode.Action.TRANSFER:RootMode.Action.NONE,
                RootMode.decide(true,false,true,state));
            assertEquals(state==AidAvailability.State.CONNECTED_NOT_ACTIVE?RootMode.Action.NOTIFY:RootMode.Action.NONE,
                RootMode.decide(false,false,true,state));
            assertEquals(RootMode.Action.NONE,RootMode.decide(true,true,true,state));
            assertEquals(RootMode.Action.NONE,RootMode.decide(true,false,false,state));
        }
        assertEquals(RootMode.Action.NOTIFY,RootMode.afterTransfer(false));
        assertEquals(RootMode.Action.NONE,RootMode.afterTransfer(true));
    }
    @Test public void onlySavedHearingSystemRouteMatches() {
        assertTrue(RootMode.matchesRoute(26,"My aids",26,"My aids",true));
        assertFalse(RootMode.matchesRoute(26,"My aids",23,"My aids",true));
        assertFalse(RootMode.matchesRoute(26,"My aids",26,"Other aids",true));
        assertFalse(RootMode.matchesRoute(26,"My aids",26,"My aids",false));
        assertFalse(RootMode.matchesRoute(2,"Speaker",2,"Speaker",true));
        assertFalse(RootMode.matchesRoute(26,"",26,"",true));
    }
}
