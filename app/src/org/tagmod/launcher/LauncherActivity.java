package org.tagmod.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Home screen driven by a single button. powerkeyd turns the power button into
 * PAGE_DOWN (tap), PAGE_UP (double tap) and BACK (hold); here BACK opens the selection.
 */
public class LauncherActivity extends Activity {
    private static final String ONYX_READER = "com.onyx.reader";
    private static final String AL_READER = "com.neverland.oreader";
    private static final Set<String> ONYX_FORMATS = new HashSet<String>(Arrays.asList("epub", "pdf", "djvu", "cbz"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Library library;
    private LibraryView view;
    private List<Book> books = new ArrayList<Book>();
    private final List<LibraryView.Row> rows = new ArrayList<LibraryView.Row>();
    private String selectedPath;
    private boolean scanning;
    private boolean rescanPending;

    private final Action light = new Action() {
        @Override public String title() { return "Luz: " + levelName(Frontlight.level()); }
        @Override public String subtitle() { return "Mantener para cambiar la intensidad"; }
        @Override public void run() { Frontlight.cycle(); }
    };
    private final Action panel = new Action() {
        @Override public String title() { return "Panel en reposo: " + (PanelConfig.load(LauncherActivity.this).enabled ? "activado" : "desactivado"); }
        @Override public String subtitle() { return "Agenda, analítica y servicios al dormir el libro"; }
        @Override public void run() {
            PanelConfig.setEnabled(LauncherActivity.this, !PanelConfig.load(LauncherActivity.this).enabled);
            PanelService.start(LauncherActivity.this, false);
        }
    };
    private final Action panelInterval = new Action() {
        @Override public String title() { return "Actualizar panel cada " + PanelConfig.load(LauncherActivity.this).intervalMinutes + " min"; }
        @Override public String subtitle() { return "Mantener para cambiar"; }
        @Override public void run() {
            PanelConfig.nextInterval(LauncherActivity.this);
            PanelService.schedule(LauncherActivity.this, PanelConfig.load(LauncherActivity.this));
        }
    };
    private final Action panelPreview = new Action() {
        @Override public String title() { return "Ver panel"; }
        @Override public String subtitle() { return "Con datos actualizados; pulsa para volver"; }
        @Override public void run() { startActivity(new Intent(LauncherActivity.this, PanelPreviewActivity.class)); }
    };
    private final Action rescan = new Action() {
        @Override public String title() { return "Actualizar biblioteca"; }
        @Override public String subtitle() { return "Buscar libros nuevos en la memoria y la tarjeta"; }
        @Override public void run() { scan(); }
    };

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0 && scale > 0) view.setBattery(level * 100 / scale, charging);
        }
    };

    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            view.tick();
        }
    };

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scan();
        }
    };

    private abstract static class Action implements LibraryView.Row, Runnable {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        library = new Library(this);
        view = new LibraryView(this);
        view.setFocusable(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return true;
                int index = view.rowAt(e.getY());
                if (index >= 0) {
                    view.select(index);
                    activate();
                }
                return true;
            }
        });
        setContentView(view);
        view.setMessage("Buscando libros…");

        IntentFilter mediaFilter = new IntentFilter();
        mediaFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        mediaFilter.addDataScheme("file");
        registerReceiver(media, mediaFilter);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(media);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        registerReceiver(clock, new IntentFilter(Intent.ACTION_TIME_TICK));
        scan();
        handler.post(redrawAfterBootAnimation);
    }

    /**
     * At boot this window first draws while the boot animation still covers it. The e-ink panel
     * only refreshes regions an app redraws, so once the animation stops its last frame would stay
     * on screen. Poll for the animation to stop, then redraw everything.
     */
    private final Runnable redrawAfterBootAnimation = new Runnable() {
        private static final long BOOT_WINDOW_MS = 3 * 60 * 1000;

        @Override
        public void run() {
            if (SystemClock.uptimeMillis() > BOOT_WINDOW_MS) return;
            if ("stopped".equals(systemProperty("init.svc.bootanim"))) {
                view.invalidate();
            } else {
                handler.postDelayed(this, 500);
            }
        }
    };

    private static String systemProperty(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(redrawAfterBootAnimation);
        unregisterReceiver(battery);
        unregisterReceiver(clock);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already home: jump back to the most recent book.
        selectedPath = null;
        view.select(0);
    }

    private void scan() {
        if (scanning) {
            rescanPending = true;
            return;
        }
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Book> found = library.scan();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        books = found;
                        rebuildRows();
                        if (rescanPending) {
                            rescanPending = false;
                            scan();
                        }
                    }
                });
            }
        }, "library-scan").start();
    }

    private void rebuildRows() {
        rows.clear();
        rows.addAll(books);
        rows.add(light);
        rows.add(panel);
        rows.add(panelInterval);
        rows.add(panelPreview);
        rows.add(rescan);
        int selected = 0;
        if (selectedPath != null) {
            for (int i = 0; i < books.size(); i++) {
                if (books.get(i).file.getAbsolutePath().equals(selectedPath)) selected = i;
            }
        }
        view.setMessage(books.isEmpty() ? "No hay libros. Cópialos por USB." : null);
        view.setRows(new ArrayList<LibraryView.Row>(rows), selected);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                event.startTracking();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (!event.isCanceled()) activate();
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void move(int delta) {
        view.select(view.selected() + delta);
        int index = view.selected();
        selectedPath = index < books.size() ? books.get(index).file.getAbsolutePath() : null;
    }

    private void activate() {
        int index = view.selected();
        if (index < 0 || index >= rows.size()) return;
        LibraryView.Row row = rows.get(index);
        if (row instanceof Book) {
            open((Book) row);
        } else if (row instanceof Action) {
            ((Action) row).run();
            view.invalidate();
        }
    }

    private void open(Book book) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.fromFile(book.file));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(ONYX_FORMATS.contains(book.extension()) ? ONYX_READER : AL_READER);
        if (!start(intent)) {
            intent.setPackage(null);
            if (!start(intent)) {
                view.setMessage("No se puede abrir " + book.file.getName());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        view.setMessage(null);
                    }
                }, 3000);
                return;
            }
        }
        library.markOpened(book);
        selectedPath = book.file.getAbsolutePath();
    }

    private boolean start(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private static String levelName(int level) {
        if (level < 0) return "no disponible";
        if (level == 0) return "apagada";
        return level + " de " + (Frontlight.levelCount() - 1);
    }
}
