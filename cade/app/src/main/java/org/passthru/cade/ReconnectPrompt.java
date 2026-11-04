// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

/** Pure notification policy. Dismissal stays latched until output recovery or stop. */
public final class ReconnectPrompt {
    private ReconnectPrompt() { }
    public enum Action { NONE, POST, CANCEL }
    public static Action decide(boolean prompted,boolean enabled,AidAvailability.State availability) {
        if (!enabled || availability==AidAvailability.State.AVAILABLE)
            return prompted?Action.CANCEL:Action.NONE;
        if (!prompted && availability==AidAvailability.State.CONNECTED_NOT_ACTIVE) return Action.POST;
        return Action.NONE;
    }
}
