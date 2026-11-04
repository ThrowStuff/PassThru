// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent setup state; only explicit switch changes execute root commands. */
final class RootSettings {
    private final SharedPreferences prefs;
    RootSettings(Context context) { prefs=context.getSharedPreferences("root_mode",Context.MODE_PRIVATE); }
    boolean enabled() { return prefs.getBoolean("enabled",false); }
    String details() { return prefs.getString("details","Rooted mode: off"); }
    void record(String text) { prefs.edit().putString("details",text).apply(); }
    String change(boolean on,String su) {
        prefs.edit().putBoolean("enabled",false).apply();
        if (su==null) { record("Rooted mode: su is unavailable"); return details(); }
        if (on) {
            RootMode.Result id=RootMode.run(su,"id");
            if (!id.ok() || !id.output.contains("uid=0")) {
                record("Rooted mode: root access denied — "+id.reason()); return details();
            }
        }
        String failure=null;
        for (RootMode.Feature feature:RootMode.FEATURES) {
            RootMode.Result result=RootMode.run(su,on?feature.setup:feature.teardown);
            if (!result.ok()) { failure=feature.name+": "+result.reason(); break; }
            if (on) {
                result=RootMode.run(su,feature.verify);
                if (!result.ok() || !result.output.matches("(?s).*MEDIA_ROUTING_CONTROL: allow(?:[;\\s].*|$)")) {
                    failure=feature.name+": app-op verification failed — "+result.reason(); break;
                }
            }
        }
        if (on && failure!=null) for (RootMode.Feature feature:RootMode.FEATURES) {
            RootMode.Result rollback=RootMode.run(su,feature.teardown);
            if (!rollback.ok()) failure+="; reset failed: "+rollback.reason();
        }
        prefs.edit().putBoolean("enabled",on && failure==null).apply();
        record(failure==null?(on?"Rooted mode: automatic aid reconnect active":"Rooted mode: off"):
            "Rooted mode: "+failure);
        return failure==null?null:details();
    }
}
