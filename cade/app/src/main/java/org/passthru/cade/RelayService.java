// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.app.*;
import android.content.Intent;
import android.media.*;
import android.os.*;
import android.net.wifi.WifiManager;
import java.util.Locale;

public final class RelayService extends Service {
    public interface Listener { void onUpdate(String state,String stats); }
    public final class LocalBinder extends Binder { public RelayService service() { return RelayService.this; } }
    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile String state="Stopped", stats="Latency — · buffer 0 ms · target 30 ms · underruns 0 · drops 0";
    private Session session;
    private Discovery discovery;
    private AidProfiles aidProfiles;
    private boolean chooseOutput;
    private boolean attentionPrompted;
    private boolean reconnectAttempted,rootPending;
    private long reconnectGeneration;
    private RootAidTransfer rootTransfer;
    public boolean needsOutputChoice() { return chooseOutput; }
    private final Runnable availabilityRefresh=new Runnable() {
        @Override public void run() {
            Session s=session;
            if (s!=null && s.routeLost && !s.stopped) {
                java.util.List<AidAvailability.Device> outputs=new java.util.ArrayList<>();
                for (AudioDeviceInfo d:getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    outputs.add(new AidAvailability.Device(d.getType(),d.getAddress(),d.getProductName().toString()));
                AidAvailability.State availability=AidAvailability.decide(
                    new AidAvailability.Device(s.type,s.address,s.name),outputs,aidProfiles.connected());
                handleReconnect(s,availability);
                chooseOutput=availability==AidAvailability.State.CONNECTED_NOT_ACTIVE;
                state=chooseOutput?"Your hearing aids are connected — tap to use them.":"Waiting for "+s.name+"…";
                publish();
            }
            if (s!=null && !s.stopped && s.routeLost) main.postDelayed(this,2000);
        }
    };
    private final class Session {
        volatile boolean stopped, routeLost;
        volatile Receiver receiver;
        final int type;
        final String address,name;
        // Guarded by this Session; retains additions arriving during cleanup.
        long additions,waitAfter;
        Session(int type,String address,String name) { this.type=type; this.address=address; this.name=name; }
        boolean matches(AudioDeviceInfo d) {
            return d.isSink() && HearingOutputs.matches(type,address,name,
                d.getType(),d.getAddress(),d.getProductName().toString());
        }
        Thread worker;
        WifiManager.WifiLock wifiLock;
        PowerManager.WakeLock wakeLock;
        synchronized void acquireLocks() {
            if (stopped || (wifiLock!=null && wifiLock.isHeld())) return;
            wifiLock=getSystemService(WifiManager.class).createWifiLock(
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY,"cade");
            wifiLock.setReferenceCounted(false);
            wakeLock=getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,"cade:relay");
            wakeLock.setReferenceCounted(false);
            wifiLock.acquire();
            wakeLock.acquire();
        }
        synchronized void releaseLocks() {
            if (wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock!=null && wifiLock.isHeld()) wifiLock.release();
        }
        // Player cleanup belongs to the worker. Never take the Player monitor
        // while holding this lock: its route-loss callback takes this lock.
        void stop() {
            synchronized (this) {
                stopped=true;
                Receiver r=receiver; if (r!=null) r.close();
                if (worker!=null) worker.interrupt();
                notifyAll();
            }
            releaseLocks();
        }
    }
    private final AudioDeviceCallback devices=new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            Session s=session;
            if (s==null) return;
            synchronized (s) {
                for (AudioDeviceInfo d:added) if (s.matches(d)) {
                    s.additions++; s.notifyAll();
                }
            }
        }
    };
    public boolean isRunning() { return session!=null; }
    public String computerPin() { return getSharedPreferences("relay",MODE_PRIVATE).getString("pin",""); }
    public void setListener(Listener listener) { this.listener=listener; publish(); }
    private void publish() {
        main.post(()->{
            Listener l=listener; if (l!=null) l.onUpdate(state,stats);
            if (session!=null) getSystemService(NotificationManager.class).notify(1,notification(state));
        });
    }
    private void update(Session s,String value) {
        main.post(()->{ if (session==s) { state=value; chooseOutput=false; updateAttention(true,AidAvailability.State.AVAILABLE); publish(); } });
    }
    private void handleReconnect(Session s,AidAvailability.State availability) {
        if (availability!=AidAvailability.State.CONNECTED_NOT_ACTIVE) {
            reconnectAttempted=false;
            cancelRootTransfer();
            updateAttention(true,availability);
            return;
        }
        RootMode.Action action=RootMode.decide(new RootSettings(this).enabled(),reconnectAttempted,true,availability);
        if (action==RootMode.Action.TRANSFER) {
            reconnectAttempted=true; rootPending=true;
            long generation=++reconnectGeneration;
            rootTransfer=new RootAidTransfer();
            rootTransfer.start(this,s.type,s.name,failure->{
                rootPending=false; rootTransfer=null;
                if (session!=s || s.stopped || !s.routeLost) return;
                new RootSettings(this).record(failure==null?"Root reconnect: transferred to saved aid":
                    "Root reconnect: "+failure+"; using reconnect notification");
                if (RootMode.afterTransfer(failure==null)==RootMode.Action.NOTIFY)
                    updateAttention(true,AidAvailability.State.CONNECTED_NOT_ACTIVE);
                else main.postDelayed(()->{
                    if (reconnectGeneration==generation && session==s && !s.stopped && s.routeLost && chooseOutput) {
                        new RootSettings(this).record("Root reconnect: transfer completed but aid output is absent; using reconnect notification");
                        updateAttention(true,AidAvailability.State.CONNECTED_NOT_ACTIVE); publish();
                    }
                },5000);
                publish();
            });
        } else if (action==RootMode.Action.NOTIFY) {
            reconnectAttempted=true;
            updateAttention(true,availability);
        }
    }
    private void cancelRootTransfer() {
        reconnectGeneration++;
        if (rootTransfer!=null) rootTransfer.cancel();
        rootTransfer=null; rootPending=false;
    }
    public void rootModeChanged() {
        if (!new RootSettings(this).enabled() && rootPending) {
            cancelRootTransfer();
            if (chooseOutput) updateAttention(true,AidAvailability.State.CONNECTED_NOT_ACTIVE);
        }
    }
    private void updateAttention(boolean enabled,AidAvailability.State availability) {
        if (!enabled || availability==AidAvailability.State.AVAILABLE) {
            reconnectAttempted=false; cancelRootTransfer();
        }
        ReconnectPrompt.Action action=ReconnectPrompt.decide(attentionPrompted,enabled,availability);
        NotificationManager notifications=getSystemService(NotificationManager.class);
        if (action==ReconnectPrompt.Action.POST) {
            attentionPrompted=true;
            Intent intent=new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_CHOOSE_OUTPUT,true);
            PendingIntent open=PendingIntent.getActivity(this,2,intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text="Hearing aids connected — tap to use them";
            notifications.notify(2,new Notification.Builder(this,"attention")
                .setSmallIcon(R.drawable.ic_cade).setContentTitle("Cade").setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build());
        } else if (action==ReconnectPrompt.Action.CANCEL) {
            attentionPrompted=false;
            notifications.cancel(2);
        }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(AudioManager.class).registerAudioDeviceCallback(devices,main);
        aidProfiles=new AidProfiles(this);
        discovery=new Discovery(this,()->{}); discovery.start();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
            "relay","Audio playback",NotificationManager.IMPORTANCE_LOW));
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
            "attention","Needs attention",NotificationManager.IMPORTANCE_HIGH));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        android.content.SharedPreferences prefs=getSharedPreferences("relay",MODE_PRIVATE);
        if (intent!=null && "stop".equals(intent.getAction())) { stopRelay(); stopSelf(); return START_NOT_STICKY; }
        if (intent==null) {
            if (!prefs.getBoolean("enabled",false)) { stopSelf(); return START_NOT_STICKY; }
            intent=new Intent().putExtra("pin",prefs.getString("pin",""))
                .putExtra("outputType",prefs.getInt("outputType",-1))
                .putExtra("outputAddress",prefs.getString("outputAddress",""))
                .putExtra("outputName",prefs.getString("outputName",""));
        }
        boolean testSpeaker=BuildConfig.DEBUG && intent.getBooleanExtra("test_speaker",false);
        if (intent.getIntExtra("outputType",-1)==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && !testSpeaker) {
            stopRelay(); stopSelf(); return START_NOT_STICKY;
        }
        stopRelay();
        startForeground(1,notification("Connecting"));
        String pin=intent.getStringExtra("pin");
        Session s=new Session(intent.getIntExtra("outputType",-1),
            intent.getStringExtra("outputAddress"),intent.getStringExtra("outputName"));
        // Old transient-id preferences cannot safely identify an output.
        if (s.type<0 || s.address==null || s.name==null) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY;
        }
        session=s;
        prefs.edit().putBoolean("enabled",true).putString("pin",pin).remove("device")
            .putInt("outputType",s.type).putString("outputAddress",s.address).putString("outputName",s.name).apply();
        s.worker=new Thread(()->runSession(s,pin),"cade-session"); s.worker.start();
        return START_STICKY;
    }
    private Notification notification(String text) {
        String pin=getSharedPreferences("relay",MODE_PRIVATE).getString("pin","");
        PairedComputers.Computer computer=new PairedComputers(this).find(pin);
        String name=computer==null?"your computer":computer.name;
        if (text.equals("Connecting")) text="Connecting to "+name+"…";
        else if (text.equals("Playing")) text="Playing "+name+" on "+(session==null?"your hearing aids":session.name)+".";
        else if (text.startsWith("Disconnected")) text="Can't reach "+name+". Retrying…";
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RelayService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"relay").setSmallIcon(org.passthru.cade.R.drawable.ic_cade)
            .setContentTitle("Cade").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).setOngoing(true).build();
    }
    private void runSession(Session s,String pin) {
        try {
            AudioManager manager=getSystemService(AudioManager.class); long backoff=500;
            while (!s.stopped) {
                long additions;
                synchronized (s) { additions=s.additions; s.routeLost=false; }
                Player player=null; Receiver receiver=null; long connected=0;
                try {
                    AudioDeviceInfo selected=null;
                    for (AudioDeviceInfo d:HearingOutputs.list(manager)) if (s.matches(d)) selected=d;
                    if (selected==null) { synchronized (s) { s.waitAfter=additions; s.routeLost=true; } throw new IllegalStateException("route lost"); }
                    s.acquireLocks();
                    JitterBuffer buffer=new JitterBuffer(); receiver=new Receiver(buffer); s.receiver=receiver;
                    if (s.stopped) break;
                    PairedComputers store=new PairedComputers(this);
                    PairedComputers.Computer computer=store.find(pin);
                    if (computer==null) throw new IllegalStateException("Computer forgotten");
                    Discovery.Computer resolved=discovery.find(pin);
                    ConnectionAddresses.Address[] addresses=ConnectionAddresses.choices(
                        resolved==null?null:resolved.host,resolved==null?0:resolved.port,
                        computer.lastHost,computer.lastPort);
                    String address=""; int destination=0;
                    update(s,"Connecting");
                    for (int i=0;i<addresses.length;i++) {
                        address=addresses[i].host; destination=addresses[i].port;
                        try { receiver.connect(address,destination,pin); break; }
                        catch (Exception error) {
                            receiver.close();
                            if (s.stopped || i+1==addresses.length) throw error;
                            receiver=new Receiver(buffer); s.receiver=receiver;
                        }
                    }
                    receiver.authenticate(computer.key);
                    if (!store.rememberAddress(pin,address,destination)) throw new IllegalStateException("Computer forgotten");
                    if (s.stopped) break;
                    player=new Player(manager,selected,buffer,()->{
                        synchronized (s) {
                            s.waitAfter=s.additions; s.routeLost=true; Receiver r=s.receiver; if (r!=null) r.close();
                        }
                    });
                    synchronized (s) {
                        if (s.stopped) break;
                        player.start();
                    }
                    update(s,"Playing");
                    final Player playing=player; final Receiver receiving=receiver;
                    connected=System.nanoTime();
                    receiver.read(()->makeStats(s,buffer,playing,receiving));

                } catch (Exception error) {
                    if (s.routeLost || "route lost".equals(error.getMessage())) {
                        synchronized (s) {
                            if (!s.routeLost) s.waitAfter=additions;
                            s.routeLost=true;
                        }
                    }
                    else if (!s.stopped) update(s,"Disconnected: "+error.getClass().getSimpleName()+" · retrying");
                } finally {
                    if (connected!=0 && System.nanoTime()-connected>10_000_000_000L) backoff=500;
                    if (receiver!=null) receiver.close();
                    if (player!=null) player.close();
                    s.receiver=null;
                }
                if (s.stopped) break;
                boolean outputPresent=false;
                for (AudioDeviceInfo d:HearingOutputs.list(manager)) if (s.matches(d)) outputPresent=true;
                if (s.routeLost) s.releaseLocks();
                if (RouteRecovery.waitForAddition(s.routeLost,outputPresent)) {
                    main.post(()->{
                        if (session==s) { main.removeCallbacks(availabilityRefresh); availabilityRefresh.run(); }
                    });
                    synchronized (s) {
                        while (!s.stopped && s.additions==s.waitAfter) {
                            try { s.wait(); } catch (InterruptedException e) { return; }
                        }
                    }
                    continue;
                }
                try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
                backoff=RouteRecovery.nextBackoff(backoff);
            }
        } finally {
            s.releaseLocks();
            main.post(()->{
                if (session==s) {
                    session=null; state="Stopped"; chooseOutput=false;
                    updateAttention(false,AidAvailability.State.NOT_CONNECTED);
                    publish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
            });
        }
    }
    private String makeStats(Session s,JitterBuffer b,Player p,Receiver r) {
        long[] c=b.counters(); double latency=p.latencyMs(r.clock.rttMicros());
        String number=Double.isFinite(latency)?String.format(Locale.ROOT,"%.1f",latency):"null";
        String line=String.format(Locale.ROOT,"Latency %s ms · buffer %d ms · target %d ms · underruns %d · drops %d · repeats %d",
            number.equals("null")?"—":number,b.occupancyMs(),b.targetMs(),c[0],c[1]+c[2],c[3]);
        main.post(()->{ if (session==s) { stats=line; publish(); } });
        return String.format(Locale.ROOT,
            "{\"occupancy_ms\":%d,\"target_ms\":%d,\"underruns\":%d,\"overflow_drops\":%d,\"drift_drops\":%d,\"drift_repeats\":%d,\"latency_ms\":%s}",
            b.occupancyMs(),b.targetMs(),c[0],c[1],c[2],c[3],number);
    }
    public void stopRelay() {
        getSharedPreferences("relay",MODE_PRIVATE).edit().putBoolean("enabled",false).apply();
        main.removeCallbacks(availabilityRefresh);
        Session old=session; session=null; if (old!=null) old.stop();
        updateAttention(false,AidAvailability.State.NOT_CONNECTED);
        state="Stopped"; chooseOutput=false; publish(); stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() {
        updateAttention(false,AidAvailability.State.NOT_CONNECTED);
        Session old=session; session=null; if (old!=null) old.stop();
        getSystemService(AudioManager.class).unregisterAudioDeviceCallback(devices);
        main.removeCallbacks(availabilityRefresh);
        if (aidProfiles!=null) aidProfiles.close();
        if (discovery!=null) discovery.close(); listener=null; super.onDestroy();
    }
}
