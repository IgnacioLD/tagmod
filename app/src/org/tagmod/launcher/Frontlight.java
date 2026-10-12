package org.tagmod.launcher;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Frontlight. The kernel exposes "white" and "warm" backlight channels (both world-writable), but
 * on the Tagus they drive the same LEDs, so they are always set together as one light.
 */
final class Frontlight {
    private static final String[] CHANNELS = {"/sys/class/backlight/white", "/sys/class/backlight/warm"};
    // The LEDs stay dark below ~150, so the usable range is 150-255 (measured on the device).
    private static final int[] LEVELS = {0, 150, 162, 176, 194, 220, 255};

    private Frontlight() {
    }

    static int levelCount() {
        return LEVELS.length;
    }

    /** Current level index (from the brightest channel), or -1 if no channel can be read. */
    static int level() {
        int value = -1;
        for (String dir : CHANNELS) {
            value = Math.max(value, read(dir + "/brightness"));
        }
        if (value < 0) return -1;
        int best = 0;
        for (int i = 1; i < LEVELS.length; i++) {
            if (Math.abs(LEVELS[i] - value) < Math.abs(LEVELS[best] - value)) best = i;
        }
        return best;
    }

    /** Moves to the next level, wrapping to off; returns the new level or -1 on failure. */
    static int cycle() {
        int current = level();
        if (current < 0) return -1;
        int next = (current + 1) % LEVELS.length;
        boolean ok = false;
        for (String dir : CHANNELS) {
            int max = read(dir + "/max_brightness");
            ok |= write(dir + "/brightness", max > 0 ? LEVELS[next] * max / 255 : LEVELS[next]);
        }
        return ok ? next : -1;
    }

    private static int read(String path) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(path));
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean write(String path, int value) {
        FileWriter w = null;
        try {
            w = new FileWriter(path);
            w.write(Integer.toString(value));
            return true;
        } catch (Exception e) {
            return false;
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
