package org.tagmod.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Location and forecast from free, keyless services (ip-api.com and Open-Meteo). */
final class Weather {
    static final class Hour {
        String label;
        int temp;
        int code;
        boolean day;
        int rainChance;
    }

    static final class Day {
        Date date;
        int code;
        int max;
        int min;
    }

    String place;
    int temp;
    int code;
    boolean day;
    int todayMax;
    int todayMin;
    Hour[] hours = new Hour[0];
    Day[] days = new Day[0];

    /** {lat, lon, name} for a place name via Open-Meteo geocoding, or null. */
    static String[] geocode(String name) throws Exception {
        JSONObject o = new JSONObject(get("http://geocoding-api.open-meteo.com/v1/search?count=1&language=es&name="
                + URLEncoder.encode(name, "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.getJSONObject(0);
        return new String[] {r.getString("latitude"), r.getString("longitude"), r.getString("name")};
    }

    /** {lat, lon, city} from the public IP address. */
    static String[] locateByIp() throws Exception {
        JSONObject o = new JSONObject(get("http://ip-api.com/json/?fields=status,city,lat,lon"));
        if (!"success".equals(o.optString("status"))) throw new Exception("ip-api: " + o);
        return new String[] {o.getString("lat"), o.getString("lon"), o.getString("city")};
    }

    static String fetchJson(String lat, String lon) throws Exception {
        return get("http://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,precipitation_probability,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=4");
    }

    static Weather parse(String json, String place) throws Exception {
        JSONObject o = new JSONObject(json);
        Weather w = new Weather();
        w.place = place;
        JSONObject current = o.getJSONObject("current");
        w.temp = (int) Math.round(current.getDouble("temperature_2m"));
        w.code = current.getInt("weather_code");
        w.day = current.optInt("is_day", 1) == 1;
        String now = current.getString("time");

        // Hourly times are local ISO strings ("2026-10-08T17:00"), comparable as text.
        JSONObject hourly = o.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        int start = 0;
        while (start < times.length() && times.getString(start).compareTo(now) <= 0) start++;
        int count = 0;
        Hour[] hours = new Hour[4];
        for (int i = start + 2; i < times.length() && count < hours.length; i += 3) {
            Hour h = new Hour();
            h.label = times.getString(i).substring(11, 16);
            h.temp = (int) Math.round(hourly.getJSONArray("temperature_2m").getDouble(i));
            h.code = hourly.getJSONArray("weather_code").getInt(i);
            h.rainChance = hourly.getJSONArray("precipitation_probability").optInt(i, 0);
            JSONArray isDay = hourly.optJSONArray("is_day");
            h.day = isDay == null || isDay.optInt(i, 1) == 1;
            hours[count++] = h;
        }
        w.hours = Arrays.copyOf(hours, count);

        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        JSONObject daily = o.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        w.todayMax = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
        w.todayMin = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
        w.days = new Day[Math.max(0, dates.length() - 1)];
        for (int i = 1; i < dates.length(); i++) {
            Day d = new Day();
            d.date = day.parse(dates.getString(i));
            d.code = daily.getJSONArray("weather_code").getInt(i);
            d.max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i));
            d.min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i));
            w.days[i - 1] = d;
        }
        return w;
    }

    /** WMO weather interpretation code, in Spanish. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Despejado";
            case 1: return "Mayormente despejado";
            case 2: return "Parcialmente nublado";
            case 3: return "Nublado";
            case 45: case 48: return "Niebla";
            case 51: case 53: case 55: return "Llovizna";
            case 56: case 57: return "Llovizna helada";
            case 61: return "Lluvia débil";
            case 63: return "Lluvia";
            case 65: return "Lluvia fuerte";
            case 66: case 67: return "Lluvia helada";
            case 71: return "Nieve débil";
            case 73: return "Nieve";
            case 75: return "Nieve fuerte";
            case 77: return "Granizo fino";
            case 80: case 81: case 82: return "Chubascos";
            case 85: case 86: return "Chubascos de nieve";
            case 95: return "Tormenta";
            case 96: case 99: return "Tormenta con granizo";
            default: return "";
        }
    }

    private static String get(String url) throws Exception {
        return Http.get(url);
    }
}
