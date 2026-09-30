package dev.jason.gboardpatches.extension.textexpansion;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 快捷文本运行时。
 *
 * 除了读取 InputConnection 中可见的文本，还会独立记录实际按下的字母/数字。
 * 这样即使中文输入法已经把 sjh 转换成候选“手机号”，按空格时仍能按照原始按键
 * sjh 命中快捷文本，而不会被第一候选词影响。
 */
public final class GboardTextExpansionRuntime {
    private static final Map<InputMethodService, RawShortcutSession> RAW_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GboardTextExpansionRuntime() {
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
            clearRawSession(service);
            return false;
        }

        String trigger = triggerText(selectedCode, eventText);
        if (trigger != null && "PRESS".equals(actionTypeName)) {
            InputConnection connection = service.getCurrentInputConnection();
            if (connection == null) {
                clearRawSession(service);
                return false;
            }

            // 优先使用我们自己记录的“原始按键串”。这是修复中文候选干扰的关键：
            // sjh 即使当前第一候选已经显示为“手机号”，仍然按 sjh 匹配。
            String rawToken = rawToken(service);
            GboardTextExpansionSettings.Entry rawEntry =
                    findMatchingEntryForRawToken(rawToken, settings.entries);
            boolean handled = rawEntry != null
                    && replaceFromRawToken(
                            connection,
                            rawEntry,
                            settings.keepTrigger ? trigger : "");

            // 英文键盘或某些编辑器里，快捷码可能已经直接出现在光标前。
            // 保留原来的可见文本匹配作为兼容 fallback。
            if (!handled) {
                GboardTextExpansionSettings.Entry visibleEntry =
                        findMatchingEntry(connection, settings.entries);
                handled = visibleEntry != null
                        && replaceVisibleShortcut(
                                connection,
                                visibleEntry,
                                settings.keepTrigger ? trigger : "");
            }

            clearRawSession(service);
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

    /**
     * 中文输入场景：当前编辑器里可能显示的是候选“手机号”，而用户实际按键是 sjh。
     * 先清空 composing span，再提交快捷文本，从而绕开第一候选词。
     */
    static boolean replaceFromRawToken(
            InputConnection connection,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        if (connection == null || entry == null) {
            return false;
        }
        boolean batch = false;
        try {
            batch = connection.beginBatchEdit();

            CharSequence beforeClear = connection.getTextBeforeCursor(
                    Math.max(256, entry.shortcut.length() + 16), 0);
            boolean composingCallSucceeded = connection.setComposingText("", 1);
            CharSequence afterClear = connection.getTextBeforeCursor(
                    Math.max(256, entry.shortcut.length() + 16), 0);

            boolean composingChanged = beforeClear != null
                    && afterClear != null
                    && !beforeClear.toString().equals(afterClear.toString());

            boolean removedLiteralShortcut = false;
            if (afterClear != null
                    && endsWithIgnoreCase(afterClear.toString(), entry.shortcut)) {
                removedLiteralShortcut =
                        connection.deleteSurroundingText(entry.shortcut.length(), 0);
                if (!removedLiteralShortcut) {
                    return false;
                }
            }

            // 如果既没有清除 composing，也没有删除可见的原始快捷码，就安全放弃，
            // 避免误删光标前的普通文本。
            if (!removedLiteralShortcut && !composingChanged) {
                // 某些编辑器会正确接受 setComposingText("")，但 getTextBeforeCursor()
                // 在调用前后都不暴露 composing span。此时只有调用失败才明确不可继续。
                if (!composingCallSucceeded) {
                    return false;
                }
                CharSequence visible = connection.getTextBeforeCursor(
                        entry.shortcut.length(), 0);
                if (visible != null && visible.length() > 0) {
                    return false;
                }
            }

            connection.finishComposingText();
            return connection.commitText(entry.text + trigger, 1);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (batch) {
                try {
                    connection.endBatchEdit();
                } catch (Throwable ignored) {
                    // 尽力结束批量编辑，不影响键盘主流程。
                }
            }
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
                return false;
            }
            if (!connection.deleteSurroundingText(entry.shortcut.length(), 0)) {
                return false;
            }
            return connection.commitText(entry.text + trigger, 1);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (batch) {
                try {
                    connection.endBatchEdit();
                } catch (Throwable ignored) {
                    // 尽力结束批量编辑，不影响键盘主流程。
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
        if (raw == null) {
            // 普通 PRESS 但不是可组成快捷码的字符（例如删除、候选提交等），
            // 视为当前 raw token 结束，避免跨词误触发。
            if ("PRESS".equals(actionTypeName)) {
                clearRawSession(service);
            }
            return;
        }

        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null) {
                session = new RawShortcutSession();
                RAW_SESSIONS.put(service, session);
            }
            session.token.append(raw);
            if (session.token.length() > GboardTextExpansionSettings.MAX_SHORTCUT_LENGTH) {
                session.token.delete(
                        0,
                        session.token.length() - GboardTextExpansionSettings.MAX_SHORTCUT_LENGTH);
            }
        }
    }

    private static String rawToken(InputMethodService service) {
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            return session == null ? "" : session.token.toString();
        }
    }

    private static void clearRawSession(InputMethodService service) {
        synchronized (RAW_SESSIONS) {
            RAW_SESSIONS.remove(service);
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
    }
}
