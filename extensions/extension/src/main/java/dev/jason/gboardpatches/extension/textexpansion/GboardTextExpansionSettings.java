package dev.jason.gboardpatches.extension.textexpansion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.jason.gboardpatches.extension.settings.GboardPatchesSettings;

/**
 * Persistent settings and portable import/export format for Text Expansion.
 */
public final class GboardTextExpansionSettings {
    public static final String PREF_KEY_ENABLED = "pref_text_expansion_enabled";
    public static final String PREF_KEY_KEEP_TRIGGER = "pref_text_expansion_keep_trigger";
    public static final String PREF_KEY_ENTRIES_JSON = "pref_text_expansion_entries_json";

    public static final boolean DEFAULT_ENABLED = true;
    public static final boolean DEFAULT_KEEP_TRIGGER = true;
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_SHORTCUT_LENGTH = 64;
    public static final int MAX_EXPANSION_LENGTH = 4096;

    private static final String FORMAT = "gboard-text-expansion.v1";

    private GboardTextExpansionSettings() {
    }

    public static final class Entry {
        public final String shortcut;
        public final String text;

        public Entry(String shortcut, String text) {
            this.shortcut = sanitizeShortcut(shortcut);
            this.text = sanitizeExpansion(text);
        }
    }

    public static SharedPreferences preferences(Context context) {
        return GboardPatchesSettings.preferences(context);
    }

    public static void ensureDefaults(Context context) {
        if (context != null) {
            ensureDefaults(preferences(context));
        }
    }

    public static void ensureDefaults(SharedPreferences preferences) {
        if (preferences == null) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        if (!preferences.contains(PREF_KEY_ENABLED)) {
            editor.putBoolean(PREF_KEY_ENABLED, DEFAULT_ENABLED);
            changed = true;
        }
        if (!preferences.contains(PREF_KEY_KEEP_TRIGGER)) {
            editor.putBoolean(PREF_KEY_KEEP_TRIGGER, DEFAULT_KEEP_TRIGGER);
            changed = true;
        }
        if (!preferences.contains(PREF_KEY_ENTRIES_JSON)) {
            editor.putString(PREF_KEY_ENTRIES_JSON, "[]");
            changed = true;
        }
        if (changed) {
            editor.commit();
        }
    }

    public static boolean readEnabled(SharedPreferences preferences) {
        return readBoolean(preferences, PREF_KEY_ENABLED, DEFAULT_ENABLED);
    }

    public static boolean readKeepTrigger(SharedPreferences preferences) {
        return readBoolean(preferences, PREF_KEY_KEEP_TRIGGER, DEFAULT_KEEP_TRIGGER);
    }

    public static List<Entry> readEntries(SharedPreferences preferences) {
        if (preferences == null) {
            return Collections.emptyList();
        }
        String raw = preferences.getString(PREF_KEY_ENTRIES_JSON, "[]");
        try {
            return parseEntriesArray(new JSONArray(raw == null ? "[]" : raw));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static boolean writeEnabled(Context context, boolean enabled) {
        boolean saved = context != null && preferences(context).edit()
                .putBoolean(PREF_KEY_ENABLED, enabled)
                .commit();
        if (saved) {
            GboardTextExpansionRuntimeSettings.invalidate();
        }
        return saved;
    }

    public static boolean writeKeepTrigger(Context context, boolean keepTrigger) {
        boolean saved = context != null && preferences(context).edit()
                .putBoolean(PREF_KEY_KEEP_TRIGGER, keepTrigger)
                .commit();
        if (saved) {
            GboardTextExpansionRuntimeSettings.invalidate();
        }
        return saved;
    }

    public static boolean writeEntries(Context context, List<Entry> entries) {
        if (context == null) {
            return false;
        }
        List<Entry> normalized = normalizeEntries(entries);
        boolean saved = preferences(context).edit()
                .putString(PREF_KEY_ENTRIES_JSON, entriesArray(normalized).toString())
                .commit();
        if (saved) {
            GboardTextExpansionRuntimeSettings.invalidate();
        }
        return saved;
    }

    public static String exportJson(Context context) {
        SharedPreferences preferences = preferences(context);
        ensureDefaults(preferences);
        try {
            JSONObject root = new JSONObject();
            root.put("format", FORMAT);
            root.put("enabled", readEnabled(preferences));
            root.put("keepTrigger", readKeepTrigger(preferences));
            root.put("entries", entriesArray(readEntries(preferences)));
            return root.toString(2);
        } catch (JSONException exception) {
            throw new IllegalStateException("无法导出快捷文本设置", exception);
        }
    }

    public static void importJson(Context context, String json) {
        if (context == null) {
            throw new IllegalArgumentException("缺少上下文");
        }
        try {
            JSONObject root = new JSONObject(json == null ? "" : json);
            if (!FORMAT.equals(root.optString("format", ""))) {
                throw new IllegalArgumentException("不支持的快捷文本文件格式");
            }
            JSONArray entriesJson = root.optJSONArray("entries");
            if (entriesJson == null) {
                throw new IllegalArgumentException("文件中缺少 entries");
            }
            List<Entry> entries = parseEntriesArray(entriesJson);
            SharedPreferences.Editor editor = preferences(context).edit()
                    .putString(PREF_KEY_ENTRIES_JSON, entriesArray(entries).toString());
            if (root.has("enabled")) {
                editor.putBoolean(PREF_KEY_ENABLED, root.getBoolean("enabled"));
            }
            if (root.has("keepTrigger")) {
                editor.putBoolean(PREF_KEY_KEEP_TRIGGER, root.getBoolean("keepTrigger"));
            }
            if (!editor.commit()) {
                throw new IllegalStateException("保存失败");
            }
            GboardTextExpansionRuntimeSettings.invalidate();
        } catch (JSONException exception) {
            throw new IllegalArgumentException("JSON 格式无效", exception);
        }
    }

    /**
     * Human-friendly editor format: one mapping per line: shortcut=expanded text.
     * Expansion text supports \\n, \\t, \\r and \\\\ escapes.
     */
    public static List<Entry> parseEditorText(String source) {
        List<Entry> result = new ArrayList<>();
        String[] lines = (source == null ? "" : source).split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException(
                        "第 " + (index + 1) + " 行缺少 '='");
            }
            String shortcut = line.substring(0, separator).trim();
            String text = decodeEscapes(line.substring(separator + 1));
            result.add(new Entry(shortcut, text));
        }
        return normalizeEntries(result);
    }

    public static String toEditorText(List<Entry> entries) {
        StringBuilder builder = new StringBuilder();
        for (Entry entry : normalizeEntries(entries)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.shortcut)
                    .append('=')
                    .append(encodeEscapes(entry.text));
        }
        return builder.toString();
    }

    static List<Entry> normalizeEntries(List<Entry> entries) {
        LinkedHashMap<String, Entry> byShortcut = new LinkedHashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null) {
                    continue;
                }
                Entry normalized = new Entry(entry.shortcut, entry.text);
                // Last value wins while preserving a deterministic insertion order.
                byShortcut.remove(normalized.shortcut);
                byShortcut.put(normalized.shortcut, normalized);
                if (byShortcut.size() > MAX_ENTRIES) {
                    throw new IllegalArgumentException(
                            "快捷文本最多 " + MAX_ENTRIES + " 条");
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(byShortcut.values()));
    }

    private static List<Entry> parseEntriesArray(JSONArray array) throws JSONException {
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject object = array.optJSONObject(index);
            if (object == null) {
                throw new IllegalArgumentException("entries[" + index + "] must be an object");
            }
            entries.add(new Entry(
                    object.optString("shortcut", ""),
                    object.optString("text", "")));
        }
        return normalizeEntries(entries);
    }

    private static JSONArray entriesArray(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : normalizeEntries(entries)) {
            JSONObject object = new JSONObject();
            try {
                object.put("shortcut", entry.shortcut);
                object.put("text", entry.text);
            } catch (JSONException exception) {
                throw new IllegalStateException("Unable to encode Text Expansion entry", exception);
            }
            array.put(object);
        }
        return array;
    }

    private static String sanitizeShortcut(String value) {
        String shortcut = value == null ? "" : value.trim();
        if (shortcut.isEmpty()) {
            throw new IllegalArgumentException("快捷方式不能为空");
        }
        if (shortcut.length() > MAX_SHORTCUT_LENGTH) {
            throw new IllegalArgumentException(
                    "快捷方式最长 " + MAX_SHORTCUT_LENGTH + " 个字符");
        }
        if (shortcut.indexOf('\n') >= 0 || shortcut.indexOf('\r') >= 0
                || shortcut.indexOf('=') >= 0) {
            throw new IllegalArgumentException(
                    "快捷方式不能包含换行或 '='");
        }
        return shortcut;
    }

    private static String sanitizeExpansion(String value) {
        String text = value == null ? "" : value;
        if (text.length() > MAX_EXPANSION_LENGTH) {
            throw new IllegalArgumentException(
                    "展开文本最长 " + MAX_EXPANSION_LENGTH + " 个字符");
        }
        return text;
    }

    private static boolean readBoolean(SharedPreferences preferences, String key,
            boolean defaultValue) {
        if (preferences == null) {
            return defaultValue;
        }
        Object raw = preferences.getAll().get(key);
        if (raw instanceof Boolean value) {
            return value.booleanValue();
        }
        if (raw instanceof String value) {
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
        }
        return defaultValue;
    }

    private static String encodeEscapes(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String decodeEscapes(String value) {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!escaped) {
                if (c == '\\') {
                    escaped = true;
                } else {
                    result.append(c);
                }
                continue;
            }
            escaped = false;
            switch (c) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                default -> {
                    result.append('\\');
                    result.append(c);
                }
            }
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }
}
