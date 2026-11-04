// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class Protocol {
    public static final int AUDIO=1, PING=2, PONG=3, STATS=4, BYE=5, MAX_PAYLOAD=65536;
    public static final int PAIR_COMMIT=6, PAIR_NONCE=7, PAIR_REVEAL=8, PAIR_CONFIRM=9,
        PAIR_KEY=10, AUTH=11, AUTH_OK=12, AUTH_FAIL=13, PAIR_FAIL=13;
    private Protocol() {}
    public static final class ProtocolError extends IOException {
        public ProtocolError(String message) { super(message); }
    }
    // timestamp stores unsigned u64 bits verbatim, including values above Long.MAX_VALUE.
    public static final class Frame {
        public final int type;
        public final long sequence, timestamp;
        public final byte[] payload;
        public Frame(int type, long sequence, long timestamp, byte[] payload) {
            this.type=type; this.sequence=sequence; this.timestamp=timestamp;
            this.payload=payload.clone();
        }
    }
    public static byte[] encode(Frame f) throws ProtocolError {
        if (f.type<0 || f.type>255 || f.sequence<0 || f.sequence>0xffffffffL
                || f.payload.length>MAX_PAYLOAD) throw new ProtocolError("field out of range");
        return ByteBuffer.allocate(20+f.payload.length).putShort((short)0x5054)
            .put((byte)2).put((byte)f.type).putInt((int)f.sequence).putLong(f.timestamp)
            .putInt(f.payload.length).put(f.payload).array();
    }
    public static final class Header {
        public final int type, size;
        public final long sequence, timestamp;
        private Header(int type, long sequence, long timestamp, int size) {
            this.type=type; this.sequence=sequence; this.timestamp=timestamp; this.size=size;
        }
    }
    public static Header decodeHeader(byte[] bytes) throws ProtocolError {
        if (bytes.length!=20) throw new ProtocolError("header must be 20 bytes");
        ByteBuffer b=ByteBuffer.wrap(bytes);
        int magic=b.getShort()&65535, version=b.get()&255, type=b.get()&255;
        long seq=Integer.toUnsignedLong(b.getInt()), time=b.getLong();
        long size=Integer.toUnsignedLong(b.getInt());
        if (magic!=0x5054 || version!=2 || size>MAX_PAYLOAD)
            throw new ProtocolError("bad magic, version, or length");
        return new Header(type,seq,time,(int)size);
    }
    public static Frame decode(byte[] bytes) throws ProtocolError {
        Header h=decodeHeader(Arrays.copyOf(bytes,Math.min(20,bytes.length)));
        if (bytes.length!=20+h.size) throw new ProtocolError("payload length mismatch");
        return new Frame(h.type,h.sequence,h.timestamp,Arrays.copyOfRange(bytes,20,bytes.length));
    }
    public interface Timeout { void set(int milliseconds) throws IOException; }
    public static Frame readFrame(DataInputStream in) throws IOException {
        return readFrame(in, ms->{}, System::nanoTime);
    }
    public static Frame readFrame(DataInputStream in, Timeout timeout) throws IOException {
        return readFrame(in,timeout,System::nanoTime);
    }
    static Frame readFrame(DataInputStream in, Timeout timeout, java.util.function.LongSupplier clock) throws IOException {
        int first=in.read();
        if (first<0) return null;
        long deadline=clock.getAsLong()+2_000_000_000L;
        byte[] header=new byte[20]; header[0]=(byte)first;
        readExact(in,header,1,deadline,timeout,clock);
        Header h=decodeHeader(header);
        byte[] payload=new byte[h.size];
        readExact(in,payload,0,deadline,timeout,clock);
        return new Frame(h.type,h.sequence,h.timestamp,payload);
    }
    private static void readExact(DataInputStream in, byte[] data, int offset, long deadline,
            Timeout timeout, java.util.function.LongSupplier clock) throws IOException {
        while (offset<data.length) {
            long left=deadline-clock.getAsLong();
            if (left<=0) throw new java.net.SocketTimeoutException("frame deadline");
            timeout.set((int)Math.max(1,(left+999999)/1000000));
            int count=in.read(data,offset,data.length-offset);
            if (clock.getAsLong()>deadline) throw new java.net.SocketTimeoutException("frame deadline");
            if (count<0) throw new ProtocolError("truncated frame");
            offset+=count;
        }
    }
}
