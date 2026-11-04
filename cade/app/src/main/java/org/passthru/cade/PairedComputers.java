// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.content.*;
import org.json.*;
import java.util.*;

public final class PairedComputers {
    public static final class Computer {
        public final String fp,key,name,lastHost;
        public final int lastPort;
        public Computer(String fp,String key,String name,String host,int port) {
            this.fp=fp; this.key=key; this.name=name; lastHost=host; lastPort=port;
        }
        @Override public String toString() { return name+" · "+lastHost; }
    }
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    public PairedComputers(Context context) { prefs=context.getSharedPreferences("paired",Context.MODE_PRIVATE); }
    public List<Computer> list() {
        synchronized (LOCK) {
        List<Computer> result=new ArrayList<>();
        try {
            JSONArray a=new JSONArray(prefs.getString("computers","[]"));
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.getJSONObject(i);
                result.add(new Computer(o.getString("fp"),o.getString("key"),o.getString("name"),
                    o.getString("lastHost"),o.getInt("lastPort")));
            }
        } catch (JSONException e) { throw new IllegalStateException("Invalid pairing store",e); }
        return result;
        }
    }
    public Computer find(String fp) { for (Computer c:list()) if (c.fp.equals(fp)) return c; return null; }
    public void put(Computer computer) {
        synchronized (LOCK) {
        List<Computer> all=list(); all.removeIf(c->c.fp.equals(computer.fp)); all.add(computer); write(all);
        }
    }
    public void forget(String fp) {
        synchronized (LOCK) {
        List<Computer> all=list(); all.removeIf(c->c.fp.equals(fp)); write(all);
        }
    }
    public boolean rememberAddress(String fp,String host,int port) {
        synchronized (LOCK) {
            Computer c=find(fp);
            if (c==null) return false;
            put(new Computer(c.fp,c.key,c.name,host,port)); return true;
        }
    }
    private void write(List<Computer> all) {
        JSONArray a=new JSONArray();
        try { for (Computer c:all) a.put(new JSONObject().put("fp",c.fp).put("key",c.key).put("name",c.name)
            .put("lastHost",c.lastHost).put("lastPort",c.lastPort)); }
        catch (JSONException e) { throw new AssertionError(e); }
        if (!prefs.edit().putString("computers",a.toString()).commit()) throw new IllegalStateException("Cannot save pairing");
    }
}
