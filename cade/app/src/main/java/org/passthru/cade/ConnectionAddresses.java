// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

/** Ordered destinations for one connection attempt; no duplicate fallback. */
final class ConnectionAddresses {
    static final class Address {
        final String host;
        final int port;
        Address(String host,int port) { this.host=host; this.port=port; }
    }
    static Address[] choices(String resolvedHost,int resolvedPort,String lastHost,int lastPort) {
        Address last=new Address(lastHost,lastPort);
        if (resolvedHost==null || resolvedHost.isEmpty() || resolvedPort<1 || resolvedPort>65535)
            return new Address[]{last};
        Address resolved=new Address(resolvedHost,resolvedPort);
        if (resolvedHost.equals(lastHost) && resolvedPort==lastPort) return new Address[]{resolved};
        return new Address[]{resolved,last};
    }
}
