// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.function.Consumer;

/** Main-thread bounded attempt. Playback still requires Player's route confirmation. */
final class RootAidTransfer {
    private final Handler main=new Handler(Looper.getMainLooper());
    private MediaRouter2 router;
    private MediaRouter2.TransferCallback callback;
    private Consumer<String> completion;
    private final Runnable timeout=()->finish("transfer timed out");
    void start(Context context,int type,String name,Consumer<String> completion) {
        this.completion=completion;
        if (Build.VERSION.SDK_INT<35) { finish("proxy routing requires Android 15 (API 35)"); return; }
        try {
            router=MediaRouter2.getInstance(context,context.getPackageName(),context.getMainExecutor(),
                ()->finish("proxy routing permission lost"));
            MediaRoute2Info selected=null;
            for (MediaRoute2Info route:router.getSystemController().getTransferableRoutes()) {
                if (!RootMode.matchesRoute(type,name,route.getType(),route.getName().toString(),route.isSystemRoute())) continue;
                if (selected!=null) { finish("saved aid matches multiple routes"); return; }
                selected=route;
            }
            if (selected==null) { finish("saved aid has no transferable system route"); return; }
            final String target=selected.getId();
            callback=new MediaRouter2.TransferCallback() {
                @Override public void onTransfer(MediaRouter2.RoutingController oldController,MediaRouter2.RoutingController newController) {
                    for (MediaRoute2Info route:newController.getSelectedRoutes())
                        if (target.equals(route.getId())) { finish(null); return; }
                    finish("transfer did not select the saved aid");
                }
                @Override public void onTransferFailure(MediaRoute2Info route) { finish("Android rejected the transfer"); }
            };
            router.registerTransferCallback(context.getMainExecutor(),callback);
            main.postDelayed(timeout,5000);
            router.transferTo(selected);
        } catch (RuntimeException | LinkageError error) { finish(error.getClass().getSimpleName()+": "+error.getMessage()); }
    }
    private void finish(String failure) {
        Consumer<String> done=completion;
        cancel();
        if (done!=null) done.accept(failure);
    }
    void cancel() {
        completion=null; main.removeCallbacks(timeout);
        if (router!=null && callback!=null) {
            try { router.unregisterTransferCallback(callback); } catch (RuntimeException ignored) { }
        }
        router=null; callback=null;
    }
}
