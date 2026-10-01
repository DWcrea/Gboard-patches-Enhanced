package dev.jason.gboardpatches.extension.textexpansion;

import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-process diagnostic ring buffer for Text Expansion.
 *
 * This intentionally avoids Android logcat so device/vendor log restrictions cannot hide
 * the data needed to debug shortcut capture and expansion. No expansion text is recorded.
 */
public final class GboardTextExpansionDiagnostics {
    private static final Object LOCK = new Object();
    private static final int MAX_LINES = 600;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    // Development diagnostic builds start enabled so a fresh reinstall is immediately useful.
    private static volatile boolean enabled = true;
    private static final long START_ELAPSED_MS = SystemClock.elapsedRealtime();

    private GboardTextExpansionDiagnostics() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (value) {
            record("DIAG enabled");
        }
    }

    public static void record(String message) {
        if (!enabled) {
            return;
        }
        try {
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - START_ELAPSED_MS);
            long minutes = elapsed / 60_000L;
            long seconds = (elapsed / 1_000L) % 60L;
            long millis = elapsed % 1_000L;
            String line = String.format(
                    Locale.US,
                    "+%02d:%02d.%03d %s",
                    minutes,
                    seconds,
                    millis,
                    message == null ? "<null>" : message);
            synchronized (LOCK) {
                while (LINES.size() >= MAX_LINES) {
                    LINES.removeFirst();
                }
                LINES.addLast(line);
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect keyboard behavior.
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return LINES.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
        if (enabled) {
            record("DIAG cleared");
        }
    }

    public static String snapshot() {
        List<String> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(LINES);
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Gboard Text Expansion diagnostics\n")
                .append("enabled=").append(enabled)
                .append(", lines=").append(copy.size())
                .append('\n');
        for (String line : copy) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
