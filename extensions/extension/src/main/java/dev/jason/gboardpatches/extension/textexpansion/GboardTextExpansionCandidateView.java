package dev.jason.gboardpatches.extension.textexpansion;

import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * dev.23 native first-candidate preview.
 *
 * dev.22 can recapture Gboard's recycled candidate View, but device testing exposed two remaining
 * issues: a long expansion can trigger Gboard's candidate auto-size and become tiny, and an exact
 * match can arrive before the native candidate row has been rebound, causing the one-shot initial
 * lookup to miss the slot completely.
 *
 * dev.23 keeps the native-view approach but makes the preview session generation-safe, keeps
 * retrying when the first lookup has no candidate yet, and renders only the portion that fits at
 * the candidate's current native text size (with an ellipsis). The full expansion is still used
 * when the candidate is accepted or a trigger key is pressed.
 */
final class GboardTextExpansionCandidateView {
    private static final String HITBOX_TAG =
            "gboard-patches-text-expansion-native-candidate-hitbox-v4";
    private static final Object LOCK = new Object();
    private static final long[] RECAPTURE_DELAYS_MS = {
            16L, 32L, 56L, 88L, 128L, 192L, 288L, 420L,
            620L, 900L, 1300L, 1800L, 2500L, 3400L
    };

    private static WeakReference<FrameLayout> activeHost = new WeakReference<>(null);
    private static WeakReference<TextView> activeTextView = new WeakReference<>(null);
    private static WeakReference<View> activeHitbox = new WeakReference<>(null);
    private static CharSequence originalText = "";
    private static String activeFullDisplay = "";
    private static Runnable activeAcceptAction;
    private static TextWatcher activeWatcher;
    private static boolean internalTextChange;
    private static long generation;
    private static int reboundCount;
    private static int recaptureCount;
    private static int probeNoneCount;

    private GboardTextExpansionCandidateView() {
    }

    static void show(View anchor, String expansion, Runnable acceptAction) {
        if (anchor == null || expansion == null || expansion.isEmpty()) {
            hide();
            return;
        }
        String display = singleLine(expansion);
        if (display.isEmpty()) {
            hide();
            return;
        }

        final long requestGeneration;
        synchronized (LOCK) {
            // Reserve the generation before posting. A later hide/show therefore invalidates this
            // queued request instead of allowing stale UI work to resurrect an old candidate.
            requestGeneration = ++generation;
        }
        try {
            anchor.post(() -> showNow(anchor, display, acceptAction, requestGeneration));
        } catch (Throwable ignored) {
            // Candidate preview is optional and must never affect typing.
        }
    }

    static void hide() {
        final long hideGeneration;
        View callbackView;
        synchronized (LOCK) {
            hideGeneration = ++generation;
            callbackView = activeHitbox.get();
            if (callbackView == null) {
                callbackView = activeTextView.get();
            }
        }

        Runnable cleanup = () -> hideNowIfCurrent(hideGeneration);
        if (callbackView == null) {
            cleanup.run();
            return;
        }
        try {
            callbackView.post(cleanup);
        } catch (Throwable ignored) {
            cleanup.run();
        }
    }

    private static void showNow(
            View anchor,
            String display,
            Runnable acceptAction,
            long expectedGeneration) {
        try {
            synchronized (LOCK) {
                if (generation != expectedGeneration) {
                    return;
                }
            }
            if (!anchor.isAttachedToWindow()) {
                return;
            }

            FrameLayout host = outermostFrameLayout(anchor);
            if (host == null || !host.isAttachedToWindow()) {
                return;
            }

            boolean sameSession;
            synchronized (LOCK) {
                sameSession = activeHost.get() == host
                        && display.equals(activeFullDisplay);
            }
            if (!sameSession) {
                detachActiveState(true);
            }

            synchronized (LOCK) {
                if (generation != expectedGeneration) {
                    return;
                }
                activeHost = new WeakReference<>(host);
                activeFullDisplay = display;
                activeAcceptAction = acceptAction;
                reboundCount = 0;
                recaptureCount = 0;
                probeNoneCount = 0;
            }

            NativePlacement placement = resolveNativePlacement(host);
            if (placement != null) {
                installPlacement(placement, display, acceptAction, false);
            } else {
                // Important: do not tear the session down. On Gboard 18.0.3 the exact raw-token
                // callback can beat the candidate-row bind by a few frames.
                diag("NATIVE_CANDIDATE target=none waiting=true");
            }
            scheduleRecaptureProbes(host, expectedGeneration);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE exception=" + throwable.getClass().getSimpleName());
        }
    }

    private static void scheduleRecaptureProbes(FrameLayout host, long expectedGeneration) {
        try {
            host.postOnAnimation(() -> probeForReplacement(host, expectedGeneration, "frame"));
            for (long delay : RECAPTURE_DELAYS_MS) {
                host.postDelayed(
                        () -> probeForReplacement(host, expectedGeneration, "delay-" + delay),
                        delay);
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static void probeForReplacement(
            FrameLayout host,
            long expectedGeneration,
            String reason) {
        String display;
        Runnable acceptAction;
        TextView previous;
        synchronized (LOCK) {
            if (generation != expectedGeneration
                    || activeHost.get() != host
                    || activeFullDisplay.isEmpty()) {
                return;
            }
            display = activeFullDisplay;
            acceptAction = activeAcceptAction;
            previous = activeTextView.get();
        }
        if (!host.isAttachedToWindow()) {
            return;
        }

        NativePlacement placement;
        try {
            placement = resolveNativePlacement(host);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE probe exception="
                    + throwable.getClass().getSimpleName());
            return;
        }

        if (placement == null) {
            synchronized (LOCK) {
                if (generation == expectedGeneration && probeNoneCount < 4) {
                    probeNoneCount++;
                    diag("NATIVE_CANDIDATE probe=none count=" + probeNoneCount
                            + " reason=" + reason);
                }
            }
            return;
        }

        boolean replaced = placement.textView != previous
                || previous == null
                || !previous.isAttachedToWindow()
                || previous.getVisibility() != View.VISIBLE;
        if (replaced) {
            synchronized (LOCK) {
                if (generation != expectedGeneration) {
                    return;
                }
                recaptureCount++;
                if (recaptureCount <= 6) {
                    diag("NATIVE_CANDIDATE recapture=" + recaptureCount
                            + " reason=" + reason
                            + " old=" + viewIdentity(previous)
                            + " new=" + viewIdentity(placement.textView));
                }
            }
            installPlacement(placement, display, acceptAction, true);
            return;
        }

        ensureDisplay(previous, display, "probe");
        updateHitboxBounds(placement);
    }

    private static void installPlacement(
            NativePlacement placement,
            String fullDisplay,
            Runnable acceptAction,
            boolean recapture) {
        TextView oldTextView;
        View oldHitbox;
        TextWatcher oldWatcher;
        CharSequence oldOriginal;
        String oldFullDisplay;
        synchronized (LOCK) {
            oldTextView = activeTextView.get();
            oldHitbox = activeHitbox.get();
            oldWatcher = activeWatcher;
            oldOriginal = originalText;
            oldFullDisplay = activeFullDisplay;
        }

        if (oldTextView == placement.textView && oldTextView != null) {
            synchronized (LOCK) {
                activeHost = new WeakReference<>(placement.host);
                activeFullDisplay = fullDisplay;
                activeAcceptAction = acceptAction;
            }
            bindClick(oldHitbox, acceptAction);
            ensureDisplay(oldTextView, fullDisplay,
                    recapture ? "recapture-same" : "refresh");
            updateHitboxBounds(placement);
            return;
        }

        if (oldTextView != null && oldWatcher != null) {
            try {
                oldTextView.removeTextChangedListener(oldWatcher);
            } catch (Throwable ignored) {
                // Optional UI only.
            }
        }
        removeView(oldHitbox);

        try {
            if (oldTextView != null
                    && oldTextView.isAttachedToWindow()
                    && isOurRenderedText(oldTextView, oldFullDisplay)) {
                setTextInternally(oldTextView, oldOriginal);
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }

        TextView candidate = placement.textView;
        CharSequence previousText = candidate.getText();
        View hitbox = createHitbox(placement, fullDisplay, acceptAction);
        TextWatcher watcher = createStickyWatcher(candidate);

        synchronized (LOCK) {
            activeHost = new WeakReference<>(placement.host);
            activeTextView = new WeakReference<>(candidate);
            activeHitbox = new WeakReference<>(hitbox);
            originalText = previousText;
            activeFullDisplay = fullDisplay;
            activeAcceptAction = acceptAction;
            activeWatcher = watcher;
        }

        try {
            candidate.addTextChangedListener(watcher);
        } catch (Throwable ignored) {
            // Recapture probes remain available even if TextWatcher cannot be attached.
        }
        ensureDisplay(candidate, fullDisplay, recapture ? "recapture" : "initial");

        String rendered = renderDisplay(candidate, fullDisplay);
        if (!recapture) {
            diag("NATIVE_CANDIDATE target=" + viewName(candidate)
                    + " identity=" + viewIdentity(candidate)
                    + " textLen=" + fullDisplay.length()
                    + " renderedLen=" + rendered.length()
                    + " bounds=" + placement.width + "x" + placement.height);
        }
    }

    private static View createHitbox(
            NativePlacement placement,
            String fullDisplay,
            Runnable acceptAction) {
        View hitbox = new View(placement.host.getContext());
        hitbox.setTag(HITBOX_TAG);
        hitbox.setBackgroundColor(Color.TRANSPARENT);
        hitbox.setClickable(true);
        hitbox.setFocusable(true);
        hitbox.setContentDescription("快捷文本：" + fullDisplay);
        bindClick(hitbox, acceptAction);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, placement.width),
                Math.max(1, placement.height));
        params.leftMargin = placement.left;
        params.topMargin = placement.top;
        placement.host.addView(hitbox, params);
        hitbox.bringToFront();
        return hitbox;
    }

    private static void updateHitboxBounds(NativePlacement placement) {
        View hitbox;
        synchronized (LOCK) {
            hitbox = activeHitbox.get();
        }
        if (hitbox == null || hitbox.getParent() != placement.host) {
            return;
        }
        try {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    Math.max(1, placement.width),
                    Math.max(1, placement.height));
            params.leftMargin = placement.left;
            params.topMargin = placement.top;
            hitbox.setLayoutParams(params);
            hitbox.bringToFront();
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static TextWatcher createStickyWatcher(TextView candidate) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String desired;
                synchronized (LOCK) {
                    if (internalTextChange || activeTextView.get() != candidate) {
                        return;
                    }
                    desired = activeFullDisplay;
                }
                if (desired == null || desired.isEmpty()) {
                    return;
                }
                String rendered = renderDisplay(candidate, desired);
                if (rendered.contentEquals(editable)) {
                    return;
                }
                try {
                    candidate.post(() -> {
                        String currentDesired;
                        synchronized (LOCK) {
                            if (activeTextView.get() != candidate) {
                                return;
                            }
                            currentDesired = activeFullDisplay;
                        }
                        if (currentDesired == null || currentDesired.isEmpty()) {
                            return;
                        }
                        String currentRendered = renderDisplay(candidate, currentDesired);
                        if (!currentRendered.contentEquals(candidate.getText())) {
                            synchronized (LOCK) {
                                reboundCount++;
                                if (reboundCount <= 4) {
                                    diag("NATIVE_CANDIDATE rebound=" + reboundCount);
                                }
                            }
                            ensureDisplay(candidate, currentDesired, "rebound");
                        }
                    });
                } catch (Throwable ignored) {
                    // Optional UI only.
                }
            }
        };
    }

    private static void ensureDisplay(TextView candidate, String fullDisplay, String reason) {
        if (candidate == null || fullDisplay == null || fullDisplay.isEmpty()) {
            return;
        }
        String rendered = renderDisplay(candidate, fullDisplay);
        if (rendered.isEmpty() || rendered.contentEquals(candidate.getText())) {
            return;
        }
        try {
            setTextInternally(candidate, rendered);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE setText=" + reason + " exception="
                    + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Fit the preview at the TextView's current native paint size instead of handing a long string
     * to Gboard's auto-size logic. This keeps long expansions readable and uses an ellipsis just as
     * a normal constrained suggestion would. The committed expansion is never truncated.
     */
    private static String renderDisplay(TextView candidate, String fullDisplay) {
        if (candidate == null || fullDisplay == null || fullDisplay.isEmpty()) {
            return "";
        }
        try {
            int available = candidate.getWidth()
                    - candidate.getCompoundPaddingLeft()
                    - candidate.getCompoundPaddingRight()
                    - dp(candidate, 8);
            if (available <= 0) {
                return fullDisplay;
            }
            // Leave a little breathing room so Gboard does not decide to auto-shrink because of
            // rounding differences between measureText() and layout.
            float targetWidth = Math.max(1f, available * 0.88f);
            if (candidate.getPaint().measureText(fullDisplay) <= targetWidth) {
                return fullDisplay;
            }
            String ellipsis = "…";
            float prefixWidth = targetWidth - candidate.getPaint().measureText(ellipsis);
            if (prefixWidth <= 0f) {
                return ellipsis;
            }
            int count = candidate.getPaint().breakText(
                    fullDisplay, true, prefixWidth, null);
            count = Math.max(1, Math.min(count, fullDisplay.length()));
            if (count < fullDisplay.length()
                    && count > 0
                    && Character.isHighSurrogate(fullDisplay.charAt(count - 1))) {
                count--;
            }
            if (count <= 0) {
                return ellipsis;
            }
            return fullDisplay.substring(0, count).trim() + ellipsis;
        } catch (Throwable ignored) {
            return fullDisplay;
        }
    }

    private static boolean isOurRenderedText(TextView view, String fullDisplay) {
        if (view == null || fullDisplay == null || fullDisplay.isEmpty()) {
            return false;
        }
        try {
            return renderDisplay(view, fullDisplay).contentEquals(view.getText());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setTextInternally(TextView view, CharSequence text) {
        synchronized (LOCK) {
            internalTextChange = true;
        }
        try {
            view.setText(text);
        } finally {
            synchronized (LOCK) {
                internalTextChange = false;
            }
        }
    }

    private static void bindClick(View view, Runnable acceptAction) {
        if (view == null) {
            return;
        }
        view.setOnClickListener(ignored -> {
            if (acceptAction != null) {
                acceptAction.run();
            }
        });
    }

    private static void hideNowIfCurrent(long expectedGeneration) {
        synchronized (LOCK) {
            if (generation != expectedGeneration) {
                return;
            }
        }
        detachActiveState(true);
    }

    /** Detach UI state without changing generation. */
    private static void detachActiveState(boolean restore) {
        FrameLayout host;
        TextView textView;
        View hitbox;
        CharSequence restoreText;
        String fullDisplay;
        TextWatcher watcher;
        synchronized (LOCK) {
            host = activeHost.get();
            textView = activeTextView.get();
            hitbox = activeHitbox.get();
            restoreText = originalText;
            fullDisplay = activeFullDisplay;
            watcher = activeWatcher;

            activeHost.clear();
            activeTextView.clear();
            activeHitbox.clear();
            originalText = "";
            activeFullDisplay = "";
            activeAcceptAction = null;
            activeWatcher = null;
            reboundCount = 0;
            recaptureCount = 0;
            probeNoneCount = 0;
        }

        if (textView != null && watcher != null) {
            try {
                textView.removeTextChangedListener(watcher);
            } catch (Throwable ignored) {
                // Optional UI only.
            }
        }

        removeView(hitbox);
        if (host != null) {
            removeTaggedChildren(host);
        }

        if (!restore) {
            return;
        }
        try {
            if (textView != null
                    && textView.isAttachedToWindow()
                    && isOurRenderedText(textView, fullDisplay)) {
                setTextInternally(textView, restoreText);
            }
        } catch (Throwable ignored) {
            // Restoring preview state must never affect normal typing.
        }
    }

    private static NativePlacement resolveNativePlacement(View anchor) {
        FrameLayout host = outermostFrameLayout(anchor);
        if (host == null || host.getWidth() <= 0 || host.getHeight() <= 0) {
            return null;
        }

        int[] hostLocation = new int[2];
        host.getLocationOnScreen(hostLocation);
        int hostTop = hostLocation[1];
        int hostBottom = hostTop + host.getHeight();

        int keyboardTop = findKeyboardPanelTop(
                host, host, hostTop, Integer.MAX_VALUE, 0);
        if (keyboardTop == Integer.MAX_VALUE) {
            keyboardTop = hostTop;
        }

        int bandTop = Math.max(hostTop, keyboardTop - dp(host, 64));
        int bandBottom = Math.min(hostBottom, keyboardTop + dp(host, 88));

        List<TextView> candidates = new ArrayList<>();
        collectCandidateTextViews(host, host, bandTop, bandBottom, 0, candidates);
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingInt(GboardTextExpansionCandidateView::screenTop)
                .thenComparingInt(GboardTextExpansionCandidateView::screenLeft));
        int rowTop = screenTop(candidates.get(0));
        TextView first = candidates.stream()
                .filter(view -> Math.abs(screenTop(view) - rowTop) <= dp(host, 18))
                .min(Comparator.comparingInt(GboardTextExpansionCandidateView::screenLeft))
                .orElse(candidates.get(0));

        View slot = resolveCandidateSlot(first, host);
        int[] slotLocation = new int[2];
        slot.getLocationOnScreen(slotLocation);
        int left = clamp(
                slotLocation[0] - hostLocation[0],
                0,
                Math.max(0, host.getWidth() - 1));
        int top = clamp(
                slotLocation[1] - hostLocation[1],
                0,
                Math.max(0, host.getHeight() - 1));
        int width = Math.min(
                Math.max(first.getWidth() + dp(host, 24), slot.getWidth()),
                host.getWidth() - left);
        int height = Math.min(
                Math.max(first.getHeight(), slot.getHeight()),
                host.getHeight() - top);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new NativePlacement(host, first, left, top, width, height);
    }

    private static void collectCandidateTextViews(
            FrameLayout host,
            View current,
            int bandTop,
            int bandBottom,
            int depth,
            List<TextView> out) {
        if (current == null
                || depth > 18
                || current.getVisibility() != View.VISIBLE
                || !current.isAttachedToWindow()) {
            return;
        }
        if (current instanceof TextView textView
                && isCandidateTextView(host, textView, bandTop, bandBottom)) {
            out.add(textView);
        }
        if (current instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                collectCandidateTextViews(
                        host,
                        group.getChildAt(index),
                        bandTop,
                        bandBottom,
                        depth + 1,
                        out);
            }
        }
    }

    private static boolean isCandidateTextView(
            FrameLayout host,
            TextView view,
            int bandTop,
            int bandBottom) {
        CharSequence value = view.getText();
        if (value == null) {
            return false;
        }
        String text = value.toString().trim();
        if (text.isEmpty()
                || text.length() > 80
                || view.getWidth() <= 0
                || view.getHeight() <= 0) {
            return false;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int centerY = location[1] + view.getHeight() / 2;
        if (centerY < bandTop || centerY > bandBottom) {
            return false;
        }
        if (view.getHeight() > dp(host, 72)
                || view.getWidth() > Math.round(host.getWidth() * 0.75f)) {
            return false;
        }
        float scaledDensity = host.getResources().getDisplayMetrics().scaledDensity;
        float sp = scaledDensity > 0f
                ? view.getTextSize() / scaledDensity
                : view.getTextSize();
        return sp >= 11f && sp <= 24f;
    }

    private static View resolveCandidateSlot(TextView textView, FrameLayout host) {
        View slot = textView;
        ViewParent parent = textView.getParent();
        int depth = 0;
        while (parent instanceof View view && view != host && depth++ < 4) {
            int width = view.getWidth();
            int height = view.getHeight();
            boolean plausible = width >= textView.getWidth()
                    && width <= Math.round(host.getWidth() * 0.55f)
                    && height >= textView.getHeight()
                    && height <= dp(host, 72);
            if (!plausible) {
                break;
            }
            slot = view;
            parent = view.getParent();
        }
        return slot;
    }

    private static FrameLayout outermostFrameLayout(View anchor) {
        if (anchor == null) {
            return null;
        }
        FrameLayout result = anchor instanceof FrameLayout
                ? (FrameLayout) anchor : null;
        ViewParent parent = anchor.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 24) {
            View current = (View) parent;
            if (current instanceof FrameLayout) {
                result = (FrameLayout) current;
            }
            parent = current.getParent();
        }
        return result;
    }

    private static int findKeyboardPanelTop(
            FrameLayout host,
            View current,
            int hostScreenTop,
            int bestScreenTop,
            int depth) {
        if (current == null
                || depth > 16
                || current.getVisibility() != View.VISIBLE
                || !current.isAttachedToWindow()) {
            return bestScreenTop;
        }
        if (current != host
                && current.getWidth() >= Math.round(host.getWidth() * 0.85f)
                && current.getHeight() >= dp(host, 160)) {
            int[] location = new int[2];
            current.getLocationOnScreen(location);
            int hostBottom = hostScreenTop + host.getHeight();
            int minimumTop = hostScreenTop + host.getHeight() / 3;
            if (location[1] >= minimumTop
                    && location[1] + current.getHeight()
                    >= hostBottom - dp(host, 48)) {
                bestScreenTop = Math.min(bestScreenTop, location[1]);
            }
        }
        if (current instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                bestScreenTop = findKeyboardPanelTop(
                        host,
                        group.getChildAt(index),
                        hostScreenTop,
                        bestScreenTop,
                        depth + 1);
            }
        }
        return bestScreenTop;
    }

    private static int screenLeft(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[0];
    }

    private static int screenTop(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return location[1];
    }

    private static void removeView(View view) {
        if (view == null) {
            return;
        }
        try {
            if (view.getParent() instanceof ViewGroup parent) {
                parent.removeView(view);
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static void removeTaggedChildren(FrameLayout host) {
        try {
            for (int index = host.getChildCount() - 1; index >= 0; index--) {
                View child = host.getChildAt(index);
                if (child != null && HITBOX_TAG.equals(child.getTag())) {
                    host.removeViewAt(index);
                }
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
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
        return view.getClass().getName();
    }

    private static String viewIdentity(View view) {
        return view == null
                ? "null"
                : viewName(view) + "@"
                + Integer.toHexString(System.identityHashCode(view));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(View view, int value) {
        float density = view != null
                ? view.getResources().getDisplayMetrics().density : 1f;
        return Math.max(1, Math.round(value * density));
    }

    private static void diag(String message) {
        try {
            GboardTextExpansionDiagnostics.record(message);
        } catch (Throwable ignored) {
            // Diagnostics cannot affect typing.
        }
    }

    private static final class NativePlacement {
        final FrameLayout host;
        final TextView textView;
        final int left;
        final int top;
        final int width;
        final int height;

        NativePlacement(
                FrameLayout host,
                TextView textView,
                int left,
                int top,
                int width,
                int height) {
            this.host = host;
            this.textView = textView;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }
}
