package dev.jason.gboardpatches.extension.textexpansion;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;

public final class GboardTextExpansionRuntimeSettings {
    private static final long CACHE_WINDOW_MS = 1_000L;
    private static final String TAG = "GboardPatches";
    private static final String LOG_PREFIX = "[gboard-text-expansion] ";
    private static final int LOG_LIMIT = 6;
    private static final Object LOCK = new Object();
    private static final AtomicInteger FAILURE_LOG_COUNT = new AtomicInteger();

    private static volatile Snapshot cachedSnapshot;
    private static volatile Context applicationContext;

    private GboardTextExpansionRuntimeSettings() {
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cachedSnapshot = null;
        }
    }

    public static Snapshot snapshot() {
        long now = SystemClock.elapsedRealtime();
        Snapshot cached = cachedSnapshot;
        if (cached != null && now - cached.loadedAtElapsedMs <= CACHE_WINDOW_MS) {
            return cached;
        }
        synchronized (LOCK) {
            cached = cachedSnapshot;
            if (cached != null && now - cached.loadedAtElapsedMs <= CACHE_WINDOW_MS) {
                return cached;
            }
            Context context = resolveContext();
            Snapshot loaded = load(context, now);
            cachedSnapshot = loaded;
            return loaded;
        }
    }

    private static Snapshot load(Context context, long now) {
        if (context == null) {
            return new Snapshot(now, false, true, List.of());
        }
        try {
            SharedPreferences preferences = GboardPatchesSettings.preferences(context);
            GboardTextExpansionSettings.ensureDefaults(preferences);
            List<GboardTextExpansionSettings.Entry> entries =
                    new ArrayList<>(GboardTextExpansionSettings.readEntries(preferences));
            entries.sort(Comparator
                    .comparingInt((GboardTextExpansionSettings.Entry e) -> e.shortcut.length())
                    .reversed());
            return new Snapshot(
                    now,
                    GboardTextExpansionSettings.readEnabled(preferences),
                    GboardTextExpansionSettings.readKeepTrigger(preferences),
                    entries);
        } catch (Throwable failure) {
            logFailure("failed to load settings", failure);
            return new Snapshot(now, false, true, List.of());
        }
    }

    private static Context resolveContext() {
        Context cached = applicationContext;
        if (cached != null) {
            return cached;
        }
        Context resolved = reflectedApplicationContext(
                "android.app.ActivityThread", "currentApplication");
        if (resolved == null) {
            resolved = reflectedApplicationContext(
                    "android.app.AppGlobals", "getInitialApplication");
        }
        applicationContext = resolved;
        return resolved;
    }

    private static Context reflectedApplicationContext(String className, String methodName) {
        try {
            Class<?> owner = Class.forName(className);
            Method method = owner.getMethod(methodName);
            Object value = method.invoke(null);
            if (!(value instanceof Application application)) {
                return null;
            }
            Context app = application.getApplicationContext();
            return app != null ? app : application;
        } catch (Throwable failure) {
            logFailure("failed to resolve application context via " + className, failure);
            return null;
        }
    }

    private static void logFailure(String message, Throwable failure) {
        try {
            if (FAILURE_LOG_COUNT.incrementAndGet() <= LOG_LIMIT) {
                Log.w(TAG, LOG_PREFIX + message, failure);
            }
        } catch (Throwable ignored) {
            // Settings lookup must never affect typing.
        }
    }

    public static final class Snapshot {
        public final long loadedAtElapsedMs;
        public final boolean enabled;
        public final boolean keepTrigger;
        public final List<GboardTextExpansionSettings.Entry> entries;

        Snapshot(long loadedAtElapsedMs, boolean enabled, boolean keepTrigger,
                List<GboardTextExpansionSettings.Entry> entries) {
            this.loadedAtElapsedMs = loadedAtElapsedMs;
            this.enabled = enabled;
            this.keepTrigger = keepTrigger;
            this.entries = List.copyOf(entries);
        }
    }
}
