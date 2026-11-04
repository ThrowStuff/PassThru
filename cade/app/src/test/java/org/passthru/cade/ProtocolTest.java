// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.Arrays;

public class ProtocolTest {
    private void same(Protocol.Frame a,Protocol.Frame b) {
        assertEquals(a.type,b.type); assertEquals(a.sequence,b.sequence);
        assertEquals(a.timestamp,b.timestamp); assertArrayEquals(a.payload,b.payload);
    }
    private String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder(); for (byte v:bytes) b.append(String.format("%02x",v&255)); return b.toString();
    }
    @Test public void wireRoundtripAndGolden() throws Exception {
        for (int kind:new int[]{1,2,3,4,5,199}) {
            Protocol.Frame f=new Protocol.Frame(kind,0x12345678L,0x123456789abcdef0L,new byte[]{97,98,99});
            byte[] wire=Protocol.encode(f); assertEquals(23,wire.length);
            assertArrayEquals(new byte[]{0x50,0x54,2,(byte)kind},Arrays.copyOf(wire,4));
            same(f,Protocol.decode(wire));
            // Computed with committed Python encode(Frame(1,0x12345678,0x123456789abcdef0,b'abc')).
            if (kind==1) assertEquals("5054020112345678123456789abcdef000000003616263",hex(wire));
        }
        for (int n:new int[]{0,65536}) {
            Protocol.Frame f=new Protocol.Frame(4,0,0,new byte[n]); same(f,Protocol.decode(Protocol.encode(f)));
        }
        Protocol.Frame unsigned=new Protocol.Frame(199,0xffffffffL,-1L,new byte[0]);
        same(unsigned,Protocol.decode(Protocol.encode(unsigned)));
    }
    @Test public void rejections() throws Exception {
        byte[] valid=Protocol.encode(new Protocol.Frame(1,0,0,new byte[]{97,98,99}));
        for (byte[] bad:new byte[][]{new byte[0],Arrays.copyOf(valid,19),Arrays.copyOf(valid,22),Arrays.copyOf(valid,24)})
            assertThrows(Protocol.ProtocolError.class,()->Protocol.decode(bad));
        for (int offset:new int[]{0,2,16}) {
            byte[] bad=valid.clone();
            if (offset==0) { bad[0]=88; bad[1]=88; }
            if (offset==2) bad[2]=1;
            if (offset==16) { bad[17]=1; bad[19]=1; }
            assertThrows(Protocol.ProtocolError.class,()->Protocol.decode(bad));
            assertThrows(Protocol.ProtocolError.class,()->Protocol.readFrame(new DataInputStream(new ByteArrayInputStream(bad))));
        }
        for (Protocol.Frame f:new Protocol.Frame[]{new Protocol.Frame(1,0,0,new byte[65537]),
                new Protocol.Frame(256,0,0,new byte[0]),new Protocol.Frame(-1,0,0,new byte[0]),
                new Protocol.Frame(1,-1,0,new byte[0]),new Protocol.Frame(1,0x100000000L,0,new byte[0])})
            assertThrows(Protocol.ProtocolError.class,()->Protocol.encode(f));
        // A Java long cannot represent an out-of-u64 timestamp; every bit pattern is valid.
    }
    @Test public void partialAndEof() throws Exception {
        Protocol.Frame f=new Protocol.Frame(1,42,91,new byte[1920]); byte[] wire=Protocol.encode(f);
        ByteArrayOutputStream both=new ByteArrayOutputStream(); both.write(wire); both.write(wire);
        InputStream partial=new ByteArrayInputStream(both.toByteArray()) {
            @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(3,len)); }
        };
        DataInputStream in=new DataInputStream(partial);
        same(f,Protocol.readFrame(in)); same(f,Protocol.readFrame(in)); assertNull(Protocol.readFrame(in));
        for (int end:new int[]{1,19,20,wire.length-1}) {
            DataInputStream truncated=new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(wire,end)));
            assertThrows(Protocol.ProtocolError.class,()->Protocol.readFrame(truncated));
        }
    }
    @Test public void slowDripHasAbsoluteDeadline() throws Exception {
        for (int payloadSize:new int[]{0,3}) {
            byte[] wire=Protocol.encode(new Protocol.Frame(Protocol.STATS,0,0,new byte[payloadSize]));
            long[] now={0};
            InputStream drip=new ByteArrayInputStream(wire) {
                @Override public synchronized int read() { now[0]+=300_000_000L; return super.read(); }
                @Override public synchronized int read(byte[] b,int off,int len) {
                    now[0]+=payloadSize>0?(pos<20?1_000_000L:800_000_000L):300_000_000L; return super.read(b,off,1);
                }
            };
            java.util.List<Integer> budgets=new java.util.ArrayList<>();
            assertThrows(java.net.SocketTimeoutException.class,()->Protocol.readFrame(
                new DataInputStream(drip),budgets::add,()->now[0]));
            assertTrue(now[0]<3_000_000_000L);
            assertTrue(budgets.get(budgets.size()-1)<budgets.get(0));
        }
    }
}
