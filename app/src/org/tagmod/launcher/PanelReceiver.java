package org.tagmod.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Renders the panel after boot, which also schedules the following updates. */
public class PanelReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Daemon.stayAwake(120);
            PanelService.start(context, false);
        }
    }
}
