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
 * dev.22 native first-candidate preview.
 *
 * Device diagnostics from dev.21 showed an exact native target, but no TextWatcher rebound events
 * even though the replacement text only flashed briefly. That means Gboard is replacing/recycling
 * the candidate View object rather than simply calling setText() on the same TextView.
 *
 * This implementation keeps the dev.21 same-view TextWatcher and adds short-lived recapture probes
 * after an exact shortcut match. If Gboard swaps the first candidate View, the new native slot is
 * detected and rebound before/around the following frame. No custom visual candidate is drawn.
 * Space/Enter/punctuation expansion remains fully independent and unchanged.
 */
final class GboardTextExpansionCandidateView {
    private static final String HITBOX_TAG =
            "gboard-patches-text-expansion-native-candidate-hitbox-v3";
    private static final Object LOCK = new Object();
    private static final long[] RECAPTURE_DELAYS_MS = {
            24L, 48L, 80L, 128L, 192L, 288L, 416L, 600L, 850L, 1200L, 1800L
    };

    private static WeakReference<FrameLayout> activeHost = new WeakReference<>(null);
    private static WeakReference<TextView> activeTextView = new WeakReference<>(null);
    private static WeakReference<View> activeHitbox = new WeakReference<>(null);
    private static CharSequence originalText = "";
    private static String activeDisplay = "";
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
        try {
            anchor.post(() -> showNow(anchor, display, acceptAction));
        } catch (Throwable ignored) {
            // Candidate preview is optional and must never affect typing.
        }
    }

    static void hide() {
        View callbackView;
        synchronized (LOCK) {
            generation++;
            callbackView = activeHitbox.get();
            if (callbackView == null) {
                callbackView = activeTextView.get();
            }
        }
        if (callbackView == null) {
            hideNow();
            return;
        }
        try {
            callbackView.post(GboardTextExpansionCandidateView::hideNow);
        } catch (Throwable ignored) {
            hideNow();
        }
    }

    private static void showNow(View anchor, String display, Runnable acceptAction) {
        try {
            if (!anchor.isAttachedToWindow()) {
                return;
            }
            NativePlacement placement = resolveNativePlacement(anchor);
            if (placement == null) {
                diag("NATIVE_CANDIDATE target=none");
                hideNow();
                return;
            }

            long currentGeneration;
            synchronized (LOCK) {
                generation++;
                currentGeneration = generation;
                activeDisplay = display;
                activeAcceptAction = acceptAction;
                reboundCount = 0;
                recaptureCount = 0;
                probeNoneCount = 0;
            }

            installPlacement(placement, display, acceptAction, false);
            scheduleRecaptureProbes(placement.host, currentGeneration);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE exception=" + throwable.getClass().getSimpleName());
            hideNow();
        }
    }

    private static void scheduleRecaptureProbes(FrameLayout host, long expectedGeneration) {
        try {
            host.postOnAnimation(() -> probeForReplacement(host, expectedGeneration, "frame"));
            for (long delay : RECAPTURE_DELAYS_MS) {
                host.postDelayed(
                        () -> probeForReplacement(host, expectedGeneration, "delay"), delay);
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static void probeForReplacement(
            FrameLayout host, long expectedGeneration, String reason) {
        String display;
        Runnable acceptAction;
        TextView previous;
        synchronized (LOCK) {
            if (generation != expectedGeneration
                    || activeHost.get() != host
                    || activeDisplay.isEmpty()) {
                return;
            }
            display = activeDisplay;
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
                if (generation == expectedGeneration && probeNoneCount < 3) {
                    probeNoneCount++;
                    diag("NATIVE_CANDIDATE probe=none count=" + probeNoneCount);
                }
            }
            return;
        }

        if (placement.textView != previous
                || previous == null
                || !previous.isAttachedToWindow()
                || previous.getVisibility() != View.VISIBLE) {
            synchronized (LOCK) {
                if (generation != expectedGeneration) {
                    return;
                }
                recaptureCount++;
                if (recaptureCount <= 5) {
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
            String display,
            Runnable acceptAction,
            boolean recapture) {
        TextView oldTextView;
        View oldHitbox;
        TextWatcher oldWatcher;
        CharSequence oldOriginal;
        String oldDisplay;
        synchronized (LOCK) {
            oldTextView = activeTextView.get();
            oldHitbox = activeHitbox.get();
            oldWatcher = activeWatcher;
            oldOriginal = originalText;
            oldDisplay = activeDisplay;
        }

        if (oldTextView == placement.textView && oldTextView != null) {
            synchronized (LOCK) {
                activeHost = new WeakReference<>(placement.host);
                activeDisplay = display;
                activeAcceptAction = acceptAction;
            }
            bindClick(oldHitbox, acceptAction);
            ensureDisplay(oldTextView, display, recapture ? "recapture-same" : "refresh");
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

        // If the old native View still exists somewhere in the hierarchy, restore its previous
        // text before abandoning it. A detached/recycled View is left alone.
        try {
            if (oldTextView != null
                    && oldTextView.isAttachedToWindow()
                    && oldDisplay != null
                    && oldDisplay.contentEquals(oldTextView.getText())) {
                setTextInternally(oldTextView, oldOriginal);
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }

        TextView candidate = placement.textView;
        CharSequence previousText = candidate.getText();
        View hitbox = createHitbox(placement, display, acceptAction);
        TextWatcher watcher = createStickyWatcher(candidate);

        synchronized (LOCK) {
            activeHost = new WeakReference<>(placement.host);
            activeTextView = new WeakReference<>(candidate);
            activeHitbox = new WeakReference<>(hitbox);
            originalText = previousText;
            activeDisplay = display;
            activeAcceptAction = acceptAction;
            activeWatcher = watcher;
        }

        try {
            candidate.addTextChangedListener(watcher);
        } catch (Throwable ignored) {
            // The recapture probes remain available even if TextWatcher cannot be attached.
        }
        ensureDisplay(candidate, display, recapture ? "recapture" : "initial");

        if (!recapture) {
            diag("NATIVE_CANDIDATE target=" + viewName(candidate)
                    + " identity=" + viewIdentity(candidate)
                    + " textLen=" + display.length()
                    + " bounds=" + placement.width + "x" + placement.height);
        }
    }

    private static View createHitbox(
            NativePlacement placement, String display, Runnable acceptAction) {
        View hitbox = new View(placement.host.getContext());
        hitbox.setTag(HITBOX_TAG);
        hitbox.setBackgroundColor(Color.TRANSPARENT);
        hitbox.setClickable(true);
        hitbox.setFocusable(true);
        hitbox.setContentDescription("快捷文本：" + display);
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
                    desired = activeDisplay;
                }
                if (desired == null || desired.isEmpty() || desired.contentEquals(editable)) {
                    return;
                }
                try {
                    candidate.post(() -> {
                        String currentDesired;
                        synchronized (LOCK) {
                            if (activeTextView.get() != candidate) {
                                return;
                            }
                            currentDesired = activeDisplay;
                        }
                        if (currentDesired == null || currentDesired.isEmpty()) {
                            return;
                        }
                        if (!currentDesired.contentEquals(candidate.getText())) {
                            synchronized (LOCK) {
                                reboundCount++;
                                if (reboundCount <= 3) {
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

    private static void ensureDisplay(TextView candidate, String display, String reason) {
        if (candidate == null || display == null || display.isEmpty()
                || display.contentEquals(candidate.getText())) {
            return;
        }
        try {
            setTextInternally(candidate, display);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE setText=" + reason + " exception="
                    + throwable.getClass().getSimpleName());
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

    private static void hideNow() {
        FrameLayout host;
        TextView textView;
        View hitbox;
        CharSequence restoreText;
        String displayed;
        TextWatcher watcher;
        synchronized (LOCK) {
            generation++;
            host = activeHost.get();
            textView = activeTextView.get();
            hitbox = activeHitbox.get();
            restoreText = originalText;
            displayed = activeDisplay;
            watcher = activeWatcher;

            activeHost.clear();
            activeTextView.clear();
            activeHitbox.clear();
            originalText = "";
            activeDisplay = "";
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

        try {
            if (textView != null
                    && textView.isAttachedToWindow()
                    && displayed != null
                    && displayed.contentEquals(textView.getText())) {
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
        int left = clamp(slotLocation[0] - hostLocation[0], 0, Math.max(0, host.getWidth() - 1));
        int top = clamp(slotLocation[1] - hostLocation[1], 0, Math.max(0, host.getHeight() - 1));
        int width = Math.min(Math.max(first.getWidth() + dp(host, 24), slot.getWidth()),
                host.getWidth() - left);
        int height = Math.min(Math.max(first.getHeight(), slot.getHeight()),
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
        if (current == null || depth > 18 || current.getVisibility() != View.VISIBLE
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
                        host, group.getChildAt(index), bandTop, bandBottom, depth + 1, out);
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
        if (text.isEmpty() || text.length() > 80 || view.getWidth() <= 0 || view.getHeight() <= 0) {
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
        float sp = scaledDensity > 0f ? view.getTextSize() / scaledDensity : view.getTextSize();
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
        FrameLayout result = anchor instanceof FrameLayout ? (FrameLayout) anchor : null;
        ViewParent parent = anchor == null ? null : anchor.getParent();
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

    private static int findKeyboardPanelTop(FrameLayout host, View current,
            int hostScreenTop, int bestScreenTop, int depth) {
        if (current == null || depth > 16 || current.getVisibility() != View.VISIBLE
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
                    >= hostBottom - dp(host.getContext(), 48)) {
                bestScreenTop = Math.min(bestScreenTop, location[1]);
            }
        }
        if (current instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                bestScreenTop = findKeyboardPanelTop(
                        host, group.getChildAt(index), hostScreenTop,
                        bestScreenTop, depth + 1);
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
        return view == null ? "null"
                : viewName(view) + "@" + Integer.toHexString(System.identityHashCode(view));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(View view, int value) {
        float density = view != null
                ? view.getResources().getDisplayMetrics().density : 1f;
        return Math.max(1, Math.round(value * density));
    }

    private static int dp(android.content.Context context, int value) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density : 1f;
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

        NativePlacement(FrameLayout host, TextView textView,
                int left, int top, int width, int height) {
            this.host = host;
            this.textView = textView;
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }
    }
}
