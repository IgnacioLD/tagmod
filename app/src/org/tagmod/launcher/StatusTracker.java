package org.tagmod.launcher;

import android.content.Context;
import android.os.Environment;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Uptime monitor run from the e-reader itself, like a status page. Services come from
 * TagMod/servicios.txt ("name = url [expected codes]"); each panel update checks them all and
 * every result is kept for a week, for uptime percentages, hourly bars and the incident list.
 */
final class StatusTracker {
    static final long WEEK_MS = 7L * 24 * 3600 * 1000;
    private static final long HOUR_MS = 3600 * 1000;
    private static final int TIMEOUT_MS = 10000;
    private static final String HISTORY_FILE = "status.tsv";

    private static final String TEMPLATE =
            "# TagMod - servicios que vigila el libro en cada actualización del panel.\n"
            + "# Una línea por servicio:  nombre = url  [códigos esperados]\n"
            + "# Sin códigos, cualquier respuesta 2xx o 3xx cuenta como funcionando.\n"
            + "#\n"
            + "# Web = https://midominio.es\n"
            + "# API = https://api.midominio.es/health\n"
            + "# Privado = https://privado.midominio.es 401\n";

    static final class Check {
        final long time;
        final boolean ok;

        Check(long time, boolean ok) {
            this.time = time;
            this.ok = ok;
        }
    }

    static final class Service {
        String name;
        String url;
        final Set<Integer> expected = new HashSet<Integer>();
        /** Checks of the last week, oldest first. */
        final List<Check> checks = new ArrayList<Check>();
        /** Short description of the latest check ("123 ms", "HTTP 502", "sin respuesta"). */
        String detail;

        boolean checked() {
            return !checks.isEmpty();
        }

        boolean ok() {
            return checks.isEmpty() || checks.get(checks.size() - 1).ok;
        }

        /** Start of the current outage, or 0 when up. */
        long downSince() {
            long since = 0;
            for (int i = checks.size() - 1; i >= 0 && !checks.get(i).ok; i--) since = checks.get(i).time;
            return since;
        }

        /** Percentage of successful checks in the last week, or -1 without checks. */
        double uptime() {
            if (checks.isEmpty()) return -1;
            int up = 0;
            for (Check c : checks) if (c.ok) up++;
            return up * 100.0 / checks.size();
        }

        /**
         * One state per hour for the last {@code hours} hours, oldest first:
         * -1 no checks, 1 all up, 0 at least one failure.
         */
        int[] hourly(long now, int hours) {
            int[] state = new int[hours];
            for (int i = 0; i < hours; i++) state[i] = -1;
            long start = now - hours * HOUR_MS;
            for (Check c : checks) {
                if (c.time <= start || c.time > now) continue;
                int i = (int) Math.min(hours - 1, (c.time - start) / HOUR_MS);
                state[i] = state[i] == 0 || !c.ok ? 0 : 1;
            }
            return state;
        }
    }

    /** A run of failed checks: from the first failure to the first success after it (0 if ongoing). */
    static final class Incident {
        final String service;
        final long start;
        final long end;

        Incident(String service, long start, long end) {
            this.service = service;
            this.start = start;
            this.end = end;
        }
    }

    private StatusTracker() {
    }

    static File file() {
        return new File(new File(Environment.getExternalStorageDirectory(), "TagMod"), "servicios.txt");
    }

    /** Configured services with their stored history (no network). */
    static List<Service> load(Context context) {
        List<Service> services = parse();
        Map<String, List<Check>> history = readHistory(context);
        for (Service s : services) {
            List<Check> h = history.get(s.url);
            if (h != null) s.checks.addAll(h);
        }
        return services;
    }

    /** Answers 204 when the device really is online (Android's own connectivity check). */
    private static final String CONNECTIVITY_URL = "http://connectivitycheck.gstatic.com/generate_204";
    private static final long RETRY_DELAY_MS = 5000;

    /**
     * Checks every service in parallel, records the results and returns the services. Like a status
     * page, a failure only counts if a retry a few seconds later fails too. When the device itself
     * is offline (no connectivity answer, or every single service failing at once, typically DNS
     * right after wifi comes up) nothing is recorded, so its own outages never count against the
     * services.
     */
    static List<Service> check(Context context) throws Exception {
        Http.init(context);
        final List<Service> services = load(context);
        if (Http.status(CONNECTIVITY_URL, TIMEOUT_MS) != 204) {
            throw new Exception("no internet connection, nothing recorded");
        }
        final long now = System.currentTimeMillis();
        final Map<Service, Boolean> results = new HashMap<Service, Boolean>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> jobs = new ArrayList<Future<?>>();
            for (final Service s : services) {
                jobs.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = checkOne(s);
                        if (!ok) {
                            SystemClock.sleep(RETRY_DELAY_MS);
                            ok = checkOne(s);
                        }
                        synchronized (results) {
                            results.put(s, ok);
                        }
                    }
                }));
            }
            for (Future<?> f : jobs) f.get(TIMEOUT_MS * 4, TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
        if (services.size() > 1 && !results.containsValue(true)) {
            throw new Exception("every service failed: device connectivity, nothing recorded");
        }
        for (Service s : services) {
            s.checks.add(new Check(now, results.get(s)));
            while (!s.checks.isEmpty() && s.checks.get(0).time < now - WEEK_MS) s.checks.remove(0);
        }
        writeHistory(context, services);
        return services;
    }

    /** Incidents of the last week across all services, most recent first. */
    static List<Incident> incidents(List<Service> services) {
        List<Incident> all = new ArrayList<Incident>();
        for (Service s : services) {
            long start = 0;
            for (Check c : s.checks) {
                if (!c.ok && start == 0) start = c.time;
                if (c.ok && start != 0) {
                    all.add(new Incident(s.name, start, c.time));
                    start = 0;
                }
            }
            if (start != 0) all.add(new Incident(s.name, start, 0));
        }
        Collections.sort(all, new Comparator<Incident>() {
            @Override
            public int compare(Incident a, Incident b) {
                // Ongoing incidents first, then by start time, newest first.
                if ((a.end == 0) != (b.end == 0)) return a.end == 0 ? -1 : 1;
                return a.start == b.start ? 0 : a.start > b.start ? -1 : 1;
            }
        });
        return all;
    }

    private static boolean checkOne(Service s) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            int code = Http.status(s.url, TIMEOUT_MS);
            boolean ok = s.expected.isEmpty() ? code >= 200 && code < 400 : s.expected.contains(code);
            s.detail = ok ? (SystemClock.elapsedRealtime() - t0) + " ms" : "HTTP " + code;
            return ok;
        } catch (java.net.SocketTimeoutException e) {
            s.detail = "sin respuesta";
        } catch (java.net.UnknownHostException e) {
            s.detail = "DNS";
        } catch (javax.net.ssl.SSLException e) {
            s.detail = "TLS";
        } catch (Exception e) {
            s.detail = e.getClass().getSimpleName().replace("Exception", "");
        }
        return false;
    }

    // History file: one "url<TAB>epoch ms<TAB>1|0" line per check, in app-private storage.

    private static Map<String, List<Check>> readHistory(Context context) {
        Map<String, List<Check>> history = new HashMap<String, List<Check>>();
        File f = new File(context.getFilesDir(), HISTORY_FILE);
        if (!f.exists()) return history;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                List<Check> list = history.get(p[0]);
                if (list == null) history.put(p[0], list = new ArrayList<Check>());
                list.add(new Check(Long.parseLong(p[1]), "1".equals(p[2])));
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        return history;
    }

    private static void writeHistory(Context context, List<Service> services) throws Exception {
        File tmp = new File(context.getFilesDir(), HISTORY_FILE + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            for (Service s : services) {
                for (Check c : s.checks) w.write(s.url + "\t" + c.time + "\t" + (c.ok ? "1" : "0") + "\n");
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(new File(context.getFilesDir(), HISTORY_FILE))) throw new Exception("cannot save status history");
    }

    private static List<Service> parse() {
        List<Service> services = new ArrayList<Service>();
        File f = file();
        if (!f.exists()) {
            writeTemplate(f);
            return services;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.startsWith("#") || eq <= 0) continue;
                String[] rest = line.substring(eq + 1).trim().split("\\s+");
                if (!rest[0].startsWith("http")) continue;
                Service s = new Service();
                s.name = line.substring(0, eq).trim();
                s.url = rest[0];
                for (int i = 1; i < rest.length; i++) {
                    try {
                        s.expected.add(Integer.parseInt(rest[i]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                services.add(s);
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        return services;
    }

    private static void writeTemplate(File f) {
        Writer w = null;
        try {
            f.getParentFile().mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(TEMPLATE);
        } catch (Exception ignored) {
        } finally {
            close(w);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
