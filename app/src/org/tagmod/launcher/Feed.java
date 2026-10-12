package org.tagmod.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data served by tagmod-server (server/ in this repository) as feed.json, version 1:
 *
 *   {"version": 1, "generated": <epoch s>,
 *    "events":  [{"start": <epoch s>, "end": <epoch s>, "all_day": bool, "title": str, "calendar": str}],
 *    "tasks":   [{"title": str, "due": <epoch s> | null, "source": str}]}
 */
final class Feed {
    static final class Event {
        Date start;
        Date end;
        boolean allDay;
        String title;
        String calendar;
    }

    static final class Task {
        String title;
        Date due;
    }

    Date generated;
    final List<Event> events = new ArrayList<Event>();
    final List<Task> tasks = new ArrayList<Task>();

    static String fetchJson(String url, String token) throws Exception {
        Map<String, String> headers = new HashMap<String, String>();
        if (token != null && token.length() > 0) headers.put("Authorization", "Bearer " + token);
        return Http.get(url, headers);
    }

    static Feed parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (o.optInt("version") != 1) throw new Exception("unsupported feed version " + o.opt("version"));
        Feed f = new Feed();
        f.generated = date(o, "generated");
        JSONArray a = o.optJSONArray("events");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject e = a.getJSONObject(i);
            Event ev = new Event();
            ev.start = date(e, "start");
            ev.end = date(e, "end");
            ev.allDay = e.optBoolean("all_day");
            ev.title = e.optString("title");
            ev.calendar = e.optString("calendar");
            if (ev.start != null) f.events.add(ev);
        }
        a = o.optJSONArray("tasks");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject t = a.getJSONObject(i);
            Task task = new Task();
            task.title = t.optString("title");
            task.due = date(t, "due");
            f.tasks.add(task);
        }
        return f;
    }

    private static Date date(JSONObject o, String key) {
        if (o.isNull(key) || !o.has(key)) return null;
        return new Date(o.optLong(key) * 1000L);
    }
}
