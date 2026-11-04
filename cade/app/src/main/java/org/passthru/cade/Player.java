// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.media.*;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Player implements AutoCloseable {
    private final AudioManager manager;
    private final AudioDeviceInfo selected;
    private final JitterBuffer buffer;
    private final Runnable routeLost;
    private final AtomicBoolean lost=new AtomicBoolean();
    private final AudioTrack track;
    private volatile boolean stopped;
    private volatile long framesWritten;
    private Thread thread;
    private final AudioDeviceCallback devices=new AudioDeviceCallback() {
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            for (AudioDeviceInfo d:removed) if (d.getId()==selected.getId()) loseRoute();
        }
    };
    private final AudioRouting.OnRoutingChangedListener routing=router->checkRoute();
    public Player(AudioManager manager,AudioDeviceInfo selected,JitterBuffer buffer,Runnable lost) {
        this.manager=manager; this.selected=selected; this.buffer=buffer; this.routeLost=lost;
        int min=AudioTrack.getMinBufferSize(48000,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if (min<=0) throw new IllegalStateException("PCM playback unavailable");
        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(min)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
        track.setVolume(0);
        if (!track.setPreferredDevice(selected)) { track.release(); throw new IllegalStateException("route lost"); }
        burst=track.getBufferCapacityInFrames()/2;
        try { burst=Integer.parseInt(manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)); }
        catch (NumberFormatException ignored) { }
        track.setBufferSizeInFrames(Math.max(1,Math.min(track.getBufferCapacityInFrames(),2*burst)));
    }
    public void start() {
        manager.registerAudioDeviceCallback(devices,new Handler(Looper.getMainLooper()));
        track.addOnRoutingChangedListener(routing,new Handler(Looper.getMainLooper()));
        thread=new Thread(this::play,"cade-playback"); thread.start();
    }
    private boolean confirmed;
    private synchronized void checkRoute() {
        if (stopped) return;
        AudioDeviceInfo routed=track.getRoutedDevice();
        if (confirmed && (routed==null || routed.getId()!=selected.getId())) { loseRoute(); return; }
        if (routed!=null && routed.getId()==selected.getId()) { confirmed=true; track.setVolume(1); }
    }
    private synchronized void loseRoute() {
        if (!stopped && lost.compareAndSet(false,true)) {
            stopped=true; track.setVolume(0);
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
            routeLost.run();
        }
    }
    private long nextRoutePoll;
    // Oboe-style latency tuning: start small, grow by one burst whenever the
    // track underruns. The routed output (e.g. LE Audio, 960-frame periods)
    // can need far more than the primary output's burst.
    private int burst, lastUnderruns;
    private long nextTune;
    private void tuneBuffer() {
        long now=System.nanoTime();
        if (now<nextTune) return;
        nextTune=now+100_000_000L;
        int underruns=track.getUnderrunCount();
        if (underruns>lastUnderruns) {
            int size=track.getBufferSizeInFrames(), capacity=track.getBufferCapacityInFrames();
            if (size<capacity) { track.setBufferSizeInFrames(Math.min(capacity,size+Math.max(burst,240))); primeWithSilence(); }
            android.util.Log.i("CadePlayer","underruns="+underruns+" buffer "+size+"->"+track.getBufferSizeInFrames()+" of "+capacity);
        }
        lastUnderruns=underruns;
    }
    private synchronized void pollRoute(long deadline) {
        long now=System.nanoTime();
        if (!confirmed && now>=deadline) { loseRoute(); return; }
        if (now>=nextRoutePoll) {
            nextRoutePoll=now+100_000_000L;
            checkRoute();
        }
    }
    // A track accepts writes instantly until its buffer is full. Fill that
    // space with silence (non-blocking) so network frames are then consumed at
    // the real playback rate instead of draining the jitter buffer in a burst.
    private void primeWithSilence() {
        byte[] silence=new byte[JitterBuffer.FRAME_BYTES];
        while (!stopped) {
            int n=track.write(silence,0,silence.length,AudioTrack.WRITE_NON_BLOCKING);
            if (n<=0) break;
            framesWritten+=n/4;
            if (n<silence.length) break;
        }
    }
    public int bufferFrames() { return track.getBufferSizeInFrames(); }
    private void play() {
        try {
            synchronized (this) { if (stopped) return; track.play(); }
            primeWithSilence();
            long deadline=System.nanoTime()+2_000_000_000L;
            while (!stopped) {
                pollRoute(deadline);
                tuneBuffer();
                if (stopped) break;
                byte[] pcm=buffer.pop(); int offset=0;
                while (offset<pcm.length && !stopped) {
                    pollRoute(deadline);
                    if (stopped) break;
                    int n=track.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_BLOCKING);
                    if (n<=0) throw new IllegalStateException("AudioTrack write failed");
                    offset+=n; framesWritten+=n/4;
                }
            }
        } catch (RuntimeException e) { if (!stopped) loseRoute(); }
    }
    public double latencyMs(double rttMicros) {
        AudioTimestamp timestamp=new AudioTimestamp();
        if (!track.getTimestamp(timestamp)) return Double.NaN;
        return buffer.occupancyMs()+Math.max(0,framesWritten-timestamp.framePosition)/48.0+rttMicros/2000;
    }
    public synchronized void requestStop() {
        stopped=true; track.setVolume(0);
        try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
    }
    @Override public void close() {
        requestStop();
        manager.unregisterAudioDeviceCallback(devices);
        track.removeOnRoutingChangedListener(routing);
        track.setVolume(0);
        try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
        if (thread!=null && thread!=Thread.currentThread()) try { thread.join(1500); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) { track.release(); }
    }
}
