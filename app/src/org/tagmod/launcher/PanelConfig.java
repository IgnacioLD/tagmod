package org.tagmod.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * Panel settings. Text values (location, server and Umami access) live in TagMod/panel.txt on the
 * internal storage so they can be edited from a computer over USB; the switches that the button
 * can change live in SharedPreferences.
 */
final class PanelConfig {
    static final int[] INTERVALS = {15, 30, 60, 120};

    private static final String TEMPLATE =
            "# TagMod - panel de reposo\n"
            + "# Edita este fichero desde el ordenador (USB). Se lee en cada actualización.\n"
            + "\n"
            + "# Ubicación para el tiempo, por ejemplo: Valencia. Vacío = automática por la IP.\n"
            + "ubicacion=\n"
            + "\n"
            + "# Servidor tagmod-server en tu VPS: agenda, tareas y estado de servidores.\n"
            + "# Ejemplo: https://tagmod.midominio.es/feed.json (o file:///sdcard/TagMod/feed.json)\n"
            + "feed_url=\n"
            + "feed_token=\n"
            + "\n"
            + "# Analítica de Umami: URL de tu Umami, una API key y el dominio (o nombre) de la web.\n"
            + "umami_url=\n"
            + "umami_key=\n"
            + "umami_web=\n";

    final boolean enabled;
    final int intervalMinutes;
    final String location;
    final String feedUrl;
    final String feedToken;
    final String umamiUrl;
    final String umamiKey;
    final String umamiWeb;

    private PanelConfig(boolean enabled, int intervalMinutes, Map<String, String> file) {
        this.enabled = enabled;
        this.intervalMinutes = intervalMinutes;
        this.location = value(file, "ubicacion");
        this.feedUrl = value(file, "feed_url");
        this.feedToken = value(file, "feed_token");
        this.umamiUrl = value(file, "umami_url");
        this.umamiKey = value(file, "umami_key");
        this.umamiWeb = value(file, "umami_web");
    }

    static File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), "TagMod"), "panel.txt");
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("panel", Context.MODE_PRIVATE);
    }

    static PanelConfig load(Context context) {
        SharedPreferences p = prefs(context);
        return new PanelConfig(p.getBoolean("enabled", true), p.getInt("interval", 30), readFile());
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
    }

    static int nextInterval(Context context) {
        int current = load(context).intervalMinutes;
        int next = INTERVALS[0];
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) next = INTERVALS[(i + 1) % INTERVALS.length];
        }
        prefs(context).edit().putInt("interval", next).apply();
        return next;
    }

    private static String value(Map<String, String> file, String key) {
        String v = file.get(key);
        return v == null || v.trim().length() == 0 ? null : v.trim();
    }

    /** Reads key=value lines, creating the commented template on first use. */
    private static Map<String, String> readFile() {
        Map<String, String> values = new HashMap<String, String>();
        File f = file();
        if (!f.exists()) {
            writeTemplate(f);
            return values;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
        return values;
    }

    private static void writeTemplate(File f) {
        Writer w = null;
        try {
            f.getParentFile().mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(TEMPLATE);
        } catch (Exception ignored) {
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
