// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

/** Plain-value policy. AVAILABLE means exposed as an output, not a confirmed playing route. */
public final class AidAvailability {
    public enum State { AVAILABLE, CONNECTED_NOT_ACTIVE, NOT_CONNECTED }
    public static final class Device {
        public final int type;
        public final String address,name;
        public Device(int type,String address,String name) {
            this.type=type; this.address=address==null?"":address; this.name=name==null?"":name;
        }
    }
    private AidAvailability() {}
    private static boolean matches(Device saved,Device device) {
        return saved.type==device.type &&
            ((!saved.address.isEmpty() && saved.address.equals(device.address)) ||
             (!saved.name.isEmpty() && saved.name.equals(device.name)));
    }
    public static State decide(Device saved,Iterable<Device> outputs,Iterable<Device> connected) {
        // Nothing saved yet (first use): any hearing output or connected aid counts.
        if (saved.type<0) {
            if (outputs.iterator().hasNext()) return State.AVAILABLE;
            return connected.iterator().hasNext() ? State.CONNECTED_NOT_ACTIVE : State.NOT_CONNECTED;
        }
        for (Device output:outputs) if (matches(saved,output)) return State.AVAILABLE;
        // Profile observations are only meaningful for the supported hearing outputs.
        if (saved.type==23 || saved.type==26 || saved.type==33)
            for (Device device:connected) if (matches(saved,device)) return State.CONNECTED_NOT_ACTIVE;
        return State.NOT_CONNECTED;
    }
}
