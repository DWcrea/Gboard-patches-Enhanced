package dev.jason.gboardpatches.extension.textexpansion;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.jason.gboardpatches.extension.longpressquickactions.GboardLongPressQuickActions1803ReflectionHandles;
import dev.jason.gboardpatches.extension.longpressquickactions.GboardLongPressQuickActions1803Runtime;

/**
 * 快捷文本运行时。
 *
 * dev.16 在保留现有触发逻辑和 logcat 诊断的同时，把同一份诊断信息写入应用内环形缓冲区，
 * 这样在限制系统日志读取的设备上也能直接从 Gboard 设置里查看和复制。
 */
public final class GboardTextExpansionRuntime {
    private static final String TAG = "GboardPatches";
    private static final String DIAG_PREFIX = "[text-expansion-diag] ";
    private static final long RAW_TOKEN_TIMEOUT_MS = 15_000L;
    private static final long DUPLICATE_KEY_WINDOW_MS = 35L;

    private static final Map<InputMethodService, RawShortcutSession> RAW_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> CAPTURED_POINTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GboardTextExpansionRuntime() {
    }

    public static void observeSoftKeyPress(Object pointerTracker, View softKeyView) {
        if (softKeyView == null) {
            return;
        }
        if (pointerTracker != null) {
            synchronized (CAPTURED_POINTERS) {
                if (Boolean.TRUE.equals(CAPTURED_POINTERS.get(pointerTracker))) {
                    diag("SOFTKEY skip=duplicate-pointer view=" + viewName(softKeyView));
                    return;
                }
                CAPTURED_POINTERS.put(pointerTracker, Boolean.TRUE);
            }
        }
        try {
            InputMethodService service = findInputMethodService(softKeyView.getContext());
            if (service == null) {
                diag("SOFTKEY skip=no-service view=" + viewName(softKeyView));
                return;
            }
            GboardTextExpansionRuntimeSettings.Snapshot settings =
                    GboardTextExpansionRuntimeSettings.snapshot();
            if (!settings.enabled || settings.entries.isEmpty() || isSensitiveEditor(service)) {
                diag("SOFTKEY skip=disabled-or-sensitive entries=" + settings.entries.size());
                clearRawSession(service, "disabled-or-sensitive");
                return;
            }

            ClassLoader classLoader = softKeyView.getClass().getClassLoader();
            if (classLoader == null && pointerTracker != null) {
                classLoader = pointerTracker.getClass().getClassLoader();
            }
            if (classLoader == null) {
                diag("SOFTKEY skip=no-classloader view=" + viewName(softKeyView));
                return;
            }

            GboardLongPressQuickActions1803ReflectionHandles handles =
                    GboardLongPressQuickActions1803Runtime.reflectionHandles(classLoader);
            Object metadata = handles.extractSoftKeyMetadata(softKeyView);
            if (metadata == null) {
                diag("SOFTKEY skip=no-metadata view=" + viewName(softKeyView));
                return;
            }

            String pressText = handles.extractPressText(metadata);
            int pressCode = handles.extractPressCarrierCode(metadata);
            String raw = rawInputText("PRESS", pressCode, pressText);
            String source = "metadata";

            if (raw == null) {
                raw = visibleAsciiKeyLabel(softKeyView);
                source = "label";
            }

            diag("SOFTKEY view=" + viewName(softKeyView)
                    + " code=" + pressCode
                    + " pressText=" + describeText(pressText)
                    + " raw=" + describeText(raw)
                    + " source=" + source
                    + " tokenBefore=" + rawToken(service));

            if (raw != null) {
                appendRawInput(service, raw, "softkey-" + source);
                return;
            }

            if (isDeleteKeyView(softKeyView)) {
                removeLastRawCharacter(service);
            }
        } catch (Throwable throwable) {
            diag("SOFTKEY exception=" + throwable.getClass().getSimpleName());
        }
    }

    public static void onPointerFinished(Object pointerTracker) {
        if (pointerTracker == null) {
            return;
        }
        synchronized (CAPTURED_POINTERS) {
            CAPTURED_POINTERS.remove(pointerTracker);
        }
    }

    public static boolean maybeHandleInputEvent(
            InputMethodService service,
            String actionTypeName,
            int selectedCode,
            String eventText) {
        if (service == null) {
            return false;
        }

        GboardTextExpansionRuntimeSettings.Snapshot settings =
                GboardTextExpansionRuntimeSettings.snapshot();
        if (!settings.enabled || settings.entries.isEmpty() || isSensitiveEditor(service)) {
            diag("EVENT skip=disabled-or-sensitive action=" + actionTypeName);
            clearRawSession(service, "disabled-or-sensitive-event");
            return false;
        }

        String tokenBefore = rawToken(service);
        String trigger = triggerText(selectedCode, eventText);
        diag("EVENT action=" + safe(actionTypeName)
                + " code=" + selectedCode
                + " text=" + describeText(eventText)
                + " trigger=" + describeText(trigger)
                + " token=" + tokenBefore);

        if (trigger != null && "PRESS".equals(actionTypeName)) {
            InputConnection connection = service.getCurrentInputConnection();
            if (connection == null) {
                diag("TRIGGER result=no-connection token=" + tokenBefore);
                clearRawSession(service, "no-connection");
                return false;
            }

            GboardTextExpansionSettings.Entry rawEntry =
                    findMatchingEntryForRawToken(tokenBefore, settings.entries);
            diag("TRIGGER rawMatch=" + (rawEntry != null)
                    + " token=" + tokenBefore
                    + " trigger=" + describeText(trigger));

            boolean handled = rawEntry != null
                    && replaceFromRawToken(
                            connection,
                            rawEntry,
                            settings.keepTrigger ? trigger : "");
            diag("TRIGGER rawHandled=" + handled + " token=" + tokenBefore);

            if (!handled) {
                GboardTextExpansionSettings.Entry visibleEntry =
                        findMatchingEntry(connection, settings.entries);
                diag("TRIGGER visibleMatch=" + (visibleEntry != null));
                handled = visibleEntry != null
                        && replaceVisibleShortcut(
                                connection,
                                visibleEntry,
                                settings.keepTrigger ? trigger : "");
                diag("TRIGGER visibleHandled=" + handled);
            }

            clearRawSession(service, "trigger-finished");
            return handled;
        }

        updateRawSession(service, actionTypeName, selectedCode, eventText);
        return false;
    }

    static GboardTextExpansionSettings.Entry findMatchingEntryForRawToken(
            String rawToken,
            List<GboardTextExpansionSettings.Entry> entries) {
        if (rawToken == null || rawToken.isEmpty() || entries == null) {
            return null;
        }
        for (GboardTextExpansionSettings.Entry entry : entries) {
            if (entry != null && rawToken.equalsIgnoreCase(entry.shortcut)) {
                return entry;
            }
        }
        return null;
    }

    static GboardTextExpansionSettings.Entry findMatchingEntry(
            InputConnection connection,
            List<GboardTextExpansionSettings.Entry> entries) {
        if (connection == null || entries == null || entries.isEmpty()) {
            return null;
        }
        int maxLength = 0;
        for (GboardTextExpansionSettings.Entry entry : entries) {
            if (entry != null) {
                maxLength = Math.max(maxLength, entry.shortcut.length());
            }
        }
        if (maxLength <= 0) {
            return null;
        }
        CharSequence before = connection.getTextBeforeCursor(maxLength, 0);
        if (before == null) {
            return null;
        }
        String suffix = before.toString();
        for (GboardTextExpansionSettings.Entry entry : entries) {
            if (entry != null && endsWithIgnoreCase(suffix, entry.shortcut)) {
                return entry;
            }
        }
        return null;
    }

    static boolean replaceFromRawToken(
            InputConnection connection,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        if (connection == null || entry == null) {
            return false;
        }
        try {
            CharSequence visible = connection.getTextBeforeCursor(
                    Math.max(256, entry.shortcut.length() + 16), 0);
            if (visible != null && endsWithIgnoreCase(visible.toString(), entry.shortcut)) {
                boolean result = replaceVisibleShortcut(connection, entry, trigger);
                diag("REPLACE mode=visible shortcutLen=" + entry.shortcut.length()
                        + " result=" + result);
                return result;
            }

            boolean replaced = connection.setComposingText(entry.text + trigger, 1);
            diag("REPLACE mode=composing shortcutLen=" + entry.shortcut.length()
                    + " setComposing=" + replaced);
            if (!replaced) {
                return false;
            }
            try {
                connection.finishComposingText();
            } catch (Throwable ignored) {
                diag("REPLACE finishComposing=exception");
            }
            return true;
        } catch (Throwable throwable) {
            diag("REPLACE exception=" + throwable.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean replaceVisibleShortcut(
            InputConnection connection,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        boolean batch = false;
        try {
            batch = connection.beginBatchEdit();
            connection.finishComposingText();
            CharSequence before = connection.getTextBeforeCursor(entry.shortcut.length(), 0);
            if (before == null || !endsWithIgnoreCase(before.toString(), entry.shortcut)) {
                diag("VISIBLE result=suffix-miss shortcutLen=" + entry.shortcut.length());
                return false;
            }
            if (!connection.deleteSurroundingText(entry.shortcut.length(), 0)) {
                diag("VISIBLE result=delete-failed shortcutLen=" + entry.shortcut.length());
                return false;
            }
            boolean committed = connection.commitText(entry.text + trigger, 1);
            diag("VISIBLE result=commit-" + committed + " shortcutLen=" + entry.shortcut.length());
            return committed;
        } catch (Throwable throwable) {
            diag("VISIBLE exception=" + throwable.getClass().getSimpleName());
            return false;
        } finally {
            if (batch) {
                try {
                    connection.endBatchEdit();
                } catch (Throwable ignored) {
                    diag("VISIBLE endBatch=exception");
                }
            }
        }
    }

    private static void updateRawSession(
            InputMethodService service,
            String actionTypeName,
            int selectedCode,
            String eventText) {
        String raw = rawInputText(actionTypeName, selectedCode, eventText);
        if (raw != null) {
            appendRawInput(service, raw, "input-event");
        }
    }

    private static void appendRawInput(InputMethodService service, String raw, String source) {
        if (service == null || raw == null || raw.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null || now - session.lastUpdatedElapsedMs > RAW_TOKEN_TIMEOUT_MS) {
                session = new RawShortcutSession();
                RAW_SESSIONS.put(service, session);
                diag("TOKEN new-session source=" + source);
            }

            if (raw.equals(session.lastAppended)
                    && now - session.lastUpdatedElapsedMs <= DUPLICATE_KEY_WINDOW_MS) {
                diag("TOKEN duplicate-suppressed raw=" + raw
                        + " source=" + source
                        + " token=" + session.token);
                return;
            }

            session.token.append(raw);
            session.lastAppended = raw;
            session.lastUpdatedElapsedMs = now;
            if (session.token.length() > GboardTextExpansionSettings.MAX_SHORTCUT_LENGTH) {
                session.token.delete(
                        0,
                        session.token.length() - GboardTextExpansionSettings.MAX_SHORTCUT_LENGTH);
            }
            diag("TOKEN append=" + raw + " source=" + source + " token=" + session.token);
        }
    }

    private static void removeLastRawCharacter(InputMethodService service) {
        if (service == null) {
            return;
        }
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null || session.token.length() == 0) {
                diag("TOKEN delete ignored=empty");
                return;
            }
            session.token.deleteCharAt(session.token.length() - 1);
            session.lastAppended = "";
            session.lastUpdatedElapsedMs = SystemClock.elapsedRealtime();
            diag("TOKEN delete token=" + session.token);
        }
    }

    private static String rawToken(InputMethodService service) {
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null) {
                return "";
            }
            long now = SystemClock.elapsedRealtime();
            if (now - session.lastUpdatedElapsedMs > RAW_TOKEN_TIMEOUT_MS) {
                RAW_SESSIONS.remove(service);
                diag("TOKEN expired");
                return "";
            }
            return session.token.toString();
        }
    }

    private static void clearRawSession(InputMethodService service, String reason) {
        synchronized (RAW_SESSIONS) {
            RawShortcutSession previous = RAW_SESSIONS.remove(service);
            if (previous != null) {
                diag("TOKEN clear reason=" + reason + " old=" + previous.token);
            }
        }
    }

    private static String rawInputText(
            String actionTypeName,
            int selectedCode,
            String eventText) {
        if (!("PRESS".equals(actionTypeName)
                || "SLIDE_DOWN".equals(actionTypeName)
                || "SLIDE_UP".equals(actionTypeName))) {
            return null;
        }
        if (eventText != null && eventText.length() == 1) {
            char value = eventText.charAt(0);
            if (isShortcutInputChar(value)) {
                return String.valueOf(value);
            }
        }
        if (selectedCode >= 0 && selectedCode <= Character.MAX_VALUE) {
            char value = (char) selectedCode;
            if (isShortcutInputChar(value)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean isShortcutInputChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_';
    }

    private static String triggerText(int selectedCode, String eventText) {
        if (eventText != null && eventText.length() == 1
                && isDelimiter(eventText.charAt(0))) {
            return eventText;
        }
        if (selectedCode >= 0 && selectedCode <= Character.MAX_VALUE) {
            char value = (char) selectedCode;
            if (isDelimiter(value)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static boolean isDelimiter(char value) {
        return Character.isWhitespace(value)
                || value == '.' || value == ',' || value == ':' || value == ';'
                || value == '!' || value == '?' || value == '-'
                || value == '\u3002' || value == '\uFF0C' || value == '\uFF1A'
                || value == '\uFF1B' || value == '\uFF01' || value == '\uFF1F';
    }

    private static boolean endsWithIgnoreCase(String source, String suffix) {
        if (source == null || suffix == null || suffix.length() > source.length()) {
            return false;
        }
        int offset = source.length() - suffix.length();
        return source.regionMatches(true, offset, suffix, 0, suffix.length());
    }

    private static String visibleAsciiKeyLabel(View view) {
        if (view == null) {
            return null;
        }
        try {
            String description = singleShortcutChar(view.getContentDescription());
            if (description != null) {
                return description;
            }
        } catch (Throwable ignored) {
            // 继续检查键帽文本。
        }
        if (view instanceof TextView textView) {
            String direct = singleShortcutChar(textView.getText());
            if (direct != null) {
                return direct;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                String nested = visibleAsciiKeyLabel(group.getChildAt(index));
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String singleShortcutChar(CharSequence value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.length() != 1) {
            return null;
        }
        return isShortcutInputChar(text.charAt(0)) ? text : null;
    }

    private static boolean isDeleteKeyView(View view) {
        if (view == null || view.getId() == View.NO_ID) {
            return false;
        }
        try {
            return "key_pos_del".equals(view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String viewName(View view) {
        if (view == null) {
            return "null";
        }
        try {
            if (view.getId() != View.NO_ID) {
                return view.getResources().getResourceEntryName(view.getId());
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return view.getClass().getSimpleName();
    }

    private static String describeText(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isEmpty()) {
            return "<empty>";
        }
        if (value.length() == 1) {
            char ch = value.charAt(0);
            if (ch == ' ') return "<space>";
            if (ch == '\n') return "<newline>";
            if (ch == '\t') return "<tab>";
            if (ch >= 0x20 && ch <= 0x7e) return "'" + ch + "'";
            return "<U+" + String.format("%04X", (int) ch) + ">";
        }
        return "<len:" + value.length() + ">";
    }

    private static String safe(String value) {
        return value == null ? "<null>" : value;
    }

    private static void diag(String message) {
        GboardTextExpansionDiagnostics.record(message);
        try {
            Log.i(TAG, DIAG_PREFIX + message);
        } catch (Throwable ignored) {
            // 诊断日志不能影响键盘主流程。
        }
    }

    private static InputMethodService findInputMethodService(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof InputMethodService service) {
                return service;
            }
            if (!(current instanceof ContextWrapper wrapper)) {
                break;
            }
            Context next = wrapper.getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private static boolean isSensitiveEditor(InputMethodService service) {
        try {
            EditorInfo info = service.getCurrentInputEditorInfo();
            if (info == null) {
                return false;
            }
            int inputType = info.inputType;
            int inputClass = inputType & InputType.TYPE_MASK_CLASS;
            int variation = inputType & InputType.TYPE_MASK_VARIATION;
            if (inputClass == InputType.TYPE_CLASS_TEXT) {
                return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            }
            return inputClass == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static final class RawShortcutSession {
        final StringBuilder token = new StringBuilder();
        String lastAppended = "";
        long lastUpdatedElapsedMs = SystemClock.elapsedRealtime();
    }
}
