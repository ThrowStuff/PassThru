// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.*;

/** Read-only profile proxies; all methods and callbacks run on the main thread. */
final class AidProfiles implements AutoCloseable {
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Map<Integer,BluetoothProfile> proxies=new HashMap<>();
    private final Set<Integer> requested=new HashSet<>();
    private boolean closed;
    private final BluetoothProfile.ServiceListener listener=new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile,BluetoothProfile proxy) {
            if (closed) { adapter.closeProfileProxy(profile,proxy); return; }
            proxies.put(profile,proxy);
        }
        public void onServiceDisconnected(int profile) {
            // Keep the proxy for automatic service reconnection and eventual close.
            // getConnectedDevices returns an empty list while its service is unavailable.
        }
    };
    AidProfiles(Context context) {
        this.context=context;
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
    }
    List<AidAvailability.Device> connected() {
        List<AidAvailability.Device> result=new ArrayList<>();
        if (closed || adapter==null || (Build.VERSION.SDK_INT>=31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)) return result;
        try {
            request(BluetoothProfile.HEARING_AID);
            if (Build.VERSION.SDK_INT>=33) request(BluetoothProfile.LE_AUDIO);
            for (Map.Entry<Integer,BluetoothProfile> entry:proxies.entrySet()) {
                for (BluetoothDevice d:entry.getValue().getConnectedDevices()) {
                    if (d.getBondState()!=BluetoothDevice.BOND_BONDED) continue;
                    if (entry.getKey()==BluetoothProfile.HEARING_AID)
                        result.add(new AidAvailability.Device(23,d.getAddress(),d.getName()));
                    else {
                        result.add(new AidAvailability.Device(26,d.getAddress(),d.getName()));
                        if (Build.VERSION.SDK_INT>=37) result.add(new AidAvailability.Device(33,d.getAddress(),d.getName()));
                    }
                }
            }
        } catch (SecurityException | IllegalStateException ignored) {
            // Permission revoked, Bluetooth off, or proxy unavailable: no positive connection claim.
            result.clear();
        }
        return result;
    }
    private void request(int profile) {
        if (!requested.contains(profile) && adapter.getProfileProxy(context,listener,profile)) requested.add(profile);
    }
    @Override public void close() {
        closed=true;
        for (Map.Entry<Integer,BluetoothProfile> entry:proxies.entrySet()) adapter.closeProfileProxy(entry.getKey(),entry.getValue());
        proxies.clear(); requested.clear();
    }
}
