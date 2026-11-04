// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small, one-shot root features. Detection never requests superuser access. */
public final class RootMode {
    public static final String DESCRIPTION="Rooted mode offers a few small quality-of-life improvements.";
    public static final class Feature {
        public final String name,description,setup,teardown,verify;
        Feature(String name,String description,String setup,String teardown,String verify) {
            this.name=name; this.description=description; this.setup=setup; this.teardown=teardown; this.verify=verify;
        }
    }
    public static final List<Feature> FEATURES=Collections.singletonList(new Feature("Automatic aid reconnect",
        "Reconnects your hearing aids automatically — no need to pick them again.",
        "cmd appops set org.passthru.cade MEDIA_ROUTING_CONTROL allow",
        "cmd appops set org.passthru.cade MEDIA_ROUTING_CONTROL default",
        "cmd appops get org.passthru.cade MEDIA_ROUTING_CONTROL"));
    private RootMode() { }
    public static String detectSu() {
        List<String> dirs=new ArrayList<>();
        String path=System.getenv("PATH");
        if (path!=null) dirs.addAll(Arrays.asList(path.split(File.pathSeparator)));
        dirs.addAll(Arrays.asList("/system/bin","/system/xbin","/sbin","/su/bin","/debug_ramdisk"));
        for (String dir:dirs) {
            if (!dir.startsWith("/")) continue;
            File su=new File(dir,"su");
            if (su.isFile() && su.canExecute()) return su.getAbsolutePath();
        }
        return null;
    }
    public static String quote(String argument) { return "'"+argument.replace("'","'\\''")+"'"; }
    // ProcessBuilder passes command as ONE argument. Do not shell-quote the whole command.
    public static List<String> command(String su,String command) { return Arrays.asList(su,"-c",command); }
    public static final class Result {
        public final int exitCode;
        public final String output;
        public Result(int exitCode,String output) { this.exitCode=exitCode; this.output=output; }
        public boolean ok() { return exitCode==0; }
        public String reason() { return output.trim().isEmpty()?"exit "+exitCode:output.replaceAll("[\\r\\n]+"," ").trim(); }
    }
    public static Result run(String su,String command) {
        Process process=null;
        try {
            process=new ProcessBuilder(command(su,command)).redirectErrorStream(true).start();
            Process child=process;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            Thread reader=new Thread(()->{
                try (InputStream in=child.getInputStream()) {
                    byte[] buffer=new byte[1024]; int n;
                    while ((n=in.read(buffer))!=-1) synchronized(bytes) {
                        if (bytes.size()<8192) bytes.write(buffer,0,Math.min(n,8192-bytes.size()));
                    }
                } catch (IOException ignored) { }
            },"cade-su-output");
            reader.setDaemon(true); reader.start();
            boolean done=process.waitFor(5,TimeUnit.SECONDS);
            if (!done) process.destroyForcibly();
            reader.join(100);
            synchronized(bytes) {
                return new Result(done?process.exitValue():-1,done?new String(bytes.toByteArray(),StandardCharsets.UTF_8):"Root command timed out (5 s)");
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(-1,error.getClass().getSimpleName()+": "+error.getMessage());
        } finally { if (process!=null) { process.destroy(); try { process.getInputStream().close(); } catch (IOException ignored) { } } }
    }
    public enum Action { NONE, TRANSFER, NOTIFY }
    public static Action decide(boolean rootOn,boolean attempted,boolean enabled,AidAvailability.State state) {
        if (!enabled || attempted || state!=AidAvailability.State.CONNECTED_NOT_ACTIVE) return Action.NONE;
        return rootOn?Action.TRANSFER:Action.NOTIFY;
    }
    public static Action afterTransfer(boolean success) { return success?Action.NONE:Action.NOTIFY; }
    public static boolean matchesRoute(int savedType,String savedName,int routeType,String routeName,boolean system) {
        return system && (savedType==23 || savedType==26 || savedType==33)
            && savedType==routeType && savedName!=null && !savedName.isEmpty() && savedName.equals(routeName);
    }
}
