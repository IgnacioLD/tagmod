package org.tagmod.launcher;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

/** Commands for powerkeyd, the root daemon from the boot image, over the abstract socket "@tagmod". */
final class Daemon {
    private Daemon() {
    }

    /** Keep the system out of suspend for up to the given time (a kernel wakelock, not filtered by Onyx). */
    static void stayAwake(int seconds) {
        send("awake " + seconds);
    }

    static void release() {
        send("release");
    }

    /**
     * Have powerkeyd wake the system at the given time and start a panel update. Android alarms
     * cannot be used: Onyx's framework drops app alarms while the device sleeps.
     */
    static void schedule(long timeMillis) {
        send("schedule " + timeMillis / 1000);
    }

    /** If the screen is off, wake and sleep so the panel shows the newly written standby image. */
    static void refreshPanel() {
        send("refresh");
    }

    private static void send(String command) {
        LocalSocket s = new LocalSocket(LocalSocket.SOCKET_DGRAM);
        try {
            s.connect(new LocalSocketAddress("tagmod", LocalSocketAddress.Namespace.ABSTRACT));
            s.getOutputStream().write(command.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w("tagmod", "powerkeyd '" + command + "': " + e);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
