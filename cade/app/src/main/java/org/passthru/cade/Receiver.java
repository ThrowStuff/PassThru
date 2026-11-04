// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.net.ssl.*;

public final class Receiver implements AutoCloseable {
    private volatile Socket raw;
    private volatile SSLSocket socket;
    private volatile boolean closed;
    private final JitterBuffer buffer;
    public final ClockSync clock=new ClockSync();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    public Receiver(JitterBuffer buffer) { this.buffer=buffer; }
    public static byte[] parseFingerprint(String value) {
        String hex=value.replace(":", "").trim();
        if (!hex.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Fingerprint must contain 64 hexadecimal characters");
        byte[] bytes=new byte[32];
        for (int i=0;i<32;i++) bytes[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return bytes;
    }
    static X509TrustManager pinnedTrust(byte[] pin) {
        final byte[] expected=pin.clone();
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                throw new CertificateException("client certificates not accepted");
            }
            public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                try {
                    if (chain==null || chain.length==0 || expected.length!=32 ||
                        !MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256")
                            .digest(chain[0].getEncoded()))) throw new CertificateException("certificate pin mismatch");
                } catch (NoSuchAlgorithmException e) { throw new CertificateException(e); }
            }
        };
    }
    private boolean authenticated;
    private byte[] serverFp;
    public interface Confirmation { boolean confirm(String code) throws Exception; }
    public void connect(String host,int port,String fingerprint) throws Exception {
        open(host,port,parseFingerprint(fingerprint));
    }
    private void open(String host,int port,byte[] pin) throws Exception {
        authenticated=false;
        SSLContext context=SSLContext.getInstance("TLSv1.3");
        context.init(null,new TrustManager[]{pin==null ? unpinnedTrust() : pinnedTrust(pin)},null);
        Socket transport=new Socket(); raw=transport;
        if (closed) { transport.close(); throw new IOException("stopped"); }
        transport.setReceiveBufferSize(8192); transport.setTcpNoDelay(true);
        transport.connect(new InetSocketAddress(host,port),5000);
        SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(transport,host,port,true);
        socket=tls;
        if (closed) { tls.close(); throw new IOException("stopped"); }
        tls.setEnabledProtocols(new String[]{"TLSv1.3"});
        SSLParameters parameters=tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm(null); tls.setSSLParameters(parameters);
        tls.setSoTimeout(5000);
        ScheduledFuture<?> handshake=watchdog.schedule(this::closeTransport,5,TimeUnit.SECONDS);
        try { tls.startHandshake(); } finally { handshake.cancel(false); }
        serverFp=PairingCrypto.commit(tls.getSession().getPeerCertificates()[0].getEncoded());
        tls.setSoTimeout(2000);
    }
    private static X509TrustManager unpinnedTrust() {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c,String a) throws CertificateException { throw new CertificateException(); }
            public void checkServerTrusted(X509Certificate[] c,String a) throws CertificateException {
                if (c==null || c.length==0) throw new CertificateException("missing certificate");
            }
        };
    }
    private void send(int type,byte[] payload) throws IOException {
        ScheduledFuture<?> timeout=watchdog.schedule(this::closeTransport,1,TimeUnit.SECONDS);
        try { socket.getOutputStream().write(Protocol.encode(new Protocol.Frame(type,0,0,payload))); }
        finally { timeout.cancel(false); }
    }
    private Protocol.Frame readFrame(DataInputStream in) throws IOException {
        int timeout=socket.getSoTimeout();
        try { return Protocol.readFrame(in,ms->socket.setSoTimeout(timeout>0?Math.min(timeout,ms):ms)); }
        finally { socket.setSoTimeout(timeout); }
    }
    private byte[] require(int type,int size) throws IOException {
        Protocol.Frame f=readFrame(new DataInputStream(socket.getInputStream()));
        if (f==null || f.type!=type || f.payload.length!=size) throw new Protocol.ProtocolError("Unexpected pairing/auth response");
        return f.payload;
    }
    public void authenticate(String key) throws IOException {
        authenticated=false;
        send(Protocol.AUTH,parseFingerprint(key)); require(Protocol.AUTH_OK,0); authenticated=true;
    }
    public PairedComputers.Computer pair(String host,int port,String name,Confirmation confirmation) throws Exception {
        open(host,port,null);
        byte[] nc=new byte[32]; new SecureRandom().nextBytes(nc);
        send(Protocol.PAIR_COMMIT,PairingCrypto.commit(nc));
        byte[] ns=require(Protocol.PAIR_NONCE,32);
        send(Protocol.PAIR_REVEAL,nc);
        // UI waits off the network thread; the connection has one TLS owner.
        long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        FutureTask<Boolean> answer=new FutureTask<>(()->confirmation.confirm(PairingCrypto.code(serverFp,nc,ns)));
        boolean match=awaitConfirmation(answer);
        send(Protocol.PAIR_CONFIRM,new byte[]{(byte)(match?1:0)});
        if (!match) throw new IOException("Codes do not match");
        long remaining=expires-System.nanoTime();
        if (remaining<=0) throw new IOException("Pairing timeout");
        socket.setSoTimeout((int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));
        ScheduledFuture<?> deadline=watchdog.schedule(this::closeTransport,remaining,TimeUnit.NANOSECONDS);
        byte[] key;
        try {
            Protocol.Frame f=readFrame(new DataInputStream(socket.getInputStream()));
            if (f==null || f.type!=Protocol.PAIR_KEY) throw new Protocol.ProtocolError("Expected pairing key");
            key=f.payload;
        } finally { deadline.cancel(false); }
        String computerName=pairKeyName(key,name);
        socket.setSoTimeout(2000); authenticated=true;
        return new PairedComputers.Computer(PairingCrypto.hex(serverFp),
            PairingCrypto.hex(java.util.Arrays.copyOf(key,32)),computerName,host,port);
    }
    static String pairKeyName(byte[] payload,String fallback) throws IOException {
        if (payload.length<32 || payload.length>96) throw new Protocol.ProtocolError("Invalid pairing key size");
        try {
            String name=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload,32,payload.length-32)).toString();
            return name.isBlank()?fallback:name;
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new Protocol.ProtocolError("Invalid computer name");
        }
    }
    /** Calling thread owns every application TLS read and write. */
    public void read(Supplier<String> stats) throws IOException {
        if (!authenticated) throw new IOException("Authentication required");
        final SSLSocket tls=socket;
        long next=0, id=0;
        DataInputStream in=new DataInputStream(tls.getInputStream());
        while (!closed) {
            if (System.nanoTime()>=next) {
                long t0=System.nanoTime()/1000;
                synchronized (this) { pendingId=id; pendingTime=t0; }
                ScheduledFuture<?> budget=watchdog.schedule(this::closeTransport,1,TimeUnit.SECONDS);
                try { tls.getOutputStream().write(Protocol.encode(new Protocol.Frame(Protocol.PING,id,t0,new byte[0]))); }
                finally { budget.cancel(false); }
                id=(id+1)&0xffffffffL; next=System.nanoTime()+1_000_000_000L;
                send(Protocol.STATS,stats.get().getBytes(StandardCharsets.UTF_8));
            }
            Protocol.Frame f=readFrame(in);
            long arrival=System.nanoTime()/1000;
            if (f==null || f.type==Protocol.BYE) throw new EOFException("sender disconnected");
            if (f.type==Protocol.AUDIO) {
                if (f.payload.length!=JitterBuffer.FRAME_BYTES) throw new Protocol.ProtocolError("bad PCM size");
                buffer.push(f.payload);
            } else if (f.type==Protocol.PONG) {
                if (f.payload.length!=8) throw new Protocol.ProtocolError("bad PONG size");
                long t0=ByteBuffer.wrap(f.payload).getLong();
                // Only accept the outstanding ping, including its echoed send time.
                synchronized (this) {
                    if (f.sequence==pendingId && t0==pendingTime && t0<=arrival) {
                        clock.record(t0,f.timestamp,arrival); pendingId=-1;
                    }
                }
            }
        }
    }
    private FutureTask<Boolean> pendingConfirmation;
    boolean awaitConfirmation(FutureTask<Boolean> answer) throws Exception {
        synchronized (this) {
            if (closed) { answer.cancel(true); throw new IOException("stopped"); }
            pendingConfirmation=answer;
            Thread task=new Thread(answer,"cade-confirm"); task.setDaemon(true); task.start();
        }
        try { return answer.get(120,TimeUnit.SECONDS); }
        finally {
            answer.cancel(true);
            synchronized (this) { if (pendingConfirmation==answer) pendingConfirmation=null; }
        }
    }
    private long pendingId=-1, pendingTime;
    private void closeTransport() {
        // Closing the underlying TCP socket also interrupts blocked TLS reads/writes.
        Socket s=raw;
        if (s!=null) try { s.close(); } catch (IOException ignored) { }
    }
    @Override public void close() {
        synchronized (this) {
            closed=true;
            if (pendingConfirmation!=null) pendingConfirmation.cancel(true);
        }
        closeTransport();
        watchdog.shutdownNow();
    }
}
