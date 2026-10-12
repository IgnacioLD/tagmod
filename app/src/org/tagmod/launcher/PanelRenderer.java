package org.tagmod.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Draws the TagMod panel (the sleep screen) for the 758x1024 e-ink display. With a tagmod-server
 * feed it shows agenda, tasks and servers next to the weather; without one, a fuller forecast.
 */
final class PanelRenderer {
    static final int W = 758;
    static final int H = 1024;
    private static final int PAD = 40;
    private static final Locale ES = new Locale("es", "ES");

    static final class Data {
        Weather weather;
        /** Set when the forecast is old because the last updates could not fetch a new one. */
        Date weatherFrom;
        Feed feed;
        Date feedFrom;
        Umami umami;
        Date umamiFrom;
        /** Monitored services; null until loaded. */
        List<StatusTracker.Service> services;
        String bookTitle;
        String bookAuthor;
        int battery = -1;
        boolean charging;
        Date now = new Date();
    }

    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface sans = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private final Typeface sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private final Typeface serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD);
    private Canvas c;
    private Date now;

    PanelRenderer() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Color.BLACK);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setColor(Color.BLACK);
    }

    Bitmap render(Data d) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        c = new Canvas(bmp);
        now = d.now;
        c.drawColor(Color.WHITE);

        List<StatusTracker.Service> services = d.services == null
                ? new ArrayList<StatusTracker.Service>() : d.services;
        // With anything else to show it is a dashboard without weather; the weather is only the
        // fallback panel when nothing else is configured.
        boolean dashboard = d.feed != null || d.umami != null || !services.isEmpty();
        float y = header(d);
        List<StatusTracker.Service> down = new ArrayList<StatusTracker.Service>();
        for (StatusTracker.Service s : services) if (s.checked() && !s.ok()) down.add(s);
        if (!down.isEmpty()) y = downBanner(down, y);

        if (!dashboard) {
            weatherFull(d.weather, y + 10);
            book(d, 846);
        } else {
            // Personal first (agenda, tasks, current book), then technology (analytics, services).
            // Everything has a fixed height except the agenda, which takes what is left.
            float techH = (d.umami == null ? 0 : ANALYTICS_H) + (services.isEmpty() ? 0 : servicesHeight(services));
            if (d.feed != null) {
                float agendaBottom = CONTENT_BOTTOM - techH - BOOK_H - tasksHeight(d.feed);
                if (agendaBottom - y >= AGENDA_MIN) y = agenda(d.feed, y, agendaBottom);
                y = tasks(d.feed, y);
            }
            y = bookRow(d, y, techH > 0);
            if (d.umami != null) y = analytics(d.umami, y);
            if (!services.isEmpty()) services(services, y);
        }
        footer(d);
        c = null;
        return bmp;
    }

    // Sections. Each takes the y where it starts and returns the y of its bottom rule.

    private float header(Data d) {
        float right = W - PAD;
        String weekday = new SimpleDateFormat("EEEE", ES).format(d.now).toUpperCase(ES);
        String date = new SimpleDateFormat("d 'de' MMMM", ES).format(d.now);
        text(weekday, PAD, 72, 26, sansBold, Color.GRAY, Paint.Align.LEFT);
        text(date, PAD, 136, fit(date, 60, 40, sansBold, W - 2 * PAD - 120), sansBold, Color.BLACK, Paint.Align.LEFT);
        if (d.battery >= 0) {
            float bx = right - 46;
            stroke.setStrokeWidth(2.5f);
            c.drawRoundRect(new RectF(bx, 52, bx + 42, 74), 4, 4, stroke);
            fill.setColor(Color.BLACK);
            c.drawRect(bx + 42, 58, bx + 46, 68, fill);
            c.drawRect(bx + 4, 56, bx + 4 + 34 * Math.max(0, Math.min(100, d.battery)) / 100f, 70, fill);
            text(d.battery + "%" + (d.charging ? "+" : ""), bx - 10, 72, 22, sans, Color.BLACK, Paint.Align.RIGHT);
        }
        rule(158);
        return 158;
    }

    private static final float ANALYTICS_H = 236;

    /** Umami: the last 24 h against the 24 before, sessions per day and the main app events. */
    private float analytics(Umami u, float top) {
        text(u.site.toUpperCase(ES) + " · ÚLTIMAS 24 H", PAD, top + 34, 22, sansBold, Color.GRAY, Paint.Align.LEFT);
        text(u.activeNow == 1 ? "1 persona ahora" : u.activeNow + " personas ahora", W - PAD, top + 34, 20,
                u.activeNow > 0 ? sansBold : sans, u.activeNow > 0 ? Color.BLACK : Color.GRAY, Paint.Align.RIGHT);

        String[] labels = {"usuarios", "sesiones", "vistas", "tiempo medio"};
        int[] now = {u.day.visitors, u.day.visits, u.day.pageviews,
                u.day.visits == 0 ? 0 : u.day.totalTime / u.day.visits};
        int[] before = {u.previous.visitors, u.previous.visits, u.previous.pageviews,
                u.previous.visits == 0 ? 0 : u.previous.totalTime / u.previous.visits};
        float col = (W - 2f * PAD) / 4;
        for (int i = 0; i < 4; i++) {
            float x = PAD + col * i;
            String value = i == 3 ? seconds(now[i]) : String.valueOf(now[i]);
            text(value, x, top + 90, 44, sansBold, Color.BLACK, Paint.Align.LEFT);
            float end = text(labels[i], x, top + 116, 18, sans, Color.GRAY, Paint.Align.LEFT);
            delta(now[i] - before[i], i == 3 ? "s" : "", end + 8, top + 116);
        }

        // Sessions per day, last 7 days, today on the right.
        text("SESIONES POR DÍA", PAD, top + 148, 15, sansBold, Color.GRAY, Paint.Align.LEFT);
        float chartTop = top + 154, chartBottom = top + 208, barW = 26, gap = 14;
        int max = 1;
        for (int v : u.dailySessions) max = Math.max(max, v);
        for (int i = 0; i < u.dailySessions.length; i++) {
            float x = PAD + i * (barW + gap);
            float h = (chartBottom - chartTop - 18) * u.dailySessions[i] / max;
            fill.setColor(i == u.dailySessions.length - 1 ? Color.BLACK : Color.GRAY);
            c.drawRect(x, chartBottom - Math.max(2, h), x + barW, chartBottom, fill);
            text(String.valueOf(u.dailySessions[i]), x + barW / 2, chartBottom - Math.max(2, h) - 4, 15, sans,
                    Color.GRAY, Paint.Align.CENTER);
            String day = new SimpleDateFormat("EEEEE", ES).format(u.days[i]).toUpperCase(ES);
            text(day, x + barW / 2, chartBottom + 20, 16, sans, Color.GRAY, Paint.Align.CENTER);
        }
        fill.setColor(Color.BLACK);

        // Top events, right half.
        float ex = PAD + 7 * (barW + gap) + 24, ey = top + 150;
        for (int i = 0; i < u.events.size() && i < 4; i++, ey += 23) {
            Umami.Event e = u.events.get(i);
            float end = text(String.valueOf(e.count), ex + 56, ey, 21, sansBold, Color.BLACK, Paint.Align.RIGHT);
            text(ellipsize(Umami.eventLabel(e.name), 20, sans, W - PAD - end - 12), ex + 66, ey, 20, sans, Color.BLACK,
                    Paint.Align.LEFT);
        }
        float bottom = top + ANALYTICS_H;
        rule(bottom);
        return bottom;
    }

    /** Small up/down triangle with the difference, or "=" when unchanged. */
    private void delta(int diff, String unit, float x, float y) {
        if (diff == 0) {
            text("=", x, y, 18, sans, Color.GRAY, Paint.Align.LEFT);
            return;
        }
        Path t = new Path();
        if (diff > 0) {
            t.moveTo(x, y - 2);
            t.lineTo(x + 12, y - 2);
            t.lineTo(x + 6, y - 13);
        } else {
            t.moveTo(x, y - 13);
            t.lineTo(x + 12, y - 13);
            t.lineTo(x + 6, y - 2);
        }
        t.close();
        fill.setColor(Color.BLACK);
        c.drawPath(t, fill);
        text(Math.abs(diff) + unit, x + 15, y, 18, sansBold, Color.BLACK, Paint.Align.LEFT);
    }

    private static String seconds(int s) {
        if (s < 60) return s + "s";
        return (s / 60) + "m" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    /** Black band listing the services that are down, first one with the time it went down. */
    private float downBanner(List<StatusTracker.Service> down, float top) {
        fill.setColor(Color.BLACK);
        c.drawRect(PAD, top + 12, W - PAD, top + 64, fill);
        StringBuilder sb = new StringBuilder("CAÍDO  ");
        for (int i = 0; i < down.size(); i++) {
            StatusTracker.Service s = down.get(i);
            if (i > 0) sb.append(",  ");
            sb.append(s.name);
            if (i == 0) sb.append(" desde ").append(when(s.downSince()));
        }
        text(ellipsize(sb.toString(), 24, sansBold, W - 2 * PAD - 32), PAD + 16, top + 47, 24, sansBold, Color.WHITE,
                Paint.Align.LEFT);
        return top + 64;
    }

    private void weatherFull(Weather w, float top) {
        if (w == null) {
            text("Sin datos del tiempo", W / 2f, top + 230, 34, sansBold, Color.BLACK, Paint.Align.CENTER);
            text("Se actualizará en cuanto haya wifi.", W / 2f, top + 280, 24, sans, Color.GRAY, Paint.Align.CENTER);
            rule(830);
            return;
        }
        icon(w.code, w.day, PAD + 80, top + 150, 150);
        float tempEnd = text(w.temp + "°", PAD + 180, top + 186, 130, sansBold, Color.BLACK, Paint.Align.LEFT);
        summary(w, tempEnd + 24, top + 100, 32, 48);

        float col = (W - 2f * PAD) / Math.max(1, w.hours.length);
        for (int i = 0; i < w.hours.length; i++) {
            Weather.Hour h = w.hours[i];
            float cx = PAD + col * i + col / 2;
            text(h.label, cx, top + 276, 24, sans, Color.GRAY, Paint.Align.CENTER);
            icon(h.code, h.day, cx, top + 330, 64);
            text(h.temp + "°", cx, top + 408, 32, sansBold, Color.BLACK, Paint.Align.CENTER);
            if (h.rainChance > 0) text(h.rainChance + "% lluvia", cx, top + 440, 20, sans, Color.GRAY, Paint.Align.CENTER);
        }
        rule(top + 470);

        // Next days, between the rules at top + 470 and 830.
        col = (W - 2f * PAD) / Math.max(1, w.days.length);
        for (int i = 0; i < w.days.length; i++) {
            Weather.Day day = w.days[i];
            float cx = PAD + col * i + col / 2;
            text(capitalize(new SimpleDateFormat("EEEE", ES).format(day.date)), cx, top + 514, 26, sansBold,
                    Color.BLACK, Paint.Align.CENTER);
            icon(day.code, true, cx, top + 566, 60);
            text(day.max + "°  /  " + day.min + "°", cx, top + 636, 26, sans, Color.BLACK, Paint.Align.CENTER);
        }
        rule(830);
    }

    /**
     * Condition, max/min and place, right-aligned between x=left and the right margin. The
     * condition shrinks (down to 70%) and then ellipsizes so it never runs into the temperature.
     */
    private void summary(Weather w, float left, float y, float size, float line) {
        float right = W - PAD, width = right - left;
        String desc = Weather.describe(w.code);
        float s = size;
        text.setTypeface(sans);
        while (s > size * 0.7f) {
            text.setTextSize(s);
            if (text.measureText(desc) <= width) break;
            s -= 1;
        }
        text(ellipsize(desc, s, sans, width), right, y, s, sans, Color.BLACK, Paint.Align.RIGHT);
        text("Máx " + w.todayMax + "°   Mín " + w.todayMin + "°", right, y + line, size * 0.86f, sans, Color.BLACK,
                Paint.Align.RIGHT);
        text(ellipsize(w.place == null ? "" : w.place, size * 0.8f, sans, width), right, y + line * 1.9f, size * 0.8f,
                sans, Color.GRAY, Paint.Align.RIGHT);
    }

    /** Room for the agenda title and one event; with less the agenda is left out. */
    private static final float AGENDA_MIN = 100;

    /**
     * Upcoming events, one line each, up to maxBottom. The section is only as tall as its content
     * so tasks and servers get the rest. Returns the y where it ends.
     */
    private float agenda(Feed feed, float top, float maxBottom) {
        text("AGENDA", PAD, top + 22, 22, sansBold, Color.GRAY, Paint.Align.LEFT);
        List<Feed.Event> upcoming = new ArrayList<Feed.Event>();
        for (Feed.Event e : feed.events) {
            Date end = e.end != null ? e.end : e.start;
            if (!end.before(now)) upcoming.add(e);
        }
        float y = top + 64;
        float line = 38;
        if (upcoming.isEmpty()) {
            text("Nada en el calendario", PAD, y, 28, sans, Color.BLACK, Paint.Align.LEFT);
            y += line;
        }
        String lastDay = null;
        int max = (int) ((maxBottom - y - 10) / line) + 1;
        for (int i = 0; i < upcoming.size() && i < max; i++) {
            Feed.Event e = upcoming.get(i);
            String day = dayLabel(e.start);
            if (!day.equals(lastDay)) {
                text(day, PAD, y, 24, sansBold, Color.BLACK, Paint.Align.LEFT);
                lastDay = day;
            }
            String when = e.allDay ? "todo el día" : new SimpleDateFormat("HH:mm", ES).format(e.start);
            text(when, PAD + 170, y, 24, sans, Color.GRAY, Paint.Align.LEFT);
            text(ellipsize(e.title, 28, sans, W - PAD - (PAD + 300)), PAD + 300, y, 28, sans, Color.BLACK,
                    Paint.Align.LEFT);
            y += line;
        }
        float bottom = Math.min(maxBottom, y - line + 22);
        rule(bottom);
        return bottom;
    }

    private static final int TASK_LINES = 2;

    private static float tasksHeight(Feed feed) {
        return 52 + 30 * Math.max(1, Math.min(TASK_LINES, feed.tasks.size()));
    }

    private float tasks(Feed feed, float top) {
        text("TAREAS", PAD, top + 34, 22, sansBold, Color.GRAY, Paint.Align.LEFT);
        if (feed.tasks.size() > TASK_LINES) {
            text("y " + (feed.tasks.size() - TASK_LINES) + " más", W - PAD, top + 34, 20, sans, Color.GRAY,
                    Paint.Align.RIGHT);
        }
        float y = top + 66;
        if (feed.tasks.isEmpty()) text("Nada pendiente", PAD, y, 24, sans, Color.BLACK, Paint.Align.LEFT);
        for (int i = 0; i < feed.tasks.size() && i < TASK_LINES; i++, y += 30) {
            Feed.Task t = feed.tasks.get(i);
            stroke.setStrokeWidth(2.5f);
            c.drawRect(PAD, y - 18, PAD + 18, y, stroke);
            String title = t.title;
            if (t.due != null) title = dayLabel(t.due).toLowerCase(ES) + " · " + title;
            text(ellipsize(title, 24, sans, W - 2 * PAD - 30), PAD + 30, y, 24, sans, Color.BLACK, Paint.Align.LEFT);
        }
        float bottom = top + tasksHeight(feed);
        rule(bottom);
        return bottom;
    }

    private static final int SERVICE_ROW = 30;
    private static final int HOURS = 24;
    private static final int INCIDENT_LINES = 2;

    private static float servicesHeight(List<StatusTracker.Service> services) {
        return 50 + SERVICE_ROW * ((services.size() + 1) / 2) + 20 + 24 * INCIDENT_LINES;
    }

    /**
     * Status page: per service its state, one bar per hour of the last 24 hours and the uptime of
     * the last 7 days, then the latest incidents.
     */
    private float services(List<StatusTracker.Service> services, float top) {
        int up = 0;
        for (StatusTracker.Service s : services) if (s.ok()) up++;
        text("SERVICIOS", PAD, top + 34, 22, sansBold, Color.GRAY, Paint.Align.LEFT);
        text(up + " de " + services.size() + " en marcha · barras: 24 h · %: 7 días", W - PAD, top + 34, 18, sans,
                Color.GRAY, Paint.Align.RIGHT);
        float colW = (W - 2f * PAD - 28) / 2;
        float y = top + 68;
        for (int i = 0; i < services.size(); i++) {
            float x = PAD + (i % 2) * (colW + 28);
            service(services.get(i), x, y + (i / 2) * SERVICE_ROW, colW);
        }
        y += ((services.size() + 1) / 2) * SERVICE_ROW;

        List<StatusTracker.Incident> incidents = StatusTracker.incidents(services);
        y += 2;
        if (incidents.isEmpty()) {
            text("Sin incidencias en los últimos 7 días", PAD, y, 20, sans, Color.GRAY, Paint.Align.LEFT);
        }
        for (int i = 0; i < incidents.size() && i < INCIDENT_LINES; i++, y += 24) {
            StatusTracker.Incident inc = incidents.get(i);
            String span = inc.end == 0
                    ? "caído desde " + when(inc.start)
                    : when(inc.start) + " – " + new SimpleDateFormat("HH:mm", ES).format(new Date(inc.end))
                    + " · " + duration(inc.end - inc.start);
            float end = text(inc.service, PAD, y, 20, sansBold, Color.BLACK, Paint.Align.LEFT);
            text(ellipsize(span, 20, sans, W - PAD - end - 16), end + 16, y, 20, sans, Color.BLACK, Paint.Align.LEFT);
        }
        float bottom = top + servicesHeight(services);
        rule(bottom);
        return bottom;
    }

    private void service(StatusTracker.Service s, float x, float y, float w) {
        boolean down = s.checked() && !s.ok();
        if (!s.checked()) {
            stroke.setStrokeWidth(2);
            c.drawCircle(x + 8, y - 8, 7, stroke);
        } else if (!down) {
            fill.setColor(Color.BLACK);
            c.drawCircle(x + 8, y - 8, 7, fill);
        } else {
            stroke.setStrokeWidth(3.5f);
            c.drawLine(x + 1, y - 15, x + 15, y - 1, stroke);
            c.drawLine(x + 1, y - 1, x + 15, y - 15, stroke);
        }
        float pctW = 56, barW = 3, gap = 1, barsW = HOURS * (barW + gap);
        float barsX = x + w - pctW - barsW;
        Typeface face = down ? sansBold : sans;
        text(ellipsize(s.name, 20, face, barsX - (x + 24) - 8), x + 24, y, 20, face, Color.BLACK, Paint.Align.LEFT);

        // Oldest hour on the left. Up: short grey bar; any failure: tall black bar; no data: a dot.
        int[] hours = s.hourly(now.getTime(), HOURS);
        float bx = barsX;
        for (int state : hours) {
            if (state < 0) {
                fill.setColor(Color.LTGRAY);
                c.drawRect(bx, y - 2, bx + barW, y, fill);
            } else {
                fill.setColor(state == 1 ? Color.GRAY : Color.BLACK);
                c.drawRect(bx, state == 1 ? y - 12 : y - 20, bx + barW, y, fill);
            }
            bx += barW + gap;
        }
        fill.setColor(Color.BLACK);
        text(percent(s.uptime()), x + w, y, 18, face, down ? Color.BLACK : Color.GRAY, Paint.Align.RIGHT);
    }

    /** "100%" when perfect, otherwise one decimal floored so 99.96% never shows as 100%. */
    private static String percent(double p) {
        if (p < 0) return "—";
        if (p >= 100) return "100%";
        return String.format(ES, "%.1f%%", Math.floor(p * 10) / 10);
    }

    /** "17:20" today, "mié 17:20" otherwise. */
    private String when(long time) {
        Date d = new Date(time);
        String hm = new SimpleDateFormat("HH:mm", ES).format(d);
        return dayLabel(d).equals("Hoy") ? hm : new SimpleDateFormat("EEE", ES).format(d) + " " + hm;
    }

    private static String duration(long ms) {
        long min = Math.max(1, ms / 60000);
        if (min < 60) return min + " min";
        long h = min / 60;
        return min % 60 == 0 ? h + " h" : h + " h " + (min % 60) + " min";
    }

    private static final float CONTENT_BOTTOM = H - 44;
    private static final float BOOK_H = 78;

    /** One-line "reading" row; ends with a thick rule when the technology sections follow. */
    private float bookRow(Data d, float top, boolean groupBreak) {
        String line = d.bookTitle == null ? "Ningún libro todavía" : d.bookTitle;
        float x = PAD + 120;
        boolean author = d.bookAuthor != null && d.bookAuthor.length() > 0;
        float base = author ? top + 36 : top + 48;
        text("LEYENDO", PAD, base, 20, sansBold, Color.GRAY, Paint.Align.LEFT);
        text(ellipsize(line, 28, serifBold, W - PAD - x), x, base + 2, 28, serifBold, Color.BLACK, Paint.Align.LEFT);
        if (author) {
            text(ellipsize(d.bookAuthor, 20, sans, W - PAD - x), x, top + 64, 20, sans, Color.GRAY, Paint.Align.LEFT);
        }
        float bottom = top + BOOK_H;
        fill.setColor(Color.BLACK);
        c.drawRect(PAD, bottom, W - PAD, bottom + (groupBreak ? 5 : 2), fill);
        return bottom + (groupBreak ? 3 : 0);
    }

    private void book(Data d, float top) {
        text("LEYENDO", PAD, top + 42, 22, sansBold, Color.GRAY, Paint.Align.LEFT);
        if (d.bookTitle == null) {
            text("Ningún libro todavía", PAD, top + 86, 32, serifBold, Color.BLACK, Paint.Align.LEFT);
            return;
        }
        text(ellipsize(d.bookTitle, 36, serifBold, W - 2 * PAD), PAD, top + 86, 36, serifBold, Color.BLACK,
                Paint.Align.LEFT);
        if (d.bookAuthor != null && d.bookAuthor.length() > 0) {
            text(ellipsize(d.bookAuthor, 24, sans, W - 2 * PAD), PAD, top + 120, 24, sans, Color.BLACK, Paint.Align.LEFT);
        }
    }

    private void footer(Data d) {
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", ES);
        String s = "Actualizado " + hm.format(d.now);
        if (d.weatherFrom != null) s += " · tiempo de las " + hm.format(d.weatherFrom);
        if (d.feedFrom != null) s += " · agenda de las " + hm.format(d.feedFrom);
        text(s, PAD, H - 20, 18, sans, Color.GRAY, Paint.Align.LEFT);
        text("tagmod", W - PAD, H - 20, 18, sansBold, Color.GRAY, Paint.Align.RIGHT);
    }

    // Helpers.

    private String dayLabel(Date date) {
        Calendar a = Calendar.getInstance();
        a.setTime(now);
        Calendar b = Calendar.getInstance();
        b.setTime(date);
        int diff = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 366 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        if (diff == 0) return "Hoy";
        if (diff == 1) return "Mañana";
        return capitalize(new SimpleDateFormat("EEE d", ES).format(date));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void rule(float y) {
        fill.setColor(Color.BLACK);
        c.drawRect(PAD, y, W - PAD, y + 2, fill);
    }

    /** Largest size from max down to min at which s fits in width. */
    private float fit(String s, float max, float min, Typeface face, float width) {
        float size = max;
        while (size > min && textWidth(s, size, face) > width) size -= 1;
        return size;
    }

    private float textWidth(String s, float size, Typeface face) {
        text.setTextSize(size);
        text.setTypeface(face);
        return text.measureText(s);
    }

    private String ellipsize(String s, float size, Typeface face, float width) {
        text.setTextSize(size);
        text.setTypeface(face);
        return TextUtils.ellipsize(s, text, width, TextUtils.TruncateAt.END).toString();
    }

    /** Draws text and returns the x where it ends (for left-aligned text). */
    private float text(String s, float x, float y, float size, Typeface face, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTypeface(face);
        text.setColor(color);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
        return x + text.measureText(s);
    }

    // Weather icons, drawn with strokes so they stay crisp on e-ink. (cx, cy) is the centre, s the size.

    private void icon(int code, boolean day, float cx, float cy, float s) {
        stroke.setStrokeWidth(Math.max(2.5f, s / 22));
        if (code == 0 || code == 1) {
            if (day) sun(cx, cy, s * 0.5f);
            else moon(cx, cy, s * 0.36f);
        } else if (code == 2) {
            if (day) sun(cx + s * 0.16f, cy - s * 0.16f, s * 0.36f);
            else moon(cx + s * 0.18f, cy - s * 0.18f, s * 0.26f);
            cloud(cx - s * 0.08f, cy + s * 0.1f, s * 0.8f, true);
        } else if (code == 3) {
            cloud(cx, cy, s, false);
        } else if (code == 45 || code == 48) {
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * s * 0.2f;
                c.drawLine(cx - s * 0.4f + (i == 0 ? s * 0.1f : 0), y, cx + s * 0.4f, y, stroke);
            }
        } else if (code >= 95) {
            cloud(cx, cy - s * 0.15f, s * 0.9f, false);
            Path bolt = new Path();
            bolt.moveTo(cx + s * 0.05f, cy + s * 0.05f);
            bolt.lineTo(cx - s * 0.1f, cy + s * 0.25f);
            bolt.lineTo(cx + s * 0.05f, cy + s * 0.25f);
            bolt.lineTo(cx - s * 0.08f, cy + s * 0.45f);
            c.drawPath(bolt, stroke);
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            cloud(cx, cy - s * 0.15f, s * 0.9f, false);
            fill.setColor(Color.BLACK);
            for (int i = -1; i <= 1; i++) {
                c.drawCircle(cx + i * s * 0.2f, cy + s * 0.3f + (i == 0 ? s * 0.1f : 0), s * 0.045f, fill);
            }
        } else {
            // Drizzle, rain and showers.
            cloud(cx, cy - s * 0.15f, s * 0.9f, false);
            for (int i = -1; i <= 1; i++) {
                float x = cx + i * s * 0.2f;
                c.drawLine(x, cy + s * 0.2f, x - s * 0.07f, cy + s * 0.42f, stroke);
            }
        }
    }

    private void sun(float cx, float cy, float r) {
        c.drawCircle(cx, cy, r * 0.45f, stroke);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 0.65f, cy + sin * r * 0.65f, cx + cos * r * 0.92f, cy + sin * r * 0.92f, stroke);
        }
    }

    /** Crescent moon: a disc minus an offset disc. */
    private void moon(float cx, float cy, float r) {
        Path disc = new Path();
        disc.addCircle(cx, cy, r, Path.Direction.CW);
        Path bite = new Path();
        bite.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        disc.op(bite, Path.Op.DIFFERENCE);
        c.drawPath(disc, stroke);
    }

    /** Cloud outline; with cover=true it is filled white first so it hides what is behind it. */
    private void cloud(float cx, float cy, float s, boolean cover) {
        Path p = new Path();
        float w = s, h = s * 0.5f;
        float left = cx - w / 2, bottom = cy + h / 2;
        p.moveTo(left + h * 0.4f, bottom);
        p.lineTo(left + w - h * 0.4f, bottom);
        p.arcTo(new RectF(left + w - h * 0.8f, bottom - h * 0.8f, left + w, bottom), 90, -180);
        p.arcTo(new RectF(cx - w * 0.15f, cy - h * 0.95f, cx + w * 0.35f, cy + h * 0.35f), -10, -170);
        p.arcTo(new RectF(left + w * 0.08f, cy - h * 0.5f, cx + w * 0.02f, cy + h * 0.3f), -30, -120);
        p.arcTo(new RectF(left, bottom - h * 0.8f, left + h * 0.8f, bottom), 270, -180);
        p.close();
        if (cover) {
            fill.setColor(Color.WHITE);
            c.drawPath(p, fill);
            fill.setColor(Color.BLACK);
        }
        c.drawPath(p, stroke);
    }
}
