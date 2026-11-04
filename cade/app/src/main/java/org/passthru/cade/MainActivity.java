// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    public static final String EXTRA_CHOOSE_OUTPUT="org.passthru.cade.CHOOSE_OUTPUT";
    private boolean pendingOutputChoice;
    private EditText host,port;
    private StatusRing ring;
    private LinearLayout outputPicker;
    private ProgressBar searching;
    private String serviceState="Stopped", technicalStats="Latency — · buffer 0 ms · target 30 ms · underruns 0 · drops 0";
    private UiState.Action action=UiState.Action.NONE;
    private Discovery.Computer found;
    private String pairingName="";
    private AidProfiles profiles;
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private final Runnable availabilityTick=new Runnable() {
        public void run() { updateButtons(); uiHandler.postDelayed(this,2000); }
    };
    private PairedComputers paired;
    private Discovery discovery;
    private PairedComputers.Computer selected;
    private android.app.AlertDialog pairingDialog;
    private volatile boolean destroyed;
    private volatile Receiver pairingReceiver;
    private volatile boolean pairing;
    private boolean debugPairConfirm;
    private Spinner outputs;
    private TextView status,stats;
    private Button start,manual,bluetoothSettings;
    private boolean debugTestSpeaker,debugAutostartPermissionBypass;
    private boolean running,debugAutostart;
    private AudioManager manager;
    private List<AudioDeviceInfo> devices=new ArrayList<>();
    private RelayService service;
    private boolean bound;
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((RelayService.LocalBinder)binder).service();
            if (service.isRunning()) selected=paired.find(service.computerPin());
            service.setListener((state,line)->{
                serviceState=state; technicalStats=line;
                running=service.isRunning(); updateButtons();
            });
            running=service.isRunning(); updateButtons();
            if (BuildConfig.DEBUG) maybeAutostart();
        }
        public void onServiceDisconnected(ComponentName name) { service=null; running=false; updateButtons(); }
    };
    private final AudioDeviceCallback callback=new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { refresh(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { refresh(); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); manager=getSystemService(AudioManager.class);
        ScrollView scroll=new ScrollView(this); LinearLayout body=new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL); int pad=(int)(24*getResources().getDisplayMetrics().density);
        body.setPadding(pad,pad,pad,pad); scroll.addView(body); setContentView(scroll);
        scroll.setBackgroundColor(getColor(R.color.background));
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        TextView title=new TextView(this); title.setText("Cade"); title.setTextSize(32);
        title.setTextColor(getColor(R.color.accent));
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(header,new LinearLayout.LayoutParams(-1,-2));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton overflow=new ImageButton(this,null,android.R.attr.borderlessButtonStyle);
        overflow.setImageResource(R.drawable.ic_more_vert); overflow.setContentDescription("More options");
        header.addView(overflow,new LinearLayout.LayoutParams(dp(48),dp(48)));
        overflow.setOnClickListener(v->{
            PopupMenu menu=new PopupMenu(this,overflow);
            if (RootMode.detectSu()!=null) menu.getMenu().add("Rooted mode").setOnMenuItemClickListener(item->{ showRootMode(); return true; });
            menu.getMenu().add("Paired computers").setOnMenuItemClickListener(item->{ showPairedComputers(); return true; });
            SubMenu add=menu.getMenu().addSubMenu("Add computer");
            for (Discovery.Computer c:discovery.list()) {
                boolean known=false;
                for (PairedComputers.Computer savedComputer:paired.list())
                    if (c.fp.length()==16 && savedComputer.fp.startsWith(c.fp)) known=true;
                if (!known) add.add(c.name).setOnMenuItemClickListener(item->{
                    if (running) stopPlayback(); pairComputer(c.host,c.port,c.name); return true;
                });
            }
            add.add("Enter address instead").setOnMenuItemClickListener(item->{ showManualAddress(); return true; });
            menu.show();
        });
        paired=new PairedComputers(this);
        profiles=new AidProfiles(this);
        SharedPreferences prefs=getSharedPreferences("connection",MODE_PRIVATE);
        // Detached fields also retain the debug automation's host/port contract.
        host=new EditText(this); host.setText(prefs.getString("host",""));
        port=new EditText(this); port.setText(prefs.getString("port","48200"));
        discovery=new Discovery(this,this::refreshComputers);
        if (BuildConfig.DEBUG) {
            Bundle extras=getIntent().getExtras();
            if (extras!=null) {
                if (extras.containsKey("host")) host.setText(extras.getString("host"));
                if (extras.containsKey("port")) port.setText(String.valueOf(extras.get("port")));
                debugPairConfirm=extras.getBoolean("pair_confirm",false);
                debugAutostart=extras.getBoolean("autostart",false);
                debugTestSpeaker=extras.getBoolean("test_speaker",false);
            }
        }
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int ringHeight=landscape?(int)(Math.min(getResources().getConfiguration().screenWidthDp,
            getResources().getConfiguration().screenHeightDp)*.4f):220;
        ring=new StatusRing(this); body.addView(ring,new LinearLayout.LayoutParams(-1,dp(ringHeight)));
        status=new TextView(this); status.setTextSize(22); status.setGravity(Gravity.CENTER); body.addView(status);
        searching=new ProgressBar(this); searching.setContentDescription("Searching for computers");
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(dp(32),dp(32)); searchParams.gravity=Gravity.CENTER;
        body.addView(searching,searchParams);
        outputPicker=new LinearLayout(this); outputPicker.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(outputPicker);
        TextView outputLabel=new TextView(this); outputLabel.setText("Output"); outputLabel.setTextSize(16);
        outputPicker.addView(outputLabel);
        outputs=new Spinner(this); outputs.setContentDescription("Output");
        outputPicker.addView(outputs,new LinearLayout.LayoutParams(0,-2,1));
        start=new Button(this); start.setTextSize(18); start.setAllCaps(false);
        start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        start.setTextColor(getColor(R.color.on_accent)); body.addView(start);
        start.setOnClickListener(v->{
            switch (action) {
                case START: startRelay(); break;
                case STOP: stopPlayback(); break;
                case PAIR: if (found!=null) pairComputer(found.host,found.port,found.name); break;
                case CHOOSE_OUTPUT: withNearbyPermission(this::openOutputSettings); break;
                default: break;
            }
        });
        manual=textButton(body,"Enter address instead"); manual.setOnClickListener(v->showManualAddress());
        bluetoothSettings=textButton(body,"Bluetooth settings");
        bluetoothSettings.setOnClickListener(v->withNearbyPermission(this::openBluetoothSettings));
        Button details=textButton(body,"Details");
        stats=new TextView(this); stats.setTextSize(14); stats.setVisibility(View.GONE); body.addView(stats);
        details.setOnClickListener(v->{ boolean expand=stats.getVisibility()!=View.VISIBLE;
            stats.setVisibility(expand?View.VISIBLE:View.GONE); details.setText(expand?"Hide details":"Details"); });
        outputs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) { updateButtons(); }
            public void onNothingSelected(AdapterView<?> parent) { updateButtons(); }
        });
        refresh();
        consumeOutputChoice(getIntent());
    }
    private void consumeOutputChoice(Intent intent) {
        if (intent!=null && intent.getBooleanExtra(EXTRA_CHOOSE_OUTPUT,false)) {
            intent.removeExtra(EXTRA_CHOOSE_OUTPUT);
            pendingOutputChoice=true;
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); consumeOutputChoice(intent);
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        // The system picker requires a visible foreground Activity.
        if (pendingOutputChoice) { pendingOutputChoice=false; openOutputSettings(); }
    }
    private int dp(int value) { return (int)(value*getResources().getDisplayMetrics().density); }
    private Button textButton(LinearLayout body,String text) {
        Button button=new Button(this,null,android.R.attr.borderlessButtonStyle);
        button.setText(text); button.setTextSize(16); button.setAllCaps(false);
        button.setTextColor(getColor(R.color.accent)); body.addView(button); return button;
    }
    private void showManualAddress() {
        LinearLayout fields=new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(24),0,dp(24),0);
        EditText address=field(fields,"Computer address",host.getText().toString(),InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        EditText number=field(fields,"Port",port.getText().toString(),InputType.TYPE_CLASS_NUMBER);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Add computer")
            .setView(fields).setPositiveButton("Pair",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            try {
                String value=address.getText().toString().trim(); int n=Integer.parseInt(number.getText().toString());
                pairComputer(value,n,value); host.setText(value); port.setText(String.valueOf(n)); save(); dialog.dismiss();
            } catch (IllegalArgumentException error) { address.setError("Enter a computer address and a port from 1 to 65535"); }
        })); dialog.show();
    }
    private void stopPlayback() {
        if (service!=null) service.stopRelay(); stopService(new Intent(this,RelayService.class));
        running=false; serviceState="Stopped"; updateButtons();
    }
    private ScrollView scrollable(LinearLayout content) {
        ScrollView scroll=new ScrollView(this); scroll.addView(content); return scroll;
    }
    private void showPairedComputers() {
        LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Paired computers")
            .setView(scrollable(list)).setPositiveButton("Done",null).create();
        if (paired.list().isEmpty()) { TextView empty=new TextView(this); empty.setText("No computers paired yet"); list.addView(empty); }
        for (PairedComputers.Computer c:paired.list()) {
            Button choose=textButton(list,c.name); choose.setOnClickListener(v->{
                if (running) stopPlayback(); selected=c; updateButtons(); dialog.dismiss();
            });
            Button forget=textButton(list,"Forget "+c.name); forget.setOnClickListener(v->{
                if (selected!=null && selected.fp.equals(c.fp)) { stopPlayback(); selected=null; }
                paired.forget(c.fp); refreshComputers(); dialog.dismiss(); showPairedComputers();
            });
        }
        dialog.show();
    }
    private void withNearbyPermission(Runnable next) {
        if (Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
            new android.app.AlertDialog.Builder(this).setMessage("Cade needs Nearby devices to recognise your hearing aids")
                .setPositiveButton("Continue",(d,w)->{ permissionContinuation=next; requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); })
                .setNegativeButton("Not now",null).show();
        } else next.run();
    }
    private Runnable permissionContinuation;
    private EditText field(LinearLayout body,String label,String value,int input) {
        TextView name=new TextView(this); name.setText(label); body.addView(name);
        EditText edit=new EditText(this); edit.setSingleLine(true); edit.setInputType(input); edit.setText(value);
        edit.setMinHeight((int)(48*getResources().getDisplayMetrics().density)); body.addView(edit); return edit;
    }
    private void save() {
        getSharedPreferences("connection",MODE_PRIVATE).edit().putString("host",host.getText().toString().trim())
            .putString("port",port.getText().toString()).apply();
    }
    private void refresh() {
        int old=outputs.getSelectedItemPosition();
        AudioDeviceInfo previous=old>=0 && old<devices.size()?devices.get(old):null;
        SharedPreferences prefs=getSharedPreferences("relay",MODE_PRIVATE);
        int type=previous==null?prefs.getInt("outputType",-1):previous.getType();
        String address=previous==null?prefs.getString("outputAddress",""):previous.getAddress();
        String product=previous==null?prefs.getString("outputName",""):previous.getProductName().toString();
        devices=HearingOutputs.list(manager);
        if (!(BuildConfig.DEBUG && debugTestSpeaker)) devices.removeIf(d->d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        List<String> names=new ArrayList<>(); int selection=0;
        for (AudioDeviceInfo d:devices) {
            String name="Hearing aid "+d.getId();
            try { name=d.getProductName().toString(); } catch (SecurityException ignored) { }
            if (BuildConfig.DEBUG) {
                if (d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    name="TEST ONLY · phone speaker (debug build)";
            }
            names.add(name);
            if (HearingOutputs.matches(type,address,product,d.getType(),d.getAddress(),d.getProductName().toString()))
                selection=names.size()-1;
        }
        if (names.isEmpty()) names.add("No hearing aid connected");
        outputs.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names));
        outputs.setSelection(selection); updateButtons();
        if (BuildConfig.DEBUG) maybeAutostart();
    }
    private void updateButtons() {
        if (outputs==null || status==null) return;
        int index=outputs.getSelectedItemPosition();
        SharedPreferences prefs=getSharedPreferences("relay",MODE_PRIVATE);
        if (selected!=null) selected=paired.find(selected.fp);
        String aid=index>=0 && index<devices.size()?devices.get(index).getProductName().toString():prefs.getString("outputName","your hearing aids");
        if (running) aid=prefs.getString("outputName",aid);
        if (aid.isEmpty()) aid="your hearing aids";
        AidAvailability.State availability=AidAvailability.State.AVAILABLE;
        if (service!=null && service.needsOutputChoice()) availability=AidAvailability.State.CONNECTED_NOT_ACTIVE;
        else if (devices.isEmpty() || (running && serviceState.startsWith("Waiting for"))) {
            List<AidAvailability.Device> available=new ArrayList<>();
            for (AudioDeviceInfo d:devices) available.add(new AidAvailability.Device(d.getType(),d.getAddress(),d.getProductName().toString()));
            availability=AidAvailability.decide(new AidAvailability.Device(prefs.getInt("outputType",-1),prefs.getString("outputAddress",""),prefs.getString("outputName","")),available,profiles.connected());
        }
        UiState.Service state=!running?UiState.Service.STOPPED:serviceState.equals("Playing")?UiState.Service.PLAYING:
            serviceState.startsWith("Disconnected")?UiState.Service.UNREACHABLE:UiState.Service.CONNECTING;
        String computer=pairing?pairingName:selected!=null?selected.name:found!=null?found.name:host.getText().toString();
        String delay="—"; java.util.regex.Matcher match=java.util.regex.Pattern.compile("Latency ([0-9.]+)").matcher(technicalStats);
        if (match.find()) delay=match.group(1);
        UiState ui=UiState.decide(state,availability,pairing?UiState.Pairing.PAIRING:found!=null?UiState.Pairing.FOUND:UiState.Pairing.SEARCHING,
            selected!=null,computer,aid,delay);
        boolean animations=android.provider.Settings.Global.getFloat(getContentResolver(),android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)!=0f;
        searching.setVisibility(selected==null && found==null && !pairing && animations?View.VISIBLE:View.GONE);
        ring.setState(ui.ring); status.setText(ui.sentence); action=ui.action;
        start.setVisibility(action==UiState.Action.NONE?View.GONE:View.VISIBLE);
        String label=action==UiState.Action.START?"Start":action==UiState.Action.PAIR?"Pair":action==UiState.Action.CHOOSE_OUTPUT?"Choose output":"Stop";
        start.setText(label);
        int icon=action==UiState.Action.START?R.drawable.ic_play:action==UiState.Action.PAIR?R.drawable.ic_link:
            action==UiState.Action.CHOOSE_OUTPUT?R.drawable.ic_headphones:R.drawable.ic_stop;
        start.setCompoundDrawablesWithIntrinsicBounds(icon,0,0,0); start.setCompoundDrawablePadding(dp(12));
        outputPicker.setVisibility(devices.size()>1 && !running?View.VISIBLE:View.GONE);
        manual.setVisibility(ui.showManualAddress?View.VISIBLE:View.GONE);
        bluetoothSettings.setVisibility(ui.showBluetoothSettings?View.VISIBLE:View.GONE);
        stats.setText(technicalStats+"\n"+new RootSettings(this).details()+"\nComputer address: "+(selected==null?"—":selected.lastHost+":"+selected.lastPort));
    }
    private void openOutputSettings() {
        if (Build.VERSION.SDK_INT>=34) {
            try { if (MediaRouter2.getInstance(this).showSystemOutputSwitcher()) return; }
            catch (SecurityException | IllegalStateException unavailable) { /* Use Bluetooth settings below. */ }
        }
        openBluetoothSettings();
    }
    private void openBluetoothSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (ActivityNotFoundException | SecurityException error) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                Toast.makeText(this,"Open Bluetooth settings and select your hearing aid for media",Toast.LENGTH_LONG).show();
            }
        }
    }
    private void maybeAutostart() {
        if (BuildConfig.DEBUG) {
            if (debugAutostart && service!=null) {
                String address=host.getText().toString().trim();
                int number;
                try { number=Integer.parseInt(port.getText().toString()); } catch (NumberFormatException e) { return; }
                selected=null;
                for (PairedComputers.Computer c:paired.list()) if (c.lastHost.equals(address) && c.lastPort==number) selected=c;
                if (selected==null) {
                    if (debugPairConfirm && !pairing) pairComputer(address,number,"Debug computer");
                    return;
                }
                // Only a real hearing-aid output, unless the test explicitly
                // asks for the debug speaker entry (extra test_speaker=true).
                int pick=-1;
                for (int i=devices.size()-1;i>=0;i--) {
                    boolean speaker=devices.get(i).getType()==android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
                    if (speaker==debugTestSpeaker) pick=i;
                }
                if (pick<0) { status.setText("No hearing aid output available"); return; }
                debugAutostart=false; debugAutostartPermissionBypass=true; outputs.setSelection(pick); updateButtons(); startRelay();
            }
        }
    }
    private void startRelay() {
        try {
            if (!(BuildConfig.DEBUG && debugAutostartPermissionBypass)) {
                if (Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) {
                    withNearbyPermission(this::startRelay); return;
                }
                if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("notificationsExplained",false)) {
                    new android.app.AlertDialog.Builder(this).setMessage("Cade can show playback controls in notifications")
                        .setPositiveButton("Continue",(d,w)->{ getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsExplained",true).apply();
                            permissionContinuation=this::startRelay; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2); })
                        .setNegativeButton("Not now",(d,w)->{ getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsExplained",true).apply(); startRelay(); }).show(); return;
                }
            }
            save();
            if (selected==null) throw new IllegalArgumentException("Pair and select a computer first");
            String hostname=selected.lastHost;
            if (hostname.isEmpty()) throw new IllegalArgumentException("Enter a computer address");
            int number=selected.lastPort;
            if (number<1 || number>65535) throw new IllegalArgumentException("Port must be 1–65535");

            int index=outputs.getSelectedItemPosition();
            if (index>=0 && index<devices.size() && devices.get(index).getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                && !(BuildConfig.DEBUG && debugTestSpeaker)) throw new IllegalArgumentException("Choose your hearing aids");
            if (index<0 || index>=devices.size()) throw new IllegalArgumentException("No hearing aid connected");
            Intent intent=new Intent(this,RelayService.class).putExtra("host",hostname).putExtra("port",number)
                .putExtra("pin",selected.fp).putExtra("outputType",devices.get(index).getType())
                .putExtra("outputAddress",devices.get(index).getAddress())
                .putExtra("outputName",devices.get(index).getProductName().toString());
            if (BuildConfig.DEBUG && debugTestSpeaker) intent.putExtra("test_speaker",true);
            startForegroundService(intent);
            running=true; serviceState="Connecting"; updateButtons();
        } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
    }
    @Override protected void onStart() {
        super.onStart(); uiHandler.post(availabilityTick); discovery.start(); refreshComputers(); bound=bindService(new Intent(this,RelayService.class),connection,BIND_AUTO_CREATE);
        manager.registerAudioDeviceCallback(callback,new Handler(Looper.getMainLooper())); refresh();
    }
    @Override protected void onStop() {
        save(); uiHandler.removeCallbacks(availabilityTick); manager.unregisterAudioDeviceCallback(callback);
        if (service!=null) service.setListener(null);
        if (bound) unbindService(connection); bound=false; service=null; super.onStop();
    }
    private void refreshComputers() {
        List<PairedComputers.Computer> saved=paired.list();
        if (selected==null && !saved.isEmpty()) selected=saved.get(0);
        found=null;
        for (Discovery.Computer c:discovery.list()) {
            boolean known=false;
            for (PairedComputers.Computer p:saved) if (c.fp.length()==16 && p.fp.startsWith(c.fp)) known=true;
            if (!known && found==null) found=c;
        }
        updateButtons();
    }
    private void pairComputer(String address,int number,String name) {
        if (pairing) return;
        if (address.isEmpty() || number<1 || number>65535) throw new IllegalArgumentException("Enter a computer address and port 1–65535");
        pairingName=name; pairing=true; updateButtons();
        new Thread(()->{
            try (Receiver receiver=new Receiver(new JitterBuffer())) {
                pairingReceiver=receiver;
                if (destroyed) { receiver.close(); return; }
                PairedComputers.Computer computer=receiver.pair(address,number,name,code->{
                    if (BuildConfig.DEBUG && debugPairConfirm) return true;
                    java.util.concurrent.ArrayBlockingQueue<Boolean> answer=new java.util.concurrent.ArrayBlockingQueue<>(1);
                    final android.app.AlertDialog[] dialog=new android.app.AlertDialog[1];
                    runOnUiThread(()->{
                        if (destroyed || isFinishing() || isDestroyed()) { answer.offer(false); return; }
                        TextView digits=new TextView(this); digits.setText(code); digits.setTextSize(44);
                        digits.setLetterSpacing(.2f); digits.setGravity(Gravity.CENTER); digits.setPadding(dp(16),dp(24),dp(16),dp(24));
                        pairingDialog=dialog[0]=new android.app.AlertDialog.Builder(this).setTitle("Check that "+name+" shows the same code")
                            .setView(digits).setPositiveButton("Codes match",(d,w)->answer.offer(true))
                            .setNegativeButton("Don't match",(d,w)->answer.offer(false))
                            .setOnCancelListener(d->answer.offer(false)).show();
                    });
                    try { return Boolean.TRUE.equals(answer.poll(120,java.util.concurrent.TimeUnit.SECONDS)); }
                    finally { runOnUiThread(()->{ if (dialog[0]!=null) dialog[0].dismiss(); }); }
                });
                paired.put(computer);
                runOnUiThread(()->{ selected=computer; status.setText("Paired"); refreshComputers(); });
            } catch (Exception e) { runOnUiThread(()->{ debugAutostart=false; Toast.makeText(this,"Couldn’t pair. Check that Cale is open and try again.",Toast.LENGTH_LONG).show(); }); }
            finally { pairingReceiver=null; runOnUiThread(()->{ pairing=false; if (!destroyed) { refreshComputers(); maybeAutostart(); } }); }
        },"cade-pair").start();
    }
    private void showRootMode() {
        RootSettings root=new RootSettings(this);
        LinearLayout content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(8),dp(24),dp(16));
        Switch toggle=new Switch(this); toggle.setText("Rooted mode"); toggle.setChecked(root.enabled()); content.addView(toggle);
        TextView description=new TextView(this); description.setText(RootMode.DESCRIPTION); content.addView(description);
        TextView features=new TextView(this); content.addView(features);
        Runnable refresh=()->{
            StringBuilder lines=new StringBuilder();
            if (root.enabled()) for (RootMode.Feature feature:RootMode.FEATURES) {
                if (lines.length()>0) lines.append('\n'); lines.append(feature.description);
            }
            features.setText(lines.toString());
        };
        refresh.run();
        TextView outcome=new TextView(this); content.addView(outcome);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Rooted mode")
            .setView(content).setPositiveButton("Done",null).show();
        toggle.setOnClickListener(v->{
            boolean on=toggle.isChecked(); toggle.setEnabled(false); dialog.setCancelable(false);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            outcome.setText(on?"Requesting root access…":"Turning off…");
            new Thread(()->{
                String failure=root.change(on,RootMode.detectSu());
                runOnUiThread(()->{
                    toggle.setChecked(root.enabled()); toggle.setEnabled(true); refresh.run();
                    outcome.setText(failure==null?"":failure);
                    dialog.setCancelable(true); dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (service!=null) service.rootModeChanged();
                    if (!destroyed) updateButtons();
                });
            },"cade-root-setup").start();
        });
    }
    @Override protected void onDestroy() {
        destroyed=true; pairing=false;
        if (pairingDialog!=null) { pairingDialog.dismiss(); pairingDialog=null; }
        if (discovery!=null) discovery.close();
        if (profiles!=null) profiles.close();
        Receiver r=pairingReceiver; if (r!=null) r.close(); super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results); refresh();
        Runnable next=permissionContinuation; permissionContinuation=null;
        if (next!=null && (request==2 || (results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED))) next.run();
    }
}
