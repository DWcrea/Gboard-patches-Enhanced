package dev.jason.gboardpatches.extension.longpressquickactions;

import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputConnection;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

public final class GboardLongPressQuickActions1803RuntimeTest {
    private static final String RUNTIME_CLASS =
            "dev.jason.gboardpatches.extension.longpressquickactions."
                    + "GboardLongPressQuickActions1803Runtime";

    @Test
    public void attemptsExactContextMenuActionAndConsumesEvenWhenTargetReturnsFalse()
            throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        AtomicInteger receivedAction = new AtomicInteger(0);
        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("getSelectedText".equals(method.getName())) {
                return "selected";
            }
            if ("performContextMenuAction".equals(method.getName())) {
                calls.incrementAndGet();
                receivedAction.set(((Integer) args[0]).intValue());
                return Boolean.FALSE;
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertTrue(attemptContextMenuAction(connection, 0x1020021));
        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(0x1020021, receivedAction.get());
    }

    @Test
    public void refusesMissingConnectionAndInvalidActionId() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("performContextMenuAction".equals(method.getName())) {
                calls.incrementAndGet();
                return Boolean.TRUE;
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertFalse(attemptContextMenuAction(null, 0x102001f));
        Assert.assertFalse(attemptContextMenuAction(connection, 0));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void copyAndCutRejectEmptySelectionBeforeDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("getSelectedText".equals(method.getName())) {
                return "";
            }
            if ("performContextMenuAction".equals(method.getName())) {
                calls.incrementAndGet();
                return Boolean.TRUE;
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertFalse(attemptContextMenuAction(connection, android.R.id.copy));
        Assert.assertFalse(attemptContextMenuAction(connection, android.R.id.cut));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void recognizedActionConsumesEditorFailure() {
        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("performContextMenuAction".equals(method.getName())) {
                throw new IllegalStateException("editor failed");
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .consumeRecognizedContextMenuAction(connection, android.R.id.paste));
    }

    @Test
    public void recognizedActionConsumesConnectionLookupFailure() {
        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .consumeRecognizedContextMenuAction(() -> {
                    throw new IllegalStateException("connection lookup failed");
                }, android.R.id.paste));
    }

    @Test
    public void disabledBindRestoresOnlyKnownPatchedMetadata() {
        Object original = new Object();
        Object patched = new Object();
        Object unrelated = new Object();

        GboardLongPressQuickActions1803Runtime.rememberPatchedMetadata(original, patched);

        Assert.assertSame(original,
                GboardLongPressQuickActions1803Runtime.metadataForBind(false, patched));
        Assert.assertSame(original,
                GboardLongPressQuickActions1803Runtime.metadataForBind(false, original));
        Assert.assertSame(unrelated,
                GboardLongPressQuickActions1803Runtime.metadataForBind(false, unrelated));
        Assert.assertSame(patched,
                GboardLongPressQuickActions1803Runtime.metadataForBind(true, patched));
    }

    @Test
    public void disabledModeConsumesOnlyMatchingEventsFromInjectedMetadata() {
        Object original = new Object();
        Object patched = new Object();
        GboardLongPressQuickActions1803Runtime.rememberPatchedMetadata(original, patched);

        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .shouldConsumeDisabledInjectedEvent(false, patched, true));
        Assert.assertFalse(GboardLongPressQuickActions1803Runtime
                .shouldConsumeDisabledInjectedEvent(false, patched, false));
        Assert.assertFalse(GboardLongPressQuickActions1803Runtime
                .shouldConsumeDisabledInjectedEvent(false, original, true));
        Assert.assertFalse(GboardLongPressQuickActions1803Runtime
                .shouldConsumeDisabledInjectedEvent(true, patched, true));
    }

    @Test
    public void metadataCacheFailureReturnsIncomingMetadata() {
        Object metadata = new Object() {
            @Override
            public int hashCode() {
                throw new IllegalStateException("metadata hash failed");
            }
        };
        GboardLongPressQuickActionsRuntimeSettings.clearEnabledOverrideForTest();
        try {
            GboardLongPressQuickActionsRuntimeSettings.setEnabledOverrideForTest(true);

            Assert.assertSame(
                    metadata,
                    GboardLongPressQuickActions1803Runtime.maybePatchMetadata(metadata, null));
        } finally {
            GboardLongPressQuickActionsRuntimeSettings.clearEnabledOverrideForTest();
        }
    }

    @Test
    public void swipeDeleteRemovesOnlyTextBeforeCursor() {
        AtomicInteger selectionStart = new AtomicInteger(-1);
        AtomicInteger selectionEnd = new AtomicInteger(-1);
        AtomicInteger emptyCommits = new AtomicInteger();
        ExtractedText extractedText = new ExtractedText();
        extractedText.text = "abcDEF";
        extractedText.startOffset = 0;
        extractedText.selectionStart = 3;
        extractedText.selectionEnd = 3;

        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("beginBatchEdit".equals(method.getName())
                    || "endBatchEdit".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("getExtractedText".equals(method.getName())) {
                return extractedText;
            }
            if ("setSelection".equals(method.getName())) {
                selectionStart.set(((Integer) args[0]).intValue());
                selectionEnd.set(((Integer) args[1]).intValue());
                return Boolean.TRUE;
            }
            if ("commitText".equals(method.getName())) {
                if (args[0] != null && args[0].toString().isEmpty()) {
                    emptyCommits.incrementAndGet();
                }
                return Boolean.TRUE;
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertTrue(GboardLongPressQuickActions1803Runtime.deleteBeforeCursor(connection));
        Assert.assertEquals(0, selectionStart.get());
        Assert.assertEquals(3, selectionEnd.get());
        Assert.assertEquals(1, emptyCommits.get());
    }

    @Test
    public void swipeDeleteAtStartIsNoOpSuccess() {
        AtomicInteger mutations = new AtomicInteger();
        ExtractedText extractedText = new ExtractedText();
        extractedText.text = "suffix";
        extractedText.startOffset = 0;
        extractedText.selectionStart = 0;
        extractedText.selectionEnd = 0;

        InputConnection connection = proxyConnection((proxy, method, args) -> {
            if ("beginBatchEdit".equals(method.getName())
                    || "endBatchEdit".equals(method.getName())) {
                return Boolean.TRUE;
            }
            if ("getExtractedText".equals(method.getName())) {
                return extractedText;
            }
            if ("setSelection".equals(method.getName())
                    || "commitText".equals(method.getName())
                    || "deleteSurroundingText".equals(method.getName())) {
                mutations.incrementAndGet();
                return Boolean.TRUE;
            }
            return defaultValue(method.getReturnType());
        });

        Assert.assertTrue(GboardLongPressQuickActions1803Runtime.deleteBeforeCursor(connection));
        Assert.assertEquals(0, mutations.get());
    }

    @Test
    public void slideDownDigitAndHalfWidthPunctuationHelpersMatchRequestedCases() {
        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .isSlideDownDigitEvent("SLIDE_DOWN", 0, "7"));
        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .isSlideDownDigitEvent("SLIDE_DOWN", (int) '3', null));
        Assert.assertFalse(GboardLongPressQuickActions1803Runtime
                .isSlideDownDigitEvent("PRESS", 0, "7"));
        Assert.assertEquals(":", GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor("："));
        Assert.assertEquals(":", GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor(":"));
        Assert.assertEquals(".", GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor("。"));
        Assert.assertEquals(".", GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor("．"));
        Assert.assertEquals(".", GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor("."));
        Assert.assertNull(GboardLongPressQuickActions1803Runtime
                .halfWidthPunctuationFor("，"));
    }

    @Test
    public void recognizedSwipeDeleteConsumesMissingConnection() {
        Assert.assertTrue(GboardLongPressQuickActions1803Runtime
                .consumeRecognizedClearAll(() -> null));
    }

    private static boolean attemptContextMenuAction(InputConnection connection, int actionId)
            throws Exception {
        try {
            Class<?> runtime = Class.forName(RUNTIME_CLASS);
            Method method = runtime.getDeclaredMethod(
                    "attemptContextMenuAction", InputConnection.class, int.class);
            method.setAccessible(true);
            return ((Boolean) method.invoke(
                    null, connection, Integer.valueOf(actionId))).booleanValue();
        } catch (ClassNotFoundException missing) {
            Assert.fail("missing GboardLongPressQuickActions1803Runtime");
            throw missing;
        }
    }

    private static InputConnection proxyConnection(InvocationHandler handler) {
        return (InputConnection) Proxy.newProxyInstance(
                GboardLongPressQuickActions1803RuntimeTest.class.getClassLoader(),
                new Class<?>[] {InputConnection.class},
                handler);
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
