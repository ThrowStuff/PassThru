// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

/** Pure presentation policy; availability never authorizes playback. */
public final class UiState {
    public enum Service { STOPPED, CONNECTING, PLAYING, UNREACHABLE }
    public enum Pairing { SEARCHING, FOUND, PAIRING }
    public enum Ring { DIM, ROTATING, STEADY, ATTENTION }
    public enum Action { NONE, PAIR, START, STOP, CHOOSE_OUTPUT }
    public final Ring ring;
    public final String sentence;
    public final Action action;
    public final boolean showManualAddress,showBluetoothSettings;
    private UiState(Ring ring,String sentence,Action action) {
        this(ring,sentence,action,false,false);
    }
    private UiState(Ring ring,String sentence,Action action,boolean showManualAddress,boolean showBluetoothSettings) {
        this.ring=ring; this.sentence=sentence; this.action=action;
        this.showManualAddress=showManualAddress; this.showBluetoothSettings=showBluetoothSettings;
    }
    private static String wholeMilliseconds(String delay) {
        try { return Long.toString(Math.round(Double.parseDouble(delay))); }
        catch (NumberFormatException unknown) { return delay; }
    }
    public static UiState decide(Service service,AidAvailability.State availability,Pairing pairing,
                                 boolean hasComputers,String computer,String aid,String delay) {
        if (pairing==Pairing.PAIRING) return new UiState(Ring.ROTATING,"Connecting to "+computer+"…",Action.NONE);
        if (!hasComputers) {
            if (pairing==Pairing.FOUND) return new UiState(Ring.DIM,computer+" is ready to pair.",Action.PAIR,true,false);
            return new UiState(Ring.DIM,"Open Cale on your computer — it will appear here.",Action.NONE,true,false);
        }
        if (availability==AidAvailability.State.CONNECTED_NOT_ACTIVE)
            return new UiState(Ring.ATTENTION,"Your hearing aids are connected — tap to use them.",Action.CHOOSE_OUTPUT,false,true);
        if (availability==AidAvailability.State.NOT_CONNECTED)
            return new UiState(Ring.ATTENTION,"Waiting for "+aid+"…",Action.NONE,false,true);
        switch (service) {
            case CONNECTING: return new UiState(Ring.ROTATING,"Connecting to "+computer+"…",Action.STOP);
            case PLAYING: return new UiState(Ring.STEADY,"Playing "+computer+" on "+aid+" · "+wholeMilliseconds(delay)+" ms delay",Action.STOP);
            case UNREACHABLE: return new UiState(Ring.ATTENTION,"Can't reach "+computer+". Retrying…",Action.STOP);
            default: return new UiState(Ring.DIM,"Ready to play "+computer+" on "+aid+".",Action.START);
        }
    }
}
