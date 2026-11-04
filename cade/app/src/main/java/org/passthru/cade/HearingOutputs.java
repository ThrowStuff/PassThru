// SPDX-License-Identifier: GPL-3.0-only
package org.passthru.cade;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

public final class HearingOutputs {
    private HearingOutputs() {}
    public static boolean accepts(int type, int sdk) {
        return type==23 || type==26 || (sdk>=37 && type==33);
    }
    // Same output type and either the same address or the same product name.
    // An LE Audio pair may report either aid's address depending on which
    // connected first, so address alone would miss the same pair.
    public static boolean matches(int savedType,String savedAddress,String savedName,
                                  int type,String address,String productName) {
        if (savedType!=type) return false;
        boolean sameAddress=!savedAddress.isEmpty() && savedAddress.equals(address);
        boolean sameName=!savedName.isEmpty() && savedName.equals(productName);
        return sameAddress || sameName;
    }
    public static List<AudioDeviceInfo> list(AudioManager manager) {
        List<AudioDeviceInfo> out=new ArrayList<>();
        for (AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (accepts(d.getType(),Build.VERSION.SDK_INT)) out.add(d);
            if (BuildConfig.DEBUG) {
                if (d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) out.add(d);
            }
        }
        return out;
    }
}
