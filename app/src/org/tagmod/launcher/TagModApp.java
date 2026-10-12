package org.tagmod.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * When the screen turns off, surfaceflinger shows the last panel image. If it is older than a few
 * minutes, render a fresh one and put it on the panel (screen-off broadcasts can only be received
 * by a registered receiver, so this lives for the whole process).
 */
public class TagModApp extends Application {
    private static final long FRESH_MS = 5 * 60 * 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                PanelConfig config = PanelConfig.load(context);
                long rendered = PanelConfig.prefs(context).getLong("rendered", 0);
                if (config.enabled && System.currentTimeMillis() - rendered > FRESH_MS) {
                    Daemon.stayAwake(120);
                    PanelService.start(context, true);
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        PanelService.schedule(this, PanelConfig.load(this));
    }
}
