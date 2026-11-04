// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

/** Missing outputs wait for addition; present outputs retry routing with backoff. */
public final class RouteRecovery {
    private RouteRecovery() {}
    public static boolean waitForAddition(boolean routeLost, boolean outputPresent) {
        return routeLost && !outputPresent;
    }
    public static long nextBackoff(long previous) { return Math.min(5000,previous*2); }
}
