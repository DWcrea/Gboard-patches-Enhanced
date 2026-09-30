package dev.jason.gboardpatches.extension.textexpansion;

import android.view.inputmethod.InputConnection;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class GboardTextExpansionRuntimeTest {
    @Test
    public void rawShortcutMatchUsesActualTypedLettersInsteadOfCandidateText() {
        GboardTextExpansionSettings.Entry entry =
                new GboardTextExpansionSettings.Entry("sjh", "13800138000");
        List<GboardTextExpansionSettings.Entry> entries = List.of(entry);

        Assert.assertSame(entry,
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("sjh", entries));
        Assert.assertSame(entry,
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("SJH", entries));
        Assert.assertNull(
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("手机号", entries));
        Assert.assertNull(
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("asjh", entries));
    }

    @Test
    public void rawShortcutMatchSupportsMultipleSavedRulesIndependently() {
        GboardTextExpansionSettings.Entry phone =
                new GboardTextExpansionSettings.Entry("sjh", "13800138000");
        GboardTextExpansionSettings.Entry email =
                new GboardTextExpansionSettings.Entry("yx", "name@example.com");
        List<GboardTextExpansionSettings.Entry> entries = List.of(phone, email);

        Assert.assertSame(phone,
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("sjh", entries));
        Assert.assertSame(email,
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("yx", entries));
        Assert.assertNull(
                GboardTextExpansionRuntime.findMatchingEntryForRawToken("yxz", entries));
    }

    @Test
    public void keycodeSpaceTriggersExpansionEvenWhenCandidatePayloadIsNotSpace() {
        Assert.assertEquals(" ",
                GboardTextExpansionRuntime.triggerText(62, "散户"));
        Assert.assertEquals(" ",
                GboardTextExpansionRuntime.triggerText(62, null));
        Assert.assertEquals(" ",
                GboardTextExpansionRuntime.triggerText(' ', null));
        Assert.assertNull(
                GboardTextExpansionRuntime.triggerText('h', "户"));
    }

    @Test
    public void replacingRawShortcutReplacesChineseComposingTextBeforeFirstCandidateCommits() {
        AtomicReference<String> text = new AtomicReference<>("前文");
        AtomicReference<Boolean> composing = new AtomicReference<>(Boolean.TRUE);
        AtomicReference<String> composingText = new AtomicReference<>("手机号");

        InputConnection connection = (InputConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {InputConnection.class},
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "beginBatchEdit", "endBatchEdit", "finishComposingText" ->
                                Boolean.TRUE;
                        case "getTextBeforeCursor" -> text.get();
                        case "setComposingText" -> {
                            composingText.set(String.valueOf(args[0]));
                            yield Boolean.TRUE;
                        }
                        case "deleteSurroundingText" -> {
                            int beforeLength = ((Integer) args[0]).intValue();
                            String current = text.get();
                            text.set(current.substring(
                                    0, Math.max(0, current.length() - beforeLength)));
                            yield Boolean.TRUE;
                        }
                        case "commitText" -> {
                            text.set(text.get() + String.valueOf(args[0]));
                            yield Boolean.TRUE;
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });

        GboardTextExpansionSettings.Entry entry =
                new GboardTextExpansionSettings.Entry("sjh", "13800138000");
        Assert.assertTrue(GboardTextExpansionRuntime.replaceFromRawToken(
                connection, entry, " "));
        Assert.assertEquals("前文", text.get());
        Assert.assertEquals("13800138000 ", composingText.get());
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }
}
