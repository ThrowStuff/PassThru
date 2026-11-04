// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.passthru.cade.AidAvailability.State.*;

public class AidAvailabilityTest {
    private AidAvailability.Device d(int type,String address,String name) {
        return new AidAvailability.Device(type,address,name);
    }
    private AidAvailability.State decide(AidAvailability.Device saved,
            AidAvailability.Device[] outputs,AidAvailability.Device... connected) {
        return AidAvailability.decide(saved,Arrays.asList(outputs),Arrays.asList(connected));
    }
    @Test public void outputWinsEvenBeforeProfileProxyArrives() {
        assertEquals(AVAILABLE,decide(d(26,"A","Aid"),new AidAvailability.Device[]{d(26,"A","renamed")}));
    }
    @Test public void connectedPairWithoutOutput() {
        assertEquals(CONNECTED_NOT_ACTIVE,decide(d(26,"A","Aid"),new AidAvailability.Device[]{d(2,"","Speaker")},
            d(26,"B","Aid"),d(26,"A","Aid")));
    }
    @Test public void unrelatedOutputCannotHidePendingAid() {
        assertEquals(CONNECTED_NOT_ACTIVE,decide(d(23,"A","aid"),new AidAvailability.Device[]{d(23,"B","other")},d(23,"A","aid")));
    }
    @Test public void missingAidOrUnavailablePermissionHasNoPositiveConnectionClaim() {
        assertEquals(NOT_CONNECTED,decide(d(26,"A","aid"),new AidAvailability.Device[0]));
        assertEquals(NOT_CONNECTED,decide(d(26,"A","aid"),new AidAvailability.Device[0],d(26,"B","other")));
    }
    @Test public void typeMustMatchForOutputsAndProfiles() {
        assertEquals(NOT_CONNECTED,decide(d(23,"A","aid"),new AidAvailability.Device[]{d(26,"A","aid")},d(26,"A","aid")));
        assertEquals(NOT_CONNECTED,decide(d(2,"A","aid"),new AidAvailability.Device[0],d(2,"A","aid")));
    }
    @Test public void emptyIdentitiesNeverMatchAndNullNamesAreSafe() {
        assertEquals(NOT_CONNECTED,decide(d(26,"",null),new AidAvailability.Device[]{d(26,"",null)},d(26,"",null)));
        assertEquals(CONNECTED_NOT_ACTIVE,decide(d(26,"A",null),new AidAvailability.Device[0],d(26,"A",null)));
    }
    @Test public void reconnectThenDisconnectTransitions() {
        for (int type:new int[]{23,26,33}) {
            AidAvailability.Device saved=d(type,"A","Aid"), peer=d(type,"B","Aid");
            assertEquals(CONNECTED_NOT_ACTIVE,decide(saved,new AidAvailability.Device[0],peer));
            assertEquals(AVAILABLE,decide(saved,new AidAvailability.Device[]{peer},peer));
            assertEquals(NOT_CONNECTED,decide(saved,new AidAvailability.Device[0]));
        }
    }
    @org.junit.Test public void firstUseWithoutSavedAidUsesAnyConnectedAid() {
        AidAvailability.Device none=new AidAvailability.Device(-1,"","");
        java.util.List<AidAvailability.Device> empty=java.util.Collections.emptyList();
        java.util.List<AidAvailability.Device> aid=java.util.Arrays.asList(new AidAvailability.Device(26,"AA","Aid"));
        org.junit.Assert.assertEquals(AidAvailability.State.CONNECTED_NOT_ACTIVE,AidAvailability.decide(none,empty,aid));
        org.junit.Assert.assertEquals(AidAvailability.State.AVAILABLE,AidAvailability.decide(none,aid,aid));
        org.junit.Assert.assertEquals(AidAvailability.State.NOT_CONNECTED,AidAvailability.decide(none,empty,empty));
    }
}
