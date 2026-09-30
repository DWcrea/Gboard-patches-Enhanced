package dev.jason.gboardpatches.extension.longpressquickactions;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import dev.jason.gboardpatches.extension.zhuyinslide.GboardZhuyinSlideRuntime;
import dev.jason.gboardpatches.extension.textexpansion.GboardTextExpansionRuntime;

public final class GboardLongPressQuickActions1803Runtime {
    private static final String TAG = "GboardPatches";
    private static final String LOG_PREFIX = "[gboard-long-press-quick-actions-18.0.3] ";
    private static final String DELETE_KEY_RESOURCE_ENTRY = "key_pos_del";
    private static final int UNKNOWN_DELETE_PRESS_CARRIER_CODE = Integer.MIN_VALUE;
    private static final long HALF_WIDTH_PUNCTUATION_WINDOW_MS = 5_000L;
    private static volatile int deletePressCarrierCode = UNKNOWN_DELETE_PRESS_CARRIER_CODE;

    private static final Map<ClassLoader,
            WeakReference<GboardLongPressQuickActions1803ReflectionHandles>>
            HANDLES_BY_CLASS_LOADER = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Object> PATCHED_METADATA_BY_ORIGINAL =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Object>> ORIGINAL_METADATA_BY_PATCHED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> UNPATCHED_METADATA_MARKERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Boolean> PATCHED_METADATA_MARKERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final AtomicInteger PATCH_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger SCHEDULE_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger ACTION_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger ERROR_LOG_COUNT = new AtomicInteger();
    private static final AtomicInteger DIAG_LOG_COUNT = new AtomicInteger();
    private static final Object DIAG_LOCK = new Object();
    private static volatile Context DIAG_CONTEXT;
    private static volatile boolean DIAG_INIT_WRITTEN;
    private static final Map<Object, BackspaceSwipeSession> BACKSPACE_SWIPE_SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<InputMethodService, Long> HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GboardLongPressQuickActions1803Runtime() {
    }

    public static GboardLongPressQuickActions1803ReflectionHandles reflectionHandles(
            ClassLoader classLoader) throws Throwable {
        if (classLoader == null) {
            throw new IllegalArgumentException("Target ClassLoader is required");
        }
        synchronized (HANDLES_BY_CLASS_LOADER) {
            WeakReference<GboardLongPressQuickActions1803ReflectionHandles> reference =
                    HANDLES_BY_CLASS_LOADER.get(classLoader);
            GboardLongPressQuickActions1803ReflectionHandles cached =
                    reference == null ? null : reference.get();
            if (cached != null) {
                return cached;
            }
            GboardLongPressQuickActions1803ReflectionHandles created =
                    new GboardLongPressQuickActions1803ReflectionHandles(classLoader);
            HANDLES_BY_CLASS_LOADER.put(classLoader, new WeakReference<>(created));
            return created;
        }
    }

    public static Object maybePatchMetadata(Object metadata, View softKeyView) {
        metadata = GboardGlobeDragRuntime.maybePatchMetadata(metadata, softKeyView);
        Object incomingMetadata = metadata;
        try {
            if (softKeyView != null) {
                Context c = softKeyView.getContext();
                if (c != null) {
                    Context app = c.getApplicationContext();
                    DIAG_CONTEXT = app != null ? app : c;
                    diagInitOnce();
                }
            }
            boolean enabled = GboardLongPressQuickActionsRuntimeSettings.isEnabled();
            metadata = metadataForBind(enabled, metadata);
            if (!enabled || metadata == null || isPatchedMetadata(metadata)) {
                return metadata;
            }

            ClassLoader classLoader = metadata.getClass().getClassLoader();
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(classLoader);
            boolean deleteCandidate = isDeleteKeyCandidate(softKeyView, metadata, handles);
            if (deleteCandidate) {
                diag("DELETE_BIND view=" + viewName(softKeyView)
                        + " carrier=" + handles.extractPressCarrierCode(metadata)
                        + " enabled=" + enabled);
            }
            if (!deleteCandidate
                    && Boolean.TRUE.equals(UNPATCHED_METADATA_MARKERS.get(metadata))) {
                return metadata;
            }

            synchronized (PATCHED_METADATA_BY_ORIGINAL) {
                Object cached = PATCHED_METADATA_BY_ORIGINAL.get(metadata);
                if (cached != null) {
                    return cached;
                }
                Context context = softKeyView == null ? null : softKeyView.getContext();

                Object patched = metadata;
                boolean changed = false;

                GboardLongPressQuickActions1803Policy.QuickAction action =
                        GboardLongPressQuickActions1803Policy.plan(
                                handles.extractKeyId(patched),
                                handles.extractPressText(patched),
                                handles.extractLongPressCodes(patched));
                if (action != null) {
                    Object longPressPatched = handles.appendLongPressAction(
                            context, patched, action);
                    if (longPressPatched != null) {
                        patched = longPressPatched;
                        changed = true;
                        logInfo(PATCH_LOG_COUNT, 30,
                                "appended keycode=" + action.actionCode
                                        + ", name=" + action.debugName
                                        + ", icon=0x"
                                        + Integer.toHexString(action.iconResId));
                    }
                }

                // The pointer-motion implementation is the primary path on the normal
                // alphabetic keyboard.  Keep a metadata SLIDE_UP fallback as well because
                // number/phone keypad layouts can route Backspace through the ordinary
                // SoftKey action dispatcher instead of the same pvi motion path.
                if (deleteCandidate) {
                    Object slideUpPatched = handles.replaceSlideUpAction(
                            patched,
                            GboardLongPressQuickActions1803Policy
                                    .BACKSPACE_SWIPE_UP_CLEAR_ACTION_CODE);
                    if (slideUpPatched != null) {
                        patched = slideUpPatched;
                        changed = true;
                        logInfo(PATCH_LOG_COUNT, 30,
                                "installed Backspace SLIDE_UP fallback view="
                                        + viewName(softKeyView));
                    }
                }

                if (!changed || patched == metadata) {
                    UNPATCHED_METADATA_MARKERS.put(metadata, Boolean.TRUE);
                    return metadata;
                }
                rememberPatchedMetadata(metadata, patched);
                return patched;
            }
        } catch (Throwable throwable) {
            markUnpatchedSafely(incomingMetadata);
            logError("metadata patch failed", throwable);
            return incomingMetadata;
        }
    }

    private static boolean isDeleteKeyResourceView(View softKeyView) {
        if (softKeyView == null || softKeyView.getId() == View.NO_ID) {
            return false;
        }
        try {
            return DELETE_KEY_RESOURCE_ENTRY.equals(
                    softKeyView.getResources().getResourceEntryName(softKeyView.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isDeleteKeyCandidate(
            View softKeyView,
            Object metadata,
            GboardLongPressQuickActions1803ReflectionHandles handles) throws Throwable {
        if (softKeyView == null || metadata == null || handles == null) {
            return false;
        }
        int carrierCode = handles.extractPressCarrierCode(metadata);
        if (isDeleteKeyResourceView(softKeyView)) {
            if (carrierCode != 0) {
                deletePressCarrierCode = carrierCode;
            }
            return true;
        }

        // Some numeric/phone layouts bind their Backspace key to a different/no resource id.
        // The PRESS carrier code is still the same editing action as the canonical
        // key_pos_del key.  Learn that code from a canonical binding and use it as a
        // layout-independent fallback.
        int knownDeleteCode = deletePressCarrierCode;
        return knownDeleteCode != UNKNOWN_DELETE_PRESS_CARRIER_CODE
                && carrierCode != 0
                && carrierCode == knownDeleteCode;
    }

    static boolean isDeleteKeyView(View softKeyView) {
        if (isDeleteKeyResourceView(softKeyView)) {
            return true;
        }
        int knownDeleteCode = deletePressCarrierCode;
        if (softKeyView == null
                || knownDeleteCode == UNKNOWN_DELETE_PRESS_CARRIER_CODE) {
            return false;
        }
        try {
            ClassLoader classLoader = softKeyView.getClass().getClassLoader();
            if (classLoader == null) {
                return false;
            }
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(classLoader);
            Object metadata = handles.extractSoftKeyMetadata(softKeyView);
            return metadata != null
                    && handles.extractPressCarrierCode(metadata) == knownDeleteCode;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean maybeHandleInputEvent(
            InputMethodService service,
            Object event) {
        if (service == null || event == null) {
            return false;
        }
        if (GboardGlobeDragRuntime.maybeHandleInputEvent(service, event)) {
            return true;
        }
        try {
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(service.getClass().getClassLoader());
            Object metadata = handles.extractEventMetadata(event);
            String actionTypeName = handles.extractEventActionTypeName(event);
            int keyId = handles.extractKeyId(metadata);
            String pressText = handles.extractPressText(metadata);
            int selectedCode = handles.extractSelectedEventCode(event);
            Object selectedPayloadObject = handles.extractSelectedEventPayload(event);
            String selectedPayload = selectedPayloadObject instanceof CharSequence
                    ? selectedPayloadObject.toString() : null;
            String eventText = selectedPayload;
            if ((eventText == null || eventText.isEmpty())
                    && "PRESS".equals(actionTypeName)) {
                eventText = pressText;
            }
            if (eventText == null || eventText.isEmpty()) {
                if (selectedCode == ':' || selectedCode == '.'
                        || selectedCode == 0xFF1A
                        || selectedCode == 0x3002
                        || selectedCode == 0xFF0E) {
                    eventText = String.valueOf((char) selectedCode);
                }
            }
            if (GboardTextExpansionRuntime.maybeHandleInputEvent(
                    service, actionTypeName, selectedCode, eventText)) {
                return true;
            }
            boolean enabled = GboardLongPressQuickActionsRuntimeSettings.isEnabled();
            if (enabled && maybeHandleHalfWidthPunctuationAfterSlideDigit(
                    service, actionTypeName, selectedCode, eventText)) {
                return true;
            }
            boolean quickActionEvent = GboardLongPressQuickActions1803Policy
                    .isQuickActionEvent(keyId, pressText, actionTypeName, selectedCode);
            // The synthetic code is private to this patch. Do not require WeakHashMap identity
            // here: Gboard may clone/rebuild metadata between view binding and event dispatch.
            boolean backspaceSwipeClearEvent = GboardLongPressQuickActions1803Policy
                    .isBackspaceSwipeUpClearEvent(actionTypeName, selectedCode);
            if (shouldConsumeDisabledInjectedEvent(
                    enabled, metadata, quickActionEvent || backspaceSwipeClearEvent)) {
                logInfo(ACTION_LOG_COUNT, 30, "suppressed disabled injected action");
                return true;
            }
            if (!enabled) {
                return false;
            }
            if (backspaceSwipeClearEvent) {
                return consumeRecognizedClearAll(service::getCurrentInputConnection);
            }
            Integer contextMenuAction =
                    GboardLongPressQuickActions1803Policy.contextMenuActionFor(
                            keyId, pressText, actionTypeName, selectedCode);
            if (contextMenuAction == null) {
                return false;
            }
            return consumeRecognizedContextMenuAction(
                    service::getCurrentInputConnection, contextMenuAction.intValue());
        } catch (Throwable throwable) {
            logError("input event handling failed", throwable);
            return false;
        }
    }

    static boolean isSlideDownDigitEvent(
            String actionTypeName, int selectedCode, String selectedPayload) {
        if (!"SLIDE_DOWN".equals(actionTypeName)) {
            return false;
        }
        if (selectedPayload != null
                && selectedPayload.length() == 1
                && selectedPayload.charAt(0) >= '0'
                && selectedPayload.charAt(0) <= '9') {
            return true;
        }
        return selectedCode >= '0' && selectedCode <= '9';
    }

    static String halfWidthPunctuationFor(String selectedPayload) {
        if (selectedPayload == null || selectedPayload.length() != 1) {
            return null;
        }
        char value = selectedPayload.charAt(0);
        if (value == ':' || value == '\uFF1A') {
            return ":";
        }
        if (value == '.' || value == '\u3002' || value == '\uFF0E') {
            return ".";
        }
        return null;
    }

    private static boolean isCommitLikeAction(String actionTypeName) {
        return "PRESS".equals(actionTypeName)
                || "LONG_PRESS".equals(actionTypeName)
                || "SLIDE_UP".equals(actionTypeName)
                || "SLIDE_DOWN".equals(actionTypeName);
    }

    private static boolean maybeHandleHalfWidthPunctuationAfterSlideDigit(
            InputMethodService service,
            String actionTypeName,
            int selectedCode,
            String selectedPayload) {
        if (service == null) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (isSlideDownDigitEvent(actionTypeName, selectedCode, selectedPayload)) {
            HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT.put(service, Long.valueOf(now));
            return false;
        }

        Long armedAt = HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT.get(service);
        if (armedAt == null) {
            return false;
        }
        if (now - armedAt.longValue() > HALF_WIDTH_PUNCTUATION_WINDOW_MS) {
            HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT.remove(service);
            return false;
        }

        String replacement = halfWidthPunctuationFor(selectedPayload);
        if (replacement != null && isCommitLikeAction(actionTypeName)) {
            HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT.remove(service);
            try {
                InputConnection connection = service.getCurrentInputConnection();
                if (connection != null && connection.commitText(replacement, 1)) {
                    logInfo(ACTION_LOG_COUNT, 60,
                            "converted punctuation after slide-down digit to " + replacement);
                    return true;
                }
            } catch (Throwable throwable) {
                logError("half-width punctuation commit failed", throwable);
            }
            return false;
        }

        // Ignore non-commit lifecycle events so an UP/CANCEL following the number gesture
        // does not disarm the one-shot conversion.  The next real committed key does.
        if (isCommitLikeAction(actionTypeName)
                && ((selectedPayload != null && !selectedPayload.isEmpty())
                || selectedCode != 0)) {
            HALF_WIDTH_PUNCTUATION_AFTER_SLIDE_DIGIT.remove(service);
        }
        return false;
    }

    /**
     * Handles Backspace swipe-up directly from the pointer-owner path.  Gboard's Backspace
     * does not dispatch its vertical gesture through the normal SoftKey SLIDE_UP action path
     * used by ordinary keys, so metadata injection alone cannot intercept it.
     *
     * This method is called immediately before Gboard retargets the active pointer.  We inspect
     * the current pointer owner (the key where the gesture started) and Gboard's own resolved
     * gesture direction.  If the owner is Backspace and the gesture is SLIDE_UP, the editor is
     * cleared directly and the pointer retarget is suppressed for that move.
     */
    public static boolean maybeHandleBackspaceSwipeUp(
            Object pointerTracker, View incomingSoftKeyView, float x, float y) {
        if (pointerTracker == null
                || !GboardLongPressQuickActionsRuntimeSettings.isEnabled()) {
            return false;
        }
        try {
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(pointerTracker.getClass().getClassLoader());
            Object currentOwner = handles.extractPointerCurrentOwner(pointerTracker);
            View ownerView = currentOwner instanceof View ? (View) currentOwner : null;
            if (!isDeleteKeyView(ownerView)) {
                ownerView = GboardZhuyinSlideRuntime.backspaceAnchorView(pointerTracker);
                if (!isDeleteKeyView(ownerView)) {
                    return false;
                }
            }
            String gesture = handles.resolvePointerGestureActionName(pointerTracker, x, y);
            if (!"SLIDE_UP".equals(gesture)) {
                return false;
            }

            InputMethodService service = findInputMethodService(ownerView.getContext());
            if (service == null && incomingSoftKeyView != null) {
                service = findInputMethodService(incomingSoftKeyView.getContext());
            }
            if (service == null) {
                logInfo(ACTION_LOG_COUNT, 30,
                        "backspace swipe-up recognized but IME service context was unavailable");
                return false;
            }

            boolean cleared = deleteBeforeCursor(service.getCurrentInputConnection());
            if (!cleared) {
                logInfo(ACTION_LOG_COUNT, 30,
                        "backspace swipe-up recognized but editor clear failed");
                return false;
            }
            logInfo(ACTION_LOG_COUNT, 30,
                    "performed direct pointer backspace swipe-up clear");
            return true;
        } catch (Throwable throwable) {
            logError("direct backspace swipe-up handling failed", throwable);
            return false;
        }
    }

    /** V5: intercept Backspace motion before Gboard retargets the pointer to a neighbour. */
    public static boolean maybeInterceptBackspaceMotion(
            Object pointerTracker, MotionEvent event, int pointerIndex) {
        if (pointerTracker == null || event == null) {
            diag("T_ENTER null tracker/event tracker=" + (pointerTracker != null)
                    + " event=" + (event != null));
            return false;
        }
        boolean featureEnabled = GboardLongPressQuickActionsRuntimeSettings.isEnabled();
        diag("T_ENTER action=" + event.getActionMasked()
                + " idx=" + pointerIndex
                + " count=" + event.getPointerCount()
                + " enabled=" + featureEnabled);
        if (!featureEnabled) {
            return false;
        }
        try {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                return false;
            }
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(pointerTracker.getClass().getClassLoader());
            BackspaceSwipeSession session = BACKSPACE_SWIPE_SESSIONS.get(pointerTracker);
            Object ownerObject = handles.extractPointerCurrentOwner(pointerTracker);
            View ownerView = ownerObject instanceof View ? (View) ownerObject : null;
            diag("T_OWNER view=" + viewName(ownerView)
                    + " isDelete=" + isDeleteKeyView(ownerView)
                    + " session=" + (session != null));

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

            // A tracker may be reused across gestures. A real DOWN is an authoritative new
            // session boundary even if an earlier UP/CANCEL never reached this helper.
            if (pointerDown) {
                if (!isDeleteKeyView(ownerView)) {
                    diag("T_DOWN not-delete owner=" + viewName(ownerView));
                    BACKSPACE_SWIPE_SESSIONS.remove(pointerTracker);
                    return false;
                }
                diag("T_DOWN DELETE x=" + x + " y=" + y + " owner=" + viewName(ownerView));
                session = new BackspaceSwipeSession(ownerView, x, y);
                BACKSPACE_SWIPE_SESSIONS.put(pointerTracker, session);
                return false;
            }

            if (session == null) {
                if (!isDeleteKeyView(ownerView)) {
                    diag("T_NO_SESSION non-delete owner=" + viewName(ownerView));
                    return false;
                }
                // pvi.t() is reached first on MOVE on this Gboard build, not on DOWN.  The
                // MotionEvent coordinates are in the keyboard/root coordinate space whereas
                // ownerView.getLeft()/getTop() are relative to the key's parent row.  V6 mixed
                // those spaces, producing bogus +300..+400 px dy values and immediately
                // misclassifying an upward swipe as horizontal.  Use this first MOVE as the
                // baseline; the next MOVE gives us a real delta in one coordinate space.
                diag("T_RECOVER_SESSION owner=" + viewName(ownerView)
                        + " baseline=current x=" + x + " y=" + y);
                session = new BackspaceSwipeSession(ownerView, x, y);
                BACKSPACE_SWIPE_SESSIONS.put(pointerTracker, session);
                return false;
            }

            if (pointerUp) {
                boolean consume = session.verticalLocked || session.cleared;
                BACKSPACE_SWIPE_SESSIONS.remove(pointerTracker);
                return consume;
            }

            View origin = session.backspaceView;
            if (origin == null) {
                BACKSPACE_SWIPE_SESSIONS.remove(pointerTracker);
                return false;
            }
            float dx = x - session.startX;
            float dy = y - session.startY;
            float up = -dy;
            float absX = Math.abs(dx);
            float density = origin.getResources().getDisplayMetrics().density;
            float armDistance = Math.max(6.0f * density, origin.getHeight() * 0.10f);
            float clearDistance = Math.max(16.0f * density, origin.getHeight() * 0.28f);
            diag("T_MOVE dx=" + dx + " dy=" + dy + " up=" + up
                    + " arm=" + armDistance + " clear=" + clearDistance
                    + " vlock=" + session.verticalLocked
                    + " hlock=" + session.horizontalLocked
                    + " cleared=" + session.cleared);

            if (!session.verticalLocked && !session.horizontalLocked) {
                if (dx < -armDistance && absX > Math.max(armDistance, up * 1.35f)) {
                    session.horizontalLocked = true;
                    diag("T_LOCK_HORIZONTAL dx=" + dx + " up=" + up);
                    return false;
                }
                if (up >= armDistance && up >= absX * 0.45f) {
                    session.verticalLocked = true;
                    diag("T_LOCK_VERTICAL dx=" + dx + " up=" + up);
                    try {
                        handles.cancelScheduledLongPress(pointerTracker);
                    } catch (Throwable ignored) {
                        // Best effort only.
                    }
                }
            }
            if (session.horizontalLocked) {
                return false;
            }
            if (!session.verticalLocked) {
                return false;
            }

            if (!session.cleared && up >= clearDistance) {
                InputMethodService service = findInputMethodService(origin.getContext());
                boolean cleared = service != null
                        && deleteBeforeCursor(service.getCurrentInputConnection());
                session.cleared = cleared;
                diag("T_CLEAR attempted cleared=" + cleared + " dx=" + dx + " dy=" + dy);
                logInfo(ACTION_LOG_COUNT, 60,
                        "V5 backspace swipe-up clear: cleared=" + cleared
                                + ", dx=" + dx + ", dy=" + dy
                                + ", threshold=" + clearDistance);
            }
            return true;
        } catch (Throwable throwable) {
            diag("T_EXCEPTION " + throwable.getClass().getName() + ": " + String.valueOf(throwable.getMessage()));
            logError("V6 backspace motion interception failed", throwable);
            return false;
        }
    }

    /**
     * V6 pre-retarget guard. This uses a deliberately smaller threshold than the clear gesture
     * so Gboard cannot hand the active pointer from Backspace to L while the user is still
     * completing the upward swipe. It never clears text by itself.
     */
    public static boolean maybeSuppressBackspaceRetarget(
            Object pointerTracker, MotionEvent event, int pointerIndex) {
        if (pointerTracker == null || event == null) {
            diag("F_ENTER null tracker/event");
            return false;
        }
        boolean featureEnabled = GboardLongPressQuickActionsRuntimeSettings.isEnabled();
        diag("F_ENTER action=" + event.getActionMasked()
                + " idx=" + pointerIndex + " enabled=" + featureEnabled);
        if (!featureEnabled) {
            return false;
        }
        try {
            if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
                return false;
            }
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(pointerTracker.getClass().getClassLoader());
            BackspaceSwipeSession session = BACKSPACE_SWIPE_SESSIONS.get(pointerTracker);
            Object ownerObject = handles.extractPointerCurrentOwner(pointerTracker);
            View ownerView = ownerObject instanceof View ? (View) ownerObject : null;
            int actionMasked = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            boolean pointerDown = actionMasked == MotionEvent.ACTION_DOWN
                    || (actionMasked == MotionEvent.ACTION_POINTER_DOWN
                    && actionIndex == pointerIndex);
            boolean pointerUp = actionMasked == MotionEvent.ACTION_UP
                    || actionMasked == MotionEvent.ACTION_CANCEL
                    || (actionMasked == MotionEvent.ACTION_POINTER_UP
                    && actionIndex == pointerIndex);

            // V6 showed that pvi.t() never receives DOWN/UP for the primary pointer, while
            // pvi.F() does receive ACTION_DOWN.  Reset here so horizontalLocked/cleared state
            // cannot leak into the next gesture.  This stale-session leak was why every later
            // attempt stayed hlock=true after the first bad classification.
            if (pointerDown) {
                if (session != null) {
                    diag("F_RESET_ON_DOWN staleSession=true");
                } else {
                    diag("F_RESET_ON_DOWN staleSession=false");
                }
                BACKSPACE_SWIPE_SESSIONS.remove(pointerTracker);
                return false;
            }
            if (pointerUp) {
                boolean consume = session != null && (session.verticalLocked || session.cleared);
                diag("F_END action=" + actionMasked + " consume=" + consume);
                BACKSPACE_SWIPE_SESSIONS.remove(pointerTracker);
                return consume;
            }

            // Re-read after the session-boundary handling above.
            session = BACKSPACE_SWIPE_SESSIONS.get(pointerTracker);
            diag("F_OWNER view=" + viewName(ownerView)
                    + " isDelete=" + isDeleteKeyView(ownerView)
                    + " session=" + (session != null));
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);

            if (session == null) {
                if (!isDeleteKeyView(ownerView)) {
                    diag("F_NO_SESSION non-delete owner=" + viewName(ownerView));
                    return false;
                }
                diag("F_RECOVER_SESSION owner=" + viewName(ownerView)
                        + " baseline=current x=" + x + " y=" + y);
                session = new BackspaceSwipeSession(ownerView, x, y);
                BACKSPACE_SWIPE_SESSIONS.put(pointerTracker, session);
                return false;
            }
            if (session.horizontalLocked) {
                return false;
            }

            float dx = x - session.startX;
            float up = -(y - session.startY);
            float absX = Math.abs(dx);
            float density = session.backspaceView.getResources().getDisplayMetrics().density;
            float guardDistance = Math.max(2.0f * density,
                    session.backspaceView.getHeight() * 0.035f);

            // If movement is clearly horizontal-left, don't interfere with stock gesture-delete.
            if (dx < -guardDistance && absX > Math.max(guardDistance, up * 1.35f)) {
                session.horizontalLocked = true;
                return false;
            }

            // Guard early and generously: F() is exactly where stock Gboard would switch owner
            // to the neighbouring L key. The larger clear threshold is still enforced in t().
            boolean suppress = up >= guardDistance && up >= absX * 0.30f;
            diag("F_DECISION dx=" + dx + " up=" + up + " guard=" + guardDistance
                    + " suppress=" + suppress);
            return suppress;
        } catch (Throwable throwable) {
            diag("F_EXCEPTION " + throwable.getClass().getName() + ": " + String.valueOf(throwable.getMessage()));
            logError("V6 backspace retarget suppression failed", throwable);
            return false;
        }
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

    public static void maybeEnsureLongPressScheduled(
            Object pointerTracker,
            View softKeyView) {
        try {
            GboardGlobeDragRuntime.onPointerOwner(pointerTracker, softKeyView);
            GboardTextExpansionRuntime.observeSoftKeyPress(pointerTracker, softKeyView);
            if (pointerTracker == null || softKeyView == null
                    || !GboardLongPressQuickActionsRuntimeSettings.isEnabled()) {
                return;
            }
            GboardLongPressQuickActions1803ReflectionHandles handles =
                    reflectionHandles(pointerTracker.getClass().getClassLoader());
            Object metadata = handles.extractSoftKeyMetadata(softKeyView);
            if (metadata == null) {
                return;
            }
            int keyId = handles.extractKeyId(metadata);
            String pressText = handles.extractPressText(metadata);
            int[] longPressCodes = handles.extractLongPressCodes(metadata);
            if (!GboardLongPressQuickActions1803Policy.containsAssignedAction(
                    keyId, pressText, longPressCodes)) {
                return;
            }
            handles.scheduleLongPress(pointerTracker);
            logInfo(SCHEDULE_LOG_COUNT, 30,
                    "ensured long-press schedule keyId=0x" + Integer.toHexString(keyId));
        } catch (Throwable throwable) {
            logError("long-press schedule failed", throwable);
        }
    }

    static boolean attemptContextMenuAction(InputConnection connection, int actionId) {
        if (!GboardEditingShortcutDispatchGuard.shouldDispatchContextMenuAction(
                connection, actionId)) {
            return false;
        }
        try {
            connection.performContextMenuAction(actionId);
            return true;
        } catch (Throwable throwable) {
            logError("editor action failed", throwable);
            return false;
        }
    }

    static boolean consumeRecognizedContextMenuAction(
            InputConnection connection, int actionId) {
        return consumeRecognizedContextMenuAction(() -> connection, actionId);
    }

    static boolean consumeRecognizedContextMenuAction(
            InputConnectionSupplier connectionSupplier, int actionId) {
        try {
            InputConnection connection = connectionSupplier == null
                    ? null : connectionSupplier.get();
            if (attemptContextMenuAction(connection, actionId)) {
                logInfo(ACTION_LOG_COUNT, 30,
                        "performed contextMenuAction=0x"
                                + Integer.toHexString(actionId));
            } else {
                logInfo(ACTION_LOG_COUNT, 30,
                        "suppressed unavailable contextMenuAction=0x"
                                + Integer.toHexString(actionId));
            }
        } catch (Throwable throwable) {
            logError("editor connection lookup failed", throwable);
        }
        return true;
    }

    static boolean deleteBeforeCursor(InputConnection connection) {
        if (connection == null) {
            return false;
        }
        boolean batchStarted = false;
        try {
            batchStarted = connection.beginBatchEdit();

            // Prefer a full extracted-text snapshot.  The user-facing behavior is now
            // "delete everything before the cursor", not "select all".  When there is a
            // selection, preserve the selection and everything after it by using the
            // selection's left edge as the deletion boundary.
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = Integer.MAX_VALUE;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted != null
                    && extracted.text != null
                    && extracted.startOffset == 0
                    && extracted.selectionStart >= 0
                    && extracted.selectionEnd >= 0) {
                int boundary = Math.min(extracted.selectionStart, extracted.selectionEnd);
                boundary = Math.max(0, Math.min(boundary, extracted.text.length()));
                if (boundary == 0) {
                    return true;
                }
                if (connection.setSelection(0, boundary)
                        && connection.commitText("", 1)) {
                    return true;
                }
            }

            // Some editors do not provide a full ExtractedText snapshot.  Delete in bounded
            // chunks immediately before the cursor; this keeps all suffix text intact and
            // also avoids relying on Select-All timing.
            final int chunkChars = 4096;
            final int maxChunks = 256;
            for (int chunk = 0; chunk < maxChunks; chunk++) {
                CharSequence before = connection.getTextBeforeCursor(chunkChars, 0);
                if (before == null) {
                    return false;
                }
                int length = before.length();
                if (length == 0) {
                    return true;
                }
                if (!connection.deleteSurroundingText(length, 0)) {
                    return false;
                }
            }
            return false;
        } catch (Throwable throwable) {
            logError("delete-before-cursor editor action failed", throwable);
            return false;
        } finally {
            if (batchStarted) {
                try {
                    connection.endBatchEdit();
                } catch (Throwable ignored) {
                    // Best-effort batch cleanup only.
                }
            }
        }
    }

    // Kept as a package-private compatibility alias for the existing unit-test/API surface.
    static boolean clearAllText(InputConnection connection) {
        return deleteBeforeCursor(connection);
    }

    static boolean consumeRecognizedClearAll(InputConnectionSupplier connectionSupplier) {
        try {
            InputConnection connection = connectionSupplier == null
                    ? null : connectionSupplier.get();
            boolean cleared = deleteBeforeCursor(connection);
            logInfo(ACTION_LOG_COUNT, 30, cleared
                    ? "performed backspace swipe-up delete-before-cursor"
                    : "consumed backspace swipe-up delete-before-cursor without editor support");
        } catch (Throwable throwable) {
            logError("delete-before-cursor connection lookup failed", throwable);
        }
        // The synthetic action must never fall through into stock Gboard.
        return true;
    }

    private static final class BackspaceSwipeSession {
        final View backspaceView;
        final float startX;
        final float startY;
        boolean verticalLocked;
        boolean horizontalLocked;
        boolean cleared;

        BackspaceSwipeSession(View backspaceView, float startX, float startY) {
            this.backspaceView = backspaceView;
            this.startX = startX;
            this.startY = startY;
        }
    }

    interface InputConnectionSupplier {
        InputConnection get() throws Throwable;
    }

    static void rememberPatchedMetadata(Object original, Object patched) {
        if (original == null || patched == null) {
            return;
        }
        PATCHED_METADATA_BY_ORIGINAL.put(original, patched);
        ORIGINAL_METADATA_BY_PATCHED.put(patched, new WeakReference<>(original));
        PATCHED_METADATA_MARKERS.put(patched, Boolean.TRUE);
    }

    static Object metadataForBind(boolean enabled, Object metadata) {
        if (enabled || metadata == null) {
            return metadata;
        }
        WeakReference<Object> originalReference = ORIGINAL_METADATA_BY_PATCHED.get(metadata);
        Object original = originalReference == null ? null : originalReference.get();
        return original == null ? metadata : original;
    }

    static boolean shouldConsumeDisabledInjectedEvent(boolean enabled, Object metadata,
            boolean quickActionEvent) {
        return !enabled && quickActionEvent && isPatchedMetadata(metadata);
    }

    private static boolean isPatchedMetadata(Object metadata) {
        return metadata != null && Boolean.TRUE.equals(PATCHED_METADATA_MARKERS.get(metadata));
    }

    private static void markUnpatchedSafely(Object metadata) {
        try {
            if (metadata != null) {
                UNPATCHED_METADATA_MARKERS.put(metadata, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // Cache cleanup must not affect the keyboard path.
        }
    }

    private static void diagInitOnce() {
        if (DIAG_INIT_WRITTEN) {
            return;
        }
        synchronized (DIAG_LOCK) {
            if (DIAG_INIT_WRITTEN) {
                return;
            }
            DIAG_INIT_WRITTEN = true;
            diag("=== V6 DIAGNOSTIC START pid=" + android.os.Process.myPid()
                    + " enabled=" + GboardLongPressQuickActionsRuntimeSettings.isEnabled() + " ===");
        }
    }

    private static String viewName(View view) {
        if (view == null) {
            return "null";
        }
        try {
            int id = view.getId();
            String entry = id == View.NO_ID ? "NO_ID"
                    : view.getResources().getResourceEntryName(id);
            return view.getClass().getName() + "#" + entry;
        } catch (Throwable ignored) {
            return view.getClass().getName() + "#id=" + view.getId();
        }
    }

    private static void diag(String message) {
        try {
            if (DIAG_LOG_COUNT.getAndIncrement() >= 1200) {
                return;
            }
            Context context = DIAG_CONTEXT;
            if (context == null) {
                return;
            }
            File dir = context.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File file = new File(dir, "gboard-backspace-v6.log");
            synchronized (DIAG_LOCK) {
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(Long.toString(System.currentTimeMillis()));
                    writer.write(" ");
                    writer.write(message == null ? "null" : message);
                    writer.write("\n");
                }
            }
        } catch (Throwable ignored) {
            // Diagnostic file output must never affect the keyboard.
        }
    }

    private static void logInfo(AtomicInteger counter, int limit, String message) {
        try {
            if (counter.getAndIncrement() >= limit) {
                return;
            }
            Log.i(TAG, LOG_PREFIX + message);
        } catch (Throwable ignored) {
            // Logging must not affect the keyboard path.
        }
    }

    private static void logError(String message, Throwable throwable) {
        try {
            if (ERROR_LOG_COUNT.getAndIncrement() >= 8) {
                return;
            }
            Log.w(TAG, LOG_PREFIX + message, throwable);
        } catch (Throwable ignored) {
            // Logging must not affect the keyboard path.
        }
    }
}
