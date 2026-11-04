// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.util.ArrayDeque;
import java.util.Arrays;

public final class ClockSync {
    private final ArrayDeque<double[]> samples=new ArrayDeque<>();
    public synchronized void record(long t0, long t1, long t2) {
        if (t2<t0) throw new IllegalArgumentException("negative RTT");
        samples.addLast(new double[]{(double)(t2-t0),t1-(t0/2.0+t2/2.0)});
        if (samples.size()>8) samples.removeFirst();
    }
    private double median(int field) {
        double[] v=new double[samples.size()]; int i=0;
        for (double[] s:samples) v[i++]=s[field];
        if (v.length==0) return 0;
        Arrays.sort(v); int m=v.length/2;
        return v.length%2==1?v[m]:(v[m-1]+v[m])/2;
    }
    public synchronized double rttMicros() { return median(0); }
    public synchronized double offsetMicros() { return median(1); }
}
