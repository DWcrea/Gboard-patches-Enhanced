package dev.jason.gboardpatches.extension.longpressquickactions;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import dev.jason.gboardpatches.extension.zhuyinslide.GboardZhuyinSlideRuntime;

/**
 * Companion runtime for the Enhanced Backspace gestures.
 *
 * <p>The existing 18.0.3 runtime owns the upward gesture and performs
 * "delete everything before the cursor".  This class observes that upward motion just before
 * the delete happens, remembers the exact prefix that is about to be removed, and adds the
 * opposite gesture: swiping down from Backspace restores that remembered prefix.</p>
 *
 * <p>The restore is intentionally fail-closed.  It only commits when the same InputConnection
 * is still active and the editor still matches the post-delete state.  Typing, moving the
 * cursor, switching editors, or waiting too long prevents a stale restore from being inserted
 * in the wrong place.</p>
 */
public final class GboardBackspaceSwipeUndoRuntime {
    private static final String TAG = "GboardPatches";
    private static final String LOG_PREFIX = "[backspace-swipe-undo] ";
    private static final long UNDO_WINDOW_MS = 30_000L;
    private static final long RESTORE_AFTER_RELEASE_DELAY_MS = 80L;
    private static final int FALLBACK_CAPTURE_CHARS = 1024 * 1024;

    private static final Map<Object, SwipeSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<InputMethodService, DeletionRecord> LAST_DELETION =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicInteger LOG_COUNT = new AtomicInteger();

    private GboardBackspaceSwipeUndoRuntime() {
    }

    /**
     * Runs before the existing Backspace motion interceptor.
     *
     * <p>For upward motion we only snapshot the text and return false so the existing V8
     * delete-before-cursor implementation continues to run.  For downward motion we restore the
     * remembered prefix and consume the gesture.</p>
     */
    public static boolean maybeInterceptBackspaceUndoMotion(
            Object pointerTracker, MotionEvent event, int pointerIndex) {
        if (pointerTracker == null || event == null
                || !GboardLongPressQuickActionsRuntimeSettings.isEnabled()) {
            return false;
        }
        try {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                return false;
            }

            GboardLongPressQuickActions1803ReflectionHandles handles =
                    GboardLongPressQuickActions1803Runtime.reflectionHandles(
                            pointerTracker.getClass().getClassLoader());
            SwipeSession session = SESSIONS.get(pointerTracker);
            View ownerView = currentBackspaceOwner(pointerTracker, handles);

            int actionMasked = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            boolean pointerDown = actionMasked == MotionEvent.ACTION_DOWN
                    || (actionMasked == MotionEvent.ACTION_POINTER_DOWN
                    && actionIndex == pointerIndex);
            boolean pointerUp = actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL
                    || (actionMasked == MotionEvent.ACTION_POINTER_UP
                    && actionIndex == pointerIndex);

            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);

            if (pointerDown) {
                SESSIONS.remove(pointerTracker);
                return false;
            }
            if (pointerUp) {
                boolean consume = session != null
                        && session.verticalLocked
                        && session.verticalDirection > 0;
                if (consume
                        && actionMasked != MotionEvent.ACTION_CANCEL
                        && session.restoreArmed) {
                    InputMethodService service = findInputMethodService(
                            session.backspaceView.getContext());
                    if (service != null) {
                        postRestoreAfterRelease(service, session.backspaceView);
                    }
                }
                SESSIONS.remove(pointerTracker);
                return consume;
            }

            if (session == null) {
                if (ownerView == null) {
                    return false;
                }
                // pvi.t() usually starts at MOVE on this build.  Use the first MOVE as the
                // baseline so all deltas stay in MotionEvent/root coordinates.
                session = new SwipeSession(ownerView, x, y);
                SESSIONS.put(pointerTracker, session);
                return false;
            }

            View origin = session.backspaceView;
            if (origin == null) {
                SESSIONS.remove(pointerTracker);
                return false;
            }

            float dx = x - session.startX;
            float dy = y - session.startY;
            float absX = Math.abs(dx);
            float absY = Math.abs(dy);
            float density = origin.getResources().getDisplayMetrics().density;
            float armDistance = Math.max(6.0f * density, origin.getHeight() * 0.10f);
            float actionDistance = Math.max(16.0f * density, origin.getHeight() * 0.28f);

            if (!session.verticalLocked && !session.horizontalLocked) {
                // Preserve stock Backspace left-swipe gesture-delete.
                if (dx < -armDistance
                        && absX > Math.max(armDistance, absY * 1.35f)) {
                    session.horizontalLocked = true;
                    return false;
                }
                if (absY >= armDistance && absY >= absX * 0.45f) {
                    session.verticalLocked = true;
                    session.verticalDirection = dy < 0.0f ? -1 : 1;
                    try {
                        handles.cancelScheduledLongPress(pointerTracker);
                    } catch (Throwable ignored) {
                        // Best effort only.
                    }
                }
            }

            if (session.horizontalLocked || !session.verticalLocked) {
                return false;
            }

            if (session.verticalDirection < 0) {
                // Upward gesture: remember what V8 is about to remove, then let the existing
                // runtime perform the actual deletion.
                if (!session.captureAttempted && -dy >= actionDistance) {
                    session.captureAttempted = true;
                    InputMethodService service = findInputMethodService(origin.getContext());
                    if (service != null) {
                        InputConnection connection = service.getCurrentInputConnection();
                        DeletionRecord record = captureDeletionRecord(service, connection);
                        if (record != null) {
                            LAST_DELETION.put(service, record);
                            log("captured " + record.deletedText.length()
                                    + " chars before swipe-up delete");
                        }
                    }
                }
                return false;
            }

            // Downward gesture: once the direction is locked, keep it away from stock Gboard.
            // Arm the undo here, but wait until ACTION_UP before restoring. This prevents the
            // stock Backspace action emitted on release from deleting the first restored char.
            if (!session.restoreArmed && dy >= actionDistance) {
                session.restoreArmed = true;
                log("swipe-down restore armed");
            }
            return true;
        } catch (Throwable throwable) {
            logError("Backspace swipe-down motion handling failed", throwable);
            return false;
        }
    }

    /**
     * Pre-retarget guard for the downward gesture.  Upward suppression remains owned by the
     * existing V8 runtime; horizontal-left movement is deliberately left untouched.
     */
    public static boolean maybeSuppressBackspaceUndoRetarget(
            Object pointerTracker, MotionEvent event, int pointerIndex) {
        if (pointerTracker == null || event == null
                || !GboardLongPressQuickActionsRuntimeSettings.isEnabled()) {
            return false;
        }
        try {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                return false;
            }
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    GboardLongPressQuickActions1803Runtime.reflectionHandles(
                            pointerTracker.getClass().getClassLoader());
            SwipeSession session = SESSIONS.get(pointerTracker);
            View ownerView = currentBackspaceOwner(pointerTracker, handles);

            int actionMasked = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            boolean pointerDown = actionMasked == MotionEvent.ACTION_DOWN
                    || (actionMasked == MotionEvent.ACTION_POINTER_DOWN
                    && actionIndex == pointerIndex);
            boolean pointerUp = actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL
                    || (actionMasked == MotionEvent.ACTION_POINTER_UP
                    && actionIndex == pointerIndex);

            if (pointerDown) {
                SESSIONS.remove(pointerTracker);
                return false;
            }
            if (pointerUp) {
                boolean consume = session != null
                        && session.verticalLocked
                        && session.verticalDirection > 0;
                if (consume
                        && actionMasked != MotionEvent.ACTION_CANCEL
                        && session.restoreArmed) {
                    InputMethodService service = findInputMethodService(
                            session.backspaceView.getContext());
                    if (service != null) {
                        postRestoreAfterRelease(service, session.backspaceView);
                    }
                }
                SESSIONS.remove(pointerTracker);
                return consume;
            }

            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            if (session == null) {
                if (ownerView == null) {
                    return false;
                }
                session = new SwipeSession(ownerView, x, y);
                SESSIONS.put(pointerTracker, session);
                return false;
            }
            if (session.horizontalLocked) {
                return false;
            }

            float dx = x - session.startX;
            float dy = y - session.startY;
            float absX = Math.abs(dx);
            float absY = Math.abs(dy);
            float density = session.backspaceView.getResources().getDisplayMetrics().density;
            float guardDistance = Math.max(2.0f * density,
                    session.backspaceView.getHeight() * 0.035f);

            if (dx < -guardDistance
                    && absX > Math.max(guardDistance, absY * 1.35f)) {
                session.horizontalLocked = true;
                return false;
            }

            // Only own downward retarget suppression.  Returning false for upward movement
            // lets the existing V8 guard continue handling swipe-up exactly as before.
            return dy >= guardDistance && dy >= absX * 0.30f;
        } catch (Throwable throwable) {
            logError("Backspace swipe-down retarget suppression failed", throwable);
            return false;
        }
    }

    private static View currentBackspaceOwner(
            Object pointerTracker,
            GboardLongPressQuickActions1803ReflectionHandles handles) throws Throwable {
        Object ownerObject = handles.extractPointerCurrentOwner(pointerTracker);
        View ownerView = ownerObject instanceof View ? (View) ownerObject : null;
        if (GboardLongPressQuickActions1803Runtime.isDeleteKeyView(ownerView)) {
            return ownerView;
        }
        View anchor = GboardZhuyinSlideRuntime.backspaceAnchorView(pointerTracker);
        return GboardLongPressQuickActions1803Runtime.isDeleteKeyView(anchor) ? anchor : null;
    }

    static DeletionRecord captureDeletionRecord(InputConnection connection) {
        return captureDeletionRecord(null, connection);
    }

    static DeletionRecord captureDeletionRecord(
            InputMethodService service, InputConnection connection) {
        if (connection == null) {
            return null;
        }
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = Integer.MAX_VALUE;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted != null
                    && extracted.text != null
                    && extracted.startOffset == 0
                    && extracted.selectionStart >= 0
                    && extracted.selectionEnd >= 0) {
                String fullText = extracted.text.toString();
                int boundary = Math.min(extracted.selectionStart, extracted.selectionEnd);
                boundary = Math.max(0, Math.min(boundary, fullText.length()));
                if (boundary == 0) {
                    return null;
                }
                return new DeletionRecord(
                        connection,
                        fullText.substring(0, boundary),
                        fullText.substring(boundary),
                        true,
                        SystemClock.elapsedRealtime(),
                        editorFingerprint(service));
            }

            CharSequence before = connection.getTextBeforeCursor(FALLBACK_CAPTURE_CHARS, 0);
            if (before == null || before.length() == 0) {
                return null;
            }
            return new DeletionRecord(
                    connection,
                    before.toString(),
                    null,
                    false,
                    SystemClock.elapsedRealtime(),
                    editorFingerprint(service));
        } catch (Throwable throwable) {
            logError("Failed to capture swipe-up deletion text", throwable);
            return null;
        }
    }

    static boolean restoreDeletionRecord(InputConnection connection, DeletionRecord record) {
        return restoreDeletionRecord(null, connection, record);
    }

    static boolean restoreDeletionRecord(
            InputMethodService service,
            InputConnection connection,
            DeletionRecord record) {
        if (connection == null || record == null || record.deletedText.isEmpty()) {
            return false;
        }
        if (SystemClock.elapsedRealtime() - record.capturedAtMs > UNDO_WINDOW_MS) {
            return false;
        }

        String currentEditor = editorFingerprint(service);
        if (record.editorFingerprint != null
                && currentEditor != null
                && !record.editorFingerprint.equals(currentEditor)) {
            return false;
        }

        InputConnection capturedConnection = record.connection.get();
        if (capturedConnection != connection) {
            // A few editors rebuild InputConnection after the field becomes completely empty.
            // Only permit that for a full snapshot and when we can prove it is still the same
            // editor through EditorInfo.
            if (!record.hasFullSnapshot
                    || record.editorFingerprint == null
                    || !record.editorFingerprint.equals(currentEditor)) {
                return false;
            }
        }

        boolean batchStarted = false;
        try {
            if (record.hasFullSnapshot) {
                if (!matchesExpectedPostDeleteState(connection, record.expectedAfterText)) {
                    return false;
                }
            } else {
                CharSequence selected = connection.getSelectedText(0);
                if (selected != null && selected.length() != 0) {
                    return false;
                }
                CharSequence before = connection.getTextBeforeCursor(1, 0);
                if (before != null && before.length() != 0) {
                    return false;
                }
            }

            batchStarted = connection.beginBatchEdit();
            return connection.commitText(record.deletedText, 1);
        } catch (Throwable throwable) {
            logError("Failed to restore swipe-up deletion text", throwable);
            return false;
        } finally {
            if (batchStarted) {
                try {
                    connection.endBatchEdit();
                } catch (Throwable ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
    }

    private static boolean matchesExpectedPostDeleteState(
            InputConnection connection, String expectedAfterText) {
        String expected = expectedAfterText != null ? expectedAfterText : "";
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = Integer.MAX_VALUE;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted != null
                    && extracted.text != null
                    && extracted.startOffset == 0
                    && extracted.selectionStart == 0
                    && extracted.selectionEnd == 0
                    && expected.equals(extracted.text.toString())) {
                return true;
            }
        } catch (Throwable ignored) {
            // Fall through to cursor-local validation below.
        }

        try {
            CharSequence selected = connection.getSelectedText(0);
            if (selected != null && selected.length() != 0) {
                return false;
            }
            CharSequence before = connection.getTextBeforeCursor(1, 0);
            if (before != null && before.length() != 0) {
                return false;
            }
            if (expected.length() >= FALLBACK_CAPTURE_CHARS) {
                return false;
            }
            int requested = Math.max(1, expected.length() + 1);
            CharSequence after = connection.getTextAfterCursor(requested, 0);
            if (after == null) {
                // Some editors return null, rather than an empty CharSequence, after deleting
                // the whole field. At cursor position zero this is a valid empty state.
                return expected.isEmpty();
            }
            return expected.contentEquals(after);
        } catch (Throwable throwable) {
            logError("Failed to validate post-delete editor state", throwable);
            return false;
        }
    }

    private static String editorFingerprint(InputMethodService service) {
        if (service == null) {
            return null;
        }
        try {
            EditorInfo info = service.getCurrentInputEditorInfo();
            if (info == null) {
                return null;
            }
            return String.valueOf(info.packageName) + "|"
                    + info.fieldId + "|"
                    + String.valueOf(info.fieldName) + "|"
                    + info.inputType + "|"
                    + info.imeOptions + "|"
                    + String.valueOf(info.privateImeOptions);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void postRestoreAfterRelease(
            InputMethodService service, View backspaceView) {
        if (service == null || backspaceView == null) {
            return;
        }
        backspaceView.postDelayed(() -> {
            boolean restored = restoreLastDeletion(service);
            log("post-release swipe-down restore restored=" + restored);
        }, RESTORE_AFTER_RELEASE_DELAY_MS);
    }

    private static boolean restoreLastDeletion(InputMethodService service) {
        DeletionRecord record = LAST_DELETION.remove(service);
        if (record == null) {
            return false;
        }
        return restoreDeletionRecord(
                service, service.getCurrentInputConnection(), record);
    }

    private static InputMethodService findInputMethodService(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof InputMethodService) {
                return (InputMethodService) current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private static void log(String message) {
        try {
            if (LOG_COUNT.getAndIncrement() < 80) {
                Log.i(TAG, LOG_PREFIX + message);
            }
        } catch (Throwable ignored) {
            // Logging must never affect input.
        }
    }

    private static void logError(String message, Throwable throwable) {
        try {
            if (LOG_COUNT.getAndIncrement() < 80) {
                Log.w(TAG, LOG_PREFIX + message, throwable);
            }
        } catch (Throwable ignored) {
            // Logging must never affect input.
        }
    }

    static final class DeletionRecord {
        final WeakReference<InputConnection> connection;
        final String deletedText;
        final String expectedAfterText;
        final boolean hasFullSnapshot;
        final long capturedAtMs;
        final String editorFingerprint;

        DeletionRecord(InputConnection connection, String deletedText, String expectedAfterText,
                boolean hasFullSnapshot, long capturedAtMs) {
            this(connection, deletedText, expectedAfterText, hasFullSnapshot, capturedAtMs, null);
        }

        DeletionRecord(InputConnection connection, String deletedText, String expectedAfterText,
                boolean hasFullSnapshot, long capturedAtMs, String editorFingerprint) {
            this.connection = new WeakReference<>(connection);
            this.deletedText = deletedText != null ? deletedText : "";
            this.expectedAfterText = expectedAfterText;
            this.hasFullSnapshot = hasFullSnapshot;
            this.capturedAtMs = capturedAtMs;
            this.editorFingerprint = editorFingerprint;
        }
    }

    private static final class SwipeSession {
        final View backspaceView;
        final float startX;
        final float startY;
        boolean verticalLocked;
        boolean horizontalLocked;
        int verticalDirection;
        boolean captureAttempted;
        boolean restoreArmed;
        boolean restoreAttempted;
        boolean restored;

        SwipeSession(View backspaceView, float startX, float startY) {
            this.backspaceView = backspaceView;
            this.startX = startX;
            this.startY = startY;
        }
    }
}
