// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.*;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverTest {
    @Test public void onlyPinnedLeafIsTrusted() throws Exception {
        X509Certificate cert;
        try (InputStream in=getClass().getResourceAsStream("/server.pem")) {
            cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        byte[] pin=MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        X509TrustManager trust=Receiver.pinnedTrust(pin);
        pin[0]^=1; // Trust manager retains its own copy.
        trust.checkServerTrusted(new X509Certificate[]{cert},"RSA");
        assertThrows(CertificateException.class,()->Receiver.pinnedTrust(pin)
            .checkServerTrusted(new X509Certificate[]{cert},"RSA"));
        assertThrows(CertificateException.class,()->trust.checkServerTrusted(new X509Certificate[0],"RSA"));
        assertThrows(CertificateException.class,()->trust.checkServerTrusted(null,"RSA"));
        assertThrows(CertificateException.class,()->trust.checkClientTrusted(new X509Certificate[]{cert},"RSA"));
        assertEquals(0,trust.getAcceptedIssuers().length);
    }
    @Test public void pairKeyNames() throws Exception {
        byte[] key=new byte[32];
        assertEquals("Discovered",Receiver.pairKeyName(key,"Discovered"));
        byte[] name="Bureau 界".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] payload=java.util.Arrays.copyOf(key,32+name.length);
        System.arraycopy(name,0,payload,32,name.length);
        assertEquals("Bureau 界",Receiver.pairKeyName(payload,"Address"));
        assertEquals("Fallback",Receiver.pairKeyName(new byte[32],"Fallback"));
        assertEquals("x".repeat(64),Receiver.pairKeyName(("0".repeat(32)+"x".repeat(64)).getBytes(java.nio.charset.StandardCharsets.UTF_8),"Fallback"));
        for (byte[] bad:new byte[][]{new byte[31],new byte[97],java.util.Arrays.copyOf(key,33)}) {
            if (bad.length==33) bad[32]=(byte)0xc3;
            assertThrows(java.io.IOException.class,()->Receiver.pairKeyName(bad,"Fallback"));
        }
    }
    @Test public void fingerprintSyntax() {
        assertArrayEquals(new byte[32],Receiver.parseFingerprint("00:".repeat(31)+"00"));
        for (String value:new String[]{"", "0".repeat(63),"g".repeat(64),"0".repeat(65)})
            assertThrows(IllegalArgumentException.class,()->Receiver.parseFingerprint(value));
    }
    @Test public void closeCancelsPendingConfirmation() throws Exception {
        Receiver receiver=new Receiver(new JitterBuffer());
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch interrupted=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<Boolean> answer=new java.util.concurrent.FutureTask<>(()->{
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); return true; }
            catch (InterruptedException e) { interrupted.countDown(); throw e; }
        });
        java.util.concurrent.FutureTask<Boolean> waiting=new java.util.concurrent.FutureTask<>(()->receiver.awaitConfirmation(answer));
        Thread worker=new Thread(waiting); worker.start();
        try {
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));
            receiver.close();
            assertThrows(java.util.concurrent.ExecutionException.class,()->waiting.get(1,java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(answer.isCancelled());
            assertTrue(interrupted.await(1,java.util.concurrent.TimeUnit.SECONDS));
            assertThrows(java.io.IOException.class,()->receiver.awaitConfirmation(new java.util.concurrent.FutureTask<>(()->true)));
        } finally { receiver.close(); worker.interrupt(); }
    }
}
