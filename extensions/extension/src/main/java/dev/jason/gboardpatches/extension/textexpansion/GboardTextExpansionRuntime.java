package dev.jason.gboardpatches.extension.textexpansion;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.List;
import java.util.Locale;

/**
 * Expands user-defined shortcuts immediately before a delimiter key is handled by stock Gboard.
 *
 * The runtime intentionally requires the shortcut to be visible through InputConnection before
 * replacing it. This makes the operation fail closed in editors whose composing text is not
 * exposed, instead of risking deletion of unrelated user text.
 */
public final class GboardTextExpansionRuntime {
    private GboardTextExpansionRuntime() {
    }

    public static boolean maybeHandleInputEvent(
            InputMethodService service,
            String actionTypeName,
            int selectedCode,
            String eventText) {
        if (service == null || !"PRESS".equals(actionTypeName)) {
            return false;
        }

        String trigger = triggerText(selectedCode, eventText);
        if (trigger == null) {
            return false;
        }

        GboardTextExpansionRuntimeSettings.Snapshot settings =
                GboardTextExpansionRuntimeSettings.snapshot();
        if (!settings.enabled || settings.entries.isEmpty() || isSensitiveEditor(service)) {
            return false;
        }

        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) {
            return false;
        }

        GboardTextExpansionSettings.Entry entry = findMatchingEntry(
                connection, settings.entries);
        if (entry == null) {
            return false;
        }

        return replace(connection, entry, settings.keepTrigger ? trigger : "");
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

    private static boolean replace(
            InputConnection connection,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        boolean batch = false;
        try {
            batch = connection.beginBatchEdit();
            // Finish the active IME composing span before replacement. The shortcut was already
            // verified through getTextBeforeCursor(), so this does not guess at hidden text.
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
                    // Best-effort cleanup.
                }
            }
        }
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
}
