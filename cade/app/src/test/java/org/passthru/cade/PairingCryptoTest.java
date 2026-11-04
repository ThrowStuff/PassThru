// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;
import org.junit.Test;
import static org.junit.Assert.*;

public class PairingCryptoTest {
    @Test public void golden() {
        String fp="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        String nc="202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f";
        String ns="404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f";
        assertEquals("784550",PairingCrypto.code(Receiver.parseFingerprint(fp),Receiver.parseFingerprint(nc),Receiver.parseFingerprint(ns)));
        assertEquals("72dbb7336c76780023f83da4c355f2eeea85733b13d3477697917790c1229084",PairingCrypto.hex(PairingCrypto.commit(Receiver.parseFingerprint(nc))));
    }
    @Test public void constants() {
        assertArrayEquals(new int[]{6,7,8,9,10,11,12,13,13},new int[]{Protocol.PAIR_COMMIT,
            Protocol.PAIR_NONCE,Protocol.PAIR_REVEAL,Protocol.PAIR_CONFIRM,Protocol.PAIR_KEY,
            Protocol.AUTH,Protocol.AUTH_OK,Protocol.AUTH_FAIL,Protocol.PAIR_FAIL});
    }
}
