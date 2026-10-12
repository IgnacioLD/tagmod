package org.tagmod.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.Date;

/** Collects what the panel shows: weather, tagmod-server feed, current book and battery. */
final class PanelSource {
    private static final String TAG = "tagmod";
    private static final long WIFI_WAIT_MS = 30000;

    private PanelSource() {
    }

    /** Gathers everything the panel shows, preferring fresh data and falling back to the last good copy. */
    static PanelRenderer.Data collect(Context context, PanelConfig config) throws Exception {
        Http.init(context);
        SharedPreferences prefs = PanelConfig.prefs(context);
        PanelRenderer.Data d = new PanelRenderer.Data();

        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        boolean turnedWifiOn = false;
        if (!online(context) && wifi != null && !wifi.isWifiEnabled()) {
            turnedWifiOn = wifi.setWifiEnabled(true);
        }
        try {
            boolean online = waitOnline(context);
            if (online) {
                // The weather is only drawn when nothing else is configured (PanelRenderer).
                boolean dashboard = config.feedUrl != null || config.umamiUrl != null
                        || !StatusTracker.load(context).isEmpty();
                if (!dashboard) try {
                    String[] place = place(config, prefs);
                    String json = Weather.fetchJson(place[0], place[1]);
                    prefs.edit().putString("weather_json", json).putString("weather_place", place[2])
                            .putLong("weather_at", System.currentTimeMillis()).apply();
                } catch (Exception e) {
                    Log.w(TAG, "weather: " + e);
                }
                try {
                    d.services = StatusTracker.check(context);
                } catch (Exception e) {
                    Log.w(TAG, "status: " + e);
                }
                if (config.umamiUrl != null && config.umamiKey != null && config.umamiWeb != null) {
                    try {
                        String json = Umami.fetchJson(config.umamiUrl, config.umamiKey, config.umamiWeb);
                        Umami.parse(json);
                        prefs.edit().putString("umami_json", json).putLong("umami_at", System.currentTimeMillis()).apply();
                    } catch (Exception e) {
                        Log.w(TAG, "umami: " + e);
                    }
                }
                if (config.feedUrl != null) {
                    try {
                        String json = Feed.fetchJson(config.feedUrl, config.feedToken);
                        Feed.parse(json);
                        prefs.edit().putString("feed_json", json).putLong("feed_at", System.currentTimeMillis()).apply();
                    } catch (Exception e) {
                        Log.w(TAG, "feed: " + e);
                    }
                }
            }
        } finally {
            if (turnedWifiOn) wifi.setWifiEnabled(false);
        }

        long staleAfter = config.intervalMinutes * 60000L + 5 * 60000L;
        String weatherJson = prefs.getString("weather_json", null);
        if (weatherJson != null) {
            try {
                d.weather = Weather.parse(weatherJson, prefs.getString("weather_place", null));
                long at = prefs.getLong("weather_at", 0);
                if (System.currentTimeMillis() - at > staleAfter) d.weatherFrom = new Date(at);
            } catch (Exception e) {
                Log.w(TAG, "cached weather: " + e);
            }
        }
        String feedJson = config.feedUrl == null ? null : prefs.getString("feed_json", null);
        if (feedJson != null) {
            try {
                d.feed = Feed.parse(feedJson);
                long at = prefs.getLong("feed_at", 0);
                if (System.currentTimeMillis() - at > staleAfter) d.feedFrom = new Date(at);
            } catch (Exception e) {
                Log.w(TAG, "cached feed: " + e);
            }
        }

        String umamiJson = config.umamiUrl == null ? null : prefs.getString("umami_json", null);
        if (umamiJson != null) {
            try {
                d.umami = Umami.parse(umamiJson);
                long at = prefs.getLong("umami_at", 0);
                if (System.currentTimeMillis() - at > staleAfter) d.umamiFrom = new Date(at);
            } catch (Exception e) {
                Log.w(TAG, "cached umami: " + e);
            }
        }

        // Offline: show the last known state; recording a failure would blame the services.
        if (d.services == null) d.services = StatusTracker.load(context);

        SharedPreferences recents = context.getSharedPreferences("recents", Context.MODE_PRIVATE);
        d.bookTitle = recents.getString("last_title", null);
        d.bookAuthor = recents.getString("last_author", null);

        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            if (level >= 0 && scale > 0) d.battery = level * 100 / scale;
            d.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        d.now = new Date();
        return d;
    }

    /** {lat, lon, name}: the configured place (geocoded once) or the IP location (refreshed daily). */
    private static String[] place(PanelConfig config, SharedPreferences prefs) throws Exception {
        String key = config.location == null ? "ip" : "name:" + config.location;
        long day = 24 * 3600 * 1000L;
        if (key.equals(prefs.getString("place_key", null))
                && (config.location != null || System.currentTimeMillis() - prefs.getLong("place_at", 0) < day)) {
            return new String[] {prefs.getString("place_lat", ""), prefs.getString("place_lon", ""),
                    prefs.getString("place_name", "")};
        }
        String[] p = config.location == null ? Weather.locateByIp() : Weather.geocode(config.location);
        if (p == null) throw new Exception("place not found: " + config.location);
        prefs.edit().putString("place_key", key).putString("place_lat", p[0]).putString("place_lon", p[1])
                .putString("place_name", p[2]).putLong("place_at", System.currentTimeMillis()).apply();
        return p;
    }

    private static boolean online(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    private static boolean waitOnline(Context context) {
        long end = SystemClock.elapsedRealtime() + WIFI_WAIT_MS;
        while (!online(context)) {
            if (SystemClock.elapsedRealtime() > end) return false;
            SystemClock.sleep(500);
        }
        return true;
    }
}
