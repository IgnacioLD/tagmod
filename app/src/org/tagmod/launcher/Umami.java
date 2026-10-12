package org.tagmod.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Website analytics from a self-hosted Umami (API key as a Bearer token): the last 24 hours against
 * the 24 before, people online now, sessions per day for a week and the top custom events.
 */
final class Umami {
    static final class Stats {
        int visitors, visits, pageviews, totalTime;
    }

    static final class Event {
        String name;
        int count;
    }

    String site;
    Stats day = new Stats();
    Stats previous = new Stats();
    int activeNow;
    /** Sessions per day, oldest first, ending today. */
    int[] dailySessions = new int[0];
    Date[] days = new Date[0];
    final List<Event> events = new ArrayList<Event>();

    /** Fetches everything as one JSON document, so the last good copy can be cached and reused. */
    static String fetchJson(String baseUrl, String key, String domain) throws Exception {
        String base = baseUrl.replaceAll("/+$", "") + "/api";
        Map<String, String> auth = new HashMap<String, String>();
        auth.put("Authorization", "Bearer " + key);

        String id = null, name = domain;
        JSONArray sites = new JSONObject(Http.get(base + "/websites", auth)).getJSONArray("data");
        for (int i = 0; i < sites.length(); i++) {
            JSONObject s = sites.getJSONObject(i);
            if (domain.equalsIgnoreCase(s.optString("domain")) || domain.equalsIgnoreCase(s.optString("name"))) {
                id = s.getString("id");
                name = s.optString("name", domain);
            }
        }
        if (id == null) throw new Exception("umami: no website " + domain);

        long now = System.currentTimeMillis(), day = 24 * 3600 * 1000L;
        String w = base + "/websites/" + id;
        String tz = URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8");
        JSONObject out = new JSONObject();
        out.put("site", name);
        out.put("stats", new JSONObject(Http.get(w + "/stats?startAt=" + (now - day) + "&endAt=" + now, auth)));
        out.put("active", new JSONObject(Http.get(w + "/active", auth)).optInt("visitors"));
        out.put("series", new JSONObject(Http.get(w + "/pageviews?unit=day&timezone=" + tz
                + "&startAt=" + startOfDay(now - 6 * day) + "&endAt=" + now, auth)));
        out.put("events", new JSONArray(Http.get(w + "/metrics?type=event&limit=12&startAt=" + (now - day)
                + "&endAt=" + now, auth)));
        return out.toString();
    }

    static Umami parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        Umami u = new Umami();
        u.site = o.optString("site");
        JSONObject s = o.getJSONObject("stats");
        u.day = stats(s);
        u.previous = stats(s.optJSONObject("comparison"));
        u.activeNow = o.optInt("active");

        // Sessions per day for the last 7 days, filling days without data with zero.
        Map<String, Integer> byDay = new HashMap<String, Integer>();
        JSONArray sessions = o.getJSONObject("series").optJSONArray("sessions");
        for (int i = 0; sessions != null && i < sessions.length(); i++) {
            JSONObject p = sessions.getJSONObject(i);
            byDay.put(p.getString("x").substring(0, 10), p.optInt("y"));
        }
        SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        long day = 24 * 3600 * 1000L, today = startOfDay(System.currentTimeMillis());
        u.dailySessions = new int[7];
        u.days = new Date[7];
        for (int i = 0; i < 7; i++) {
            u.days[i] = new Date(today - (6 - i) * day);
            Integer v = byDay.get(key.format(u.days[i]));
            u.dailySessions[i] = v == null ? 0 : v;
        }

        JSONArray ev = o.optJSONArray("events");
        for (int i = 0; ev != null && i < ev.length(); i++) {
            Event e = new Event();
            e.name = ev.getJSONObject(i).optString("x");
            e.count = ev.getJSONObject(i).optInt("y");
            u.events.add(e);
        }
        return u;
    }

    /** Readable Spanish names for known custom events; others are shown with spaces. */
    static String eventLabel(String name) {
        if ("app_open".equals(name)) return "aperturas";
        if ("exercise_completed".equals(name)) return "ejercicios";
        if ("score_attempt".equals(name)) return "intentos";
        if ("lesson_started".equals(name)) return "lecciones empezadas";
        if ("lesson_completed".equals(name)) return "lecciones completadas";
        if ("lesson_step".equals(name)) return "pasos de lección";
        if ("session_end".equals(name)) return "sesiones cerradas";
        if ("flow_abandoned".equals(name)) return "abandonos";
        return name.replace('_', ' ');
    }

    private static Stats stats(JSONObject o) {
        Stats s = new Stats();
        if (o == null) return s;
        s.visitors = value(o, "visitors");
        s.visits = value(o, "visits");
        s.pageviews = value(o, "pageviews");
        s.totalTime = value(o, "totaltime");
        return s;
    }

    /** Umami v2 nests values as {"value": n}; newer versions use plain numbers. */
    private static int value(JSONObject o, String key) {
        JSONObject nested = o.optJSONObject(key);
        return nested != null ? nested.optInt("value") : o.optInt(key);
    }

    private static long startOfDay(long time) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(time);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
