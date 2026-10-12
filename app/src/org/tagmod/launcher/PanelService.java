package org.tagmod.launcher;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;

/**
 * Updates the sleep-screen panel: brings wifi up if needed, fetches weather and the tagmod-server
 * feed (falling back to the last good copies), renders the panel, writes it where surfaceflinger
 * reads the standby image, asks powerkeyd to put it on the e-ink, and schedules the next run.
 */
public class PanelService extends IntentService {
    static final String ACTION_UPDATE = "org.tagmod.launcher.PANEL_UPDATE";
    static final String EXTRA_SHOW = "show";

    static final File IMAGES = new File("/data/local/assets/images");
    static final File STANDBY = new File(IMAGES, "standby-1.png");
    /** Stock sleep art, put there by the boot service; shown when the panel is off. */
    static final File ART = new File("/data/local/assets/art/standby.png");

    private static final String TAG = "tagmod";

    public PanelService() {
        super("PanelService");
    }

    /** Runs an update. show=true also puts the result on screen now if the screen is off. */
    static void start(Context context, boolean show) {
        context.startService(new Intent(context, PanelService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_SHOW, show));
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Onyx refuses app wakelocks while the screen is off; powerkeyd holds a kernel one instead.
        Daemon.stayAwake(120);
        PanelConfig config = PanelConfig.load(this);
        try {
            if (!config.enabled) {
                copy(ART, STANDBY);
                return;
            }
            Bitmap panel = new PanelRenderer().render(PanelSource.collect(this, config));
            writeStandby(panel);
            panel.recycle();
            prefs().edit().putLong("rendered", System.currentTimeMillis()).apply();
            if (intent.getBooleanExtra(EXTRA_SHOW, false)) Daemon.refreshPanel();
        } catch (Exception e) {
            Log.e(TAG, "panel update failed", e);
        } finally {
            schedule(this, config);
            Daemon.release();
        }
    }

    private SharedPreferences prefs() {
        return PanelConfig.prefs(this);
    }

    /** surfaceflinger draws standby images in the panel's native landscape orientation. */
    private static void writeStandby(Bitmap portrait) throws Exception {
        Matrix m = new Matrix();
        m.postRotate(-90);
        Bitmap landscape = Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(), portrait.getHeight(), m, true);
        File tmp = new File(IMAGES, ".standby-1.png.tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            landscape.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
            landscape.recycle();
        }
        publish(tmp);
    }

    private static void copy(File from, File to) throws Exception {
        File tmp = new File(IMAGES, ".standby-1.png.tmp");
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        publish(tmp);
    }

    private static void publish(File tmp) throws Exception {
        if (!tmp.setReadable(true, false) || !tmp.renameTo(STANDBY)) {
            throw new Exception("cannot write " + STANDBY);
        }
    }

    /**
     * Next update at the next multiple of the interval on the clock (:00, :30, ...), run by
     * powerkeyd so it also happens while the device sleeps. With the panel off nothing is scheduled
     * (an old schedule still fires once and finds the panel off).
     */
    static void schedule(Context context, PanelConfig config) {
        if (!config.enabled) return;
        Calendar next = Calendar.getInstance();
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        int minutes = next.get(Calendar.HOUR_OF_DAY) * 60 + next.get(Calendar.MINUTE);
        int target = (minutes / config.intervalMinutes + 1) * config.intervalMinutes;
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.add(Calendar.MINUTE, target);
        Daemon.schedule(next.getTimeInMillis());
    }
}
