package dev.jason.gboardpatches.extension.textexpansion;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.jason.gboardpatches.extension.longpressquickactions.GboardLongPressQuickActions1803ReflectionHandles;
import dev.jason.gboardpatches.extension.longpressquickactions.GboardLongPressQuickActions1803Runtime;

/**
 * 快捷文本运行时。
 *
 * 除了读取 InputConnection 中可见的文本，还会独立记录实际按下的字母/数字。
 * 中文输入状态下，实际按键串和候选词提交事件可能不是同一条路径；因此物理空格键
 * 会在 pointer-owner / SoftKeyView 阶段单独记录，并在后续 PRESS 事件中完成展开。
 */
public final class GboardTextExpansionRuntime {
    private static final long RAW_TOKEN_TIMEOUT_MS = 15_000L;
    private static final long DUPLICATE_KEY_WINDOW_MS = 35L;
    private static final long PENDING_SPACE_TIMEOUT_MS = 1_500L;

    // 这里只用于 SoftKey metadata 的物理空格键识别。Gboard 18.0.3 自身的
    // SpacebarLogo 运行时也使用 62 判断空格 SoftKey。不要再把 input-event 的
    // selectedCode=62 直接解释成空格；两者不是同一个契约。
    private static final int SOFT_KEY_SPACE_CARRIER_CODE = 62;

    private static final Map<InputMethodService, RawShortcutSession> RAW_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<InputMethodService, PendingSpaceExpansion> PENDING_SPACE_EXPANSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> CAPTURED_POINTERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GboardTextExpansionRuntime() {
    }

    /**
     * 在 Gboard 的 pointer-owner 阶段记录真实按下的软键。
     *
     * 中文拼音/双拼的普通字母通常不会以可识别的字母事件到达后面的 input-event
     * dispatcher，所以只在 maybeHandleInputEvent() 里记录按键是不够的。这里直接从
     * SoftKeyView metadata 读取 PRESS payload，能在候选生成之前得到 s/j/h 这类原始键值。
     *
     * 当这里识别到真实的物理空格键时，如果 raw token 已经精确命中规则，就只“武装”
     * 一次待展开状态；真正替换仍等后面的 PRESS input event 到来。这样既不依赖候选词
     * payload，也不会在触摸 DOWN 阶段过早修改编辑器。
     */
    public static void observeSoftKeyPress(Object pointerTracker, View softKeyView) {
        if (softKeyView == null) {
            return;
        }
        if (pointerTracker != null) {
            synchronized (CAPTURED_POINTERS) {
                if (Boolean.TRUE.equals(CAPTURED_POINTERS.get(pointerTracker))) {
                    return;
                }
                CAPTURED_POINTERS.put(pointerTracker, Boolean.TRUE);
            }
        }
        try {
            InputMethodService service = findInputMethodService(softKeyView.getContext());
            if (service == null) {
                return;
            }
            GboardTextExpansionRuntimeSettings.Snapshot settings =
                    GboardTextExpansionRuntimeSettings.snapshot();
            if (!settings.enabled || settings.entries.isEmpty() || isSensitiveEditor(service)) {
                clearPendingSpaceExpansion(service);
                clearRawSession(service);
                return;
            }

            ClassLoader classLoader = softKeyView.getClass().getClassLoader();
            if (classLoader == null && pointerTracker != null) {
                classLoader = pointerTracker.getClass().getClassLoader();
            }
            if (classLoader == null) {
                return;
            }

            GboardLongPressQuickActions1803ReflectionHandles handles =
                    GboardLongPressQuickActions1803Runtime.reflectionHandles(classLoader);
            Object metadata = handles.extractSoftKeyMetadata(softKeyView);
            if (metadata == null) {
                return;
            }

            String pressText = handles.extractPressText(metadata);
            int pressCode = handles.extractPressCarrierCode(metadata);

            // 关键修复：在物理 SoftKey 层识别空格，而不是根据后面的中文候选提交事件
            // 猜 selectedCode / payload。此时 raw token 仍然保留着用户实际输入的 sjh 等。
            if (isPhysicalSpaceSoftKey(pressCode, pressText)) {
                GboardTextExpansionSettings.Entry entry =
                        findMatchingEntryForRawToken(rawToken(service), settings.entries);
                if (entry != null) {
                    armPendingSpaceExpansion(
                            service,
                            entry,
                            settings.keepTrigger ? " " : "");
                } else {
                    clearPendingSpaceExpansion(service);
                }
                // 空格天然是 token 边界。匹配内容已经复制到 PendingSpaceExpansion 中。
                clearRawSession(service);
                return;
            }

            // 在待展开空格之后如果又开始了另一个物理按键，旧 pending 不能误伤下一键。
            clearPendingSpaceExpansion(service);

            String raw = rawInputText("PRESS", pressCode, pressText);

            // 部分中文拼音/双拼布局的 PRESS metadata 不直接暴露 ASCII 字母。
            // 这时从真实键帽/无障碍标签回退读取 s、j、h 等物理键字符。
            if (raw == null) {
                raw = visibleAsciiKeyLabel(softKeyView);
            }
            if (raw != null) {
                appendRawInput(service, raw);
                return;
            }

            if (isDeleteKeyView(softKeyView)) {
                removeLastRawCharacter(service);
            }
        } catch (Throwable ignored) {
            // 原始按键观察失败时直接回退到普通 Gboard 行为，不能影响键盘主流程。
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
            clearPendingSpaceExpansion(service);
            clearRawSession(service);
            return false;
        }

        // 物理空格已经在 SoftKey 层精确命中规则时，不再关心这个 PRESS 事件的
        // selectedCode 或 candidate payload 是什么。第一候选可以是“散户”、
        // “手机号”或任何其他中文词，都只按之前记录的真实快捷码展开。
        if ("PRESS".equals(actionTypeName)) {
            PendingSpaceExpansion pending = takePendingSpaceExpansion(service);
            if (pending != null) {
                InputConnection connection = service.getCurrentInputConnection();
                if (connection != null
                        && replaceFromRawToken(connection, pending.entry, pending.trigger)) {
                    clearRawSession(service);
                    return true;
                }
                // 替换失败时放行原生 Gboard 行为，避免吞掉用户的空格。
            }
        }

        String trigger = triggerText(selectedCode, eventText);
        if (trigger != null && "PRESS".equals(actionTypeName)) {
            InputConnection connection = service.getCurrentInputConnection();
            if (connection == null) {
                clearRawSession(service);
                return false;
            }

            // 英文键盘或某些没有经过 SoftKey physical-space 路径的布局仍保留旧 fallback。
            String rawToken = rawToken(service);
            GboardTextExpansionSettings.Entry rawEntry =
                    findMatchingEntryForRawToken(rawToken, settings.entries);
            boolean handled = rawEntry != null
                    && replaceFromRawToken(
                            connection,
                            rawEntry,
                            settings.keepTrigger ? trigger : "");

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

    static boolean isPhysicalSpaceSoftKey(int pressCode, String pressText) {
        return pressCode == SOFT_KEY_SPACE_CARRIER_CODE
                || " ".equals(pressText);
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
     * 中文输入场景：当前编辑器里可能显示的是候选词，而用户实际按键是快捷码。
     * setComposingText() 会直接替换当前 composing span，因此不需要先提交第一候选。
     */
    static boolean replaceFromRawToken(
            InputConnection connection,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        if (connection == null || entry == null) {
            return false;
        }
        try {
            // 英文键盘等场景里，快捷码已经作为普通文本提交到了编辑器。
            // 这种情况继续走“删除可见快捷码 → 提交展开文本”的稳定路径。
            CharSequence visible = connection.getTextBeforeCursor(
                    Math.max(256, entry.shortcut.length() + 16), 0);
            if (visible != null && endsWithIgnoreCase(visible.toString(), entry.shortcut)) {
                return replaceVisibleShortcut(connection, entry, trigger);
            }

            boolean replaced = connection.setComposingText(entry.text + trigger, 1);
            if (!replaced) {
                return false;
            }
            try {
                connection.finishComposingText();
            } catch (Throwable ignored) {
                // setComposingText 已成功时，结束 composing 失败也不应让空格继续落入 stock。
            }
            return true;
        } catch (Throwable ignored) {
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
        if (raw != null) {
            appendRawInput(service, raw);
        }
    }

    private static void appendRawInput(InputMethodService service, String raw) {
        if (service == null || raw == null || raw.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null || now - session.lastUpdatedElapsedMs > RAW_TOKEN_TIMEOUT_MS) {
                session = new RawShortcutSession();
                RAW_SESSIONS.put(service, session);
            }

            // 同一个实体按键有时会先从 pointer-owner，再从 input-event 各观察一次。
            if (raw.equals(session.lastAppended)
                    && now - session.lastUpdatedElapsedMs <= DUPLICATE_KEY_WINDOW_MS) {
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
        }
    }

    private static void removeLastRawCharacter(InputMethodService service) {
        if (service == null) {
            return;
        }
        synchronized (RAW_SESSIONS) {
            RawShortcutSession session = RAW_SESSIONS.get(service);
            if (session == null || session.token.length() == 0) {
                return;
            }
            session.token.deleteCharAt(session.token.length() - 1);
            session.lastAppended = "";
            session.lastUpdatedElapsedMs = SystemClock.elapsedRealtime();
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
                return "";
            }
            return session.token.toString();
        }
    }

    private static void clearRawSession(InputMethodService service) {
        synchronized (RAW_SESSIONS) {
            RAW_SESSIONS.remove(service);
        }
    }

    private static void armPendingSpaceExpansion(
            InputMethodService service,
            GboardTextExpansionSettings.Entry entry,
            String trigger) {
        if (service == null || entry == null) {
            return;
        }
        synchronized (PENDING_SPACE_EXPANSIONS) {
            PENDING_SPACE_EXPANSIONS.put(
                    service,
                    new PendingSpaceExpansion(
                            entry,
                            trigger == null ? "" : trigger,
                            SystemClock.elapsedRealtime()));
        }
    }

    private static PendingSpaceExpansion takePendingSpaceExpansion(InputMethodService service) {
        if (service == null) {
            return null;
        }
        synchronized (PENDING_SPACE_EXPANSIONS) {
            PendingSpaceExpansion pending = PENDING_SPACE_EXPANSIONS.remove(service);
            if (pending == null) {
                return null;
            }
            long age = SystemClock.elapsedRealtime() - pending.armedAtElapsedMs;
            return age >= 0L && age <= PENDING_SPACE_TIMEOUT_MS ? pending : null;
        }
    }

    private static void clearPendingSpaceExpansion(InputMethodService service) {
        if (service == null) {
            return;
        }
        synchronized (PENDING_SPACE_EXPANSIONS) {
            PENDING_SPACE_EXPANSIONS.remove(service);
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
            return "key_pos_del".equals(
                    view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
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

    private static final class PendingSpaceExpansion {
        final GboardTextExpansionSettings.Entry entry;
        final String trigger;
        final long armedAtElapsedMs;

        PendingSpaceExpansion(
                GboardTextExpansionSettings.Entry entry,
                String trigger,
                long armedAtElapsedMs) {
            this.entry = entry;
            this.trigger = trigger;
            this.armedAtElapsedMs = armedAtElapsedMs;
        }
    }
}
