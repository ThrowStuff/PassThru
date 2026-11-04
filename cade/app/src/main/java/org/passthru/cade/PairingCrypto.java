// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

public final class PairingCrypto {
    private PairingCrypto() {}
    public static byte[] commit(byte[] nonce) {
        try { return MessageDigest.getInstance("SHA-256").digest(nonce); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String code(byte[] fp,byte[] nc,byte[] ns) {
        if (fp.length!=32 || nc.length!=32 || ns.length!=32) throw new IllegalArgumentException("32 bytes required");
        byte[] input=ByteBuffer.allocate(102).put("PTPAIR".getBytes(StandardCharsets.US_ASCII))
            .put(fp).put(nc).put(ns).array();
        return String.format(Locale.ROOT,"%06d",Integer.toUnsignedLong(ByteBuffer.wrap(commit(input)).getInt())%1_000_000);
    }
    public static String hex(byte[] bytes) {
        StringBuilder s=new StringBuilder();
        for (byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255));
        return s.toString();
    }
}
