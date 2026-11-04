// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.passthru.cade.UiState.*;
import static org.passthru.cade.AidAvailability.State.*;
public class UiStateTest {
    private void row(Service service,AidAvailability.State aid,Pairing pairing,boolean saved,Ring ring,String sentence,Action action) {
        UiState ui=decide(service,aid,pairing,saved,"Desk","Aids","42");
        assertEquals(ring,ui.ring); assertEquals(sentence,ui.sentence); assertEquals(action,ui.action);
    }
    @Test public void secondaryActionsFollowPresentationState() {
        for (Service service:Service.values()) for (AidAvailability.State aid:AidAvailability.State.values())
            for (Pairing pairing:Pairing.values()) for (boolean saved:new boolean[]{false,true}) {
                UiState ui=decide(service,aid,pairing,saved,"Desk","Aids","42");
                assertEquals(!saved && pairing!=Pairing.PAIRING,ui.showManualAddress);
                assertEquals(saved && pairing!=Pairing.PAIRING && aid!=AVAILABLE,ui.showBluetoothSettings);
            }
    }
    @Test public void sentenceRoundsDelayToWholeMilliseconds() {
        assertEquals("Playing Desk on Aids · 103 ms delay",
            decide(Service.PLAYING,AVAILABLE,Pairing.SEARCHING,true,"Desk","Aids","103.4").sentence);
        assertEquals("Playing Desk on Aids · 104 ms delay",
            decide(Service.PLAYING,AVAILABLE,Pairing.SEARCHING,true,"Desk","Aids","103.6").sentence);
        assertEquals("Playing Desk on Aids · — ms delay",
            decide(Service.PLAYING,AVAILABLE,Pairing.SEARCHING,true,"Desk","Aids","—").sentence);
    }
    @Test public void specRows() {
        row(Service.STOPPED,NOT_CONNECTED,Pairing.SEARCHING,false,Ring.DIM,"Open Cale on your computer — it will appear here.",Action.NONE);
        row(Service.STOPPED,NOT_CONNECTED,Pairing.FOUND,false,Ring.DIM,"Desk is ready to pair.",Action.PAIR);
        row(Service.STOPPED,AVAILABLE,Pairing.SEARCHING,true,Ring.DIM,"Ready to play Desk on Aids.",Action.START);
        row(Service.CONNECTING,AVAILABLE,Pairing.SEARCHING,true,Ring.ROTATING,"Connecting to Desk…",Action.STOP);
        row(Service.PLAYING,AVAILABLE,Pairing.SEARCHING,true,Ring.STEADY,"Playing Desk on Aids · 42 ms delay",Action.STOP);
        row(Service.PLAYING,CONNECTED_NOT_ACTIVE,Pairing.SEARCHING,true,Ring.ATTENTION,"Your hearing aids are connected — tap to use them.",Action.CHOOSE_OUTPUT);
        row(Service.CONNECTING,NOT_CONNECTED,Pairing.SEARCHING,true,Ring.ATTENTION,"Waiting for Aids…",Action.NONE);
        row(Service.UNREACHABLE,AVAILABLE,Pairing.SEARCHING,true,Ring.ATTENTION,"Can't reach Desk. Retrying…",Action.STOP);
    }
    @Test public void pairingAndMissingAidTakePriority() {
        row(Service.STOPPED,NOT_CONNECTED,Pairing.PAIRING,false,Ring.ROTATING,"Connecting to Desk…",Action.NONE);
        row(Service.STOPPED,NOT_CONNECTED,Pairing.SEARCHING,true,Ring.ATTENTION,"Waiting for Aids…",Action.NONE);
    }
}
