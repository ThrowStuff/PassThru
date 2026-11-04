// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Framework NSD, with serialized resolves for API 29's single-resolve limit. */
public final class Discovery implements AutoCloseable {
    public static final class Computer {
        public final String name,host,fp;
        public final int port;
        Computer(String name,String host,int port,String fp) { this.name=name; this.host=host; this.port=port; this.fp=fp; }
    }
    private final NsdManager manager;
    private final WifiManager.MulticastLock lock;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Computer> found=new LinkedHashMap<>();
    private final Set<String> present=new HashSet<>();
    private final Queue<NsdServiceInfo> queue=new ArrayDeque<>();
    private final Runnable changed;
    // NsdManager on older releases allows only one resolve across this app.
    private static boolean resolving;
    private boolean closed,started;
    public Discovery(Context context,Runnable changed) {
        manager=context.getSystemService(NsdManager.class); this.changed=changed;
        lock=context.getSystemService(WifiManager.class).createMulticastLock("cade-discovery");
        lock.setReferenceCounted(false);
    }
    public synchronized List<Computer> list() { return new ArrayList<>(found.values()); }
    public synchronized Computer find(String pin) {
        for (Computer c:found.values()) if (c.fp.matches("[0-9a-f]{16}") && pin.startsWith(c.fp)) return c;
        return null;
    }
    private final NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener() {
        public void onDiscoveryStarted(String type) {}
        public void onDiscoveryStopped(String type) {}
        public void onStartDiscoveryFailed(String type,int error) { main.post(Discovery.this::close); }
        public void onStopDiscoveryFailed(String type,int error) {}
        public void onServiceFound(NsdServiceInfo info) { main.post(()->{
            if (!closed) { present.add(info.getServiceName()); queue.add(info); resolveNext(); }
        }); }
        public void onServiceLost(NsdServiceInfo info) { main.post(()->{
            present.remove(info.getServiceName());
            synchronized (Discovery.this) { found.remove(info.getServiceName()); }
            changed.run();
        }); }
    };
    public void start() {
        if (started || closed) return;
        lock.acquire(); started=true;
        try { manager.discoverServices("_passthru._tcp.",NsdManager.PROTOCOL_DNS_SD,listener); }
        catch (RuntimeException e) { close(); }
    }
    private void resolveNext() {
        if (closed || queue.isEmpty()) return;
        if (resolving) { main.postDelayed(this::resolveNext,250); return; }
        resolving=true;
        manager.resolveService(queue.remove(),new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info,int error) { main.post(()->{ resolving=false; resolveNext(); }); }
            public void onServiceResolved(NsdServiceInfo info) { main.post(()->{
                if (!closed && present.contains(info.getServiceName()) && info.getHost()!=null) {
                    Map<String,byte[]> txt=info.getAttributes();
                    String fp=new String(txt.getOrDefault("fp",new byte[0]),StandardCharsets.UTF_8);
                    String name=new String(txt.getOrDefault("name",info.getServiceName().getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8);
                    synchronized (Discovery.this) { found.put(info.getServiceName(),new Computer(name,
                        info.getHost().getHostAddress(),info.getPort(),fp)); }
                    changed.run();
                }
                resolving=false; resolveNext();
            }); }
        });
    }
    @Override public void close() {
        if (closed) return; closed=true;
        try { if (started) manager.stopServiceDiscovery(listener); }
        catch (IllegalArgumentException ignored) {}
        finally { if (lock.isHeld()) lock.release(); }
    }
}
