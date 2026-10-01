package dev.jason.gboardpatches.extension.textexpansion;

import android.graphics.Color;
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
 * dev.20 first-candidate preview.
 *
 * Instead of drawing a styled floating TextView over Gboard, this implementation temporarily
 * reuses the text of Gboard's own first visible candidate slot. A transparent click hitbox is
 * placed over the native slot so tapping still accepts the Text Expansion entry without
 * replacing Gboard's original click listener. When the shortcut stops matching, the original
 * text is restored only if Gboard has not already rebound that view itself.
 *
 * If a safe native candidate target cannot be resolved, no preview is shown. Space/Enter/
 * punctuation expansion remains fully independent and continues to work as before.
 */
final class GboardTextExpansionCandidateView {
    private static final String HITBOX_TAG =
            "gboard-patches-text-expansion-native-candidate-hitbox-v1";
    private static final Object LOCK = new Object();

    private static WeakReference<FrameLayout> activeHost = new WeakReference<>(null);
    private static WeakReference<TextView> activeTextView = new WeakReference<>(null);
    private static WeakReference<View> activeHitbox = new WeakReference<>(null);
    private static CharSequence originalText = "";
    private static String activeDisplay = "";

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
            // Wait until Gboard has completed the current candidate/layout pass.
            anchor.post(() -> showNow(anchor, display, acceptAction));
        } catch (Throwable ignored) {
            // Candidate preview is optional and must never affect typing.
        }
    }

    static void hide() {
        View callbackView;
        synchronized (LOCK) {
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

            synchronized (LOCK) {
                TextView current = activeTextView.get();
                View hitbox = activeHitbox.get();
                if (current == placement.textView
                        && current.isAttachedToWindow()
                        && activeDisplay.equals(display)) {
                    if (!display.contentEquals(current.getText())) {
                        current.setText(display);
                    }
                    bindClick(hitbox, acceptAction);
                    return;
                }
            }

            hideNow();

            TextView candidate = placement.textView;
            CharSequence previous = candidate.getText();
            candidate.setText(display);

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

            synchronized (LOCK) {
                activeHost = new WeakReference<>(placement.host);
                activeTextView = new WeakReference<>(candidate);
                activeHitbox = new WeakReference<>(hitbox);
                originalText = previous;
                activeDisplay = display;
            }
            diag("NATIVE_CANDIDATE target=" + viewName(candidate)
                    + " textLen=" + display.length()
                    + " bounds=" + placement.width + "x" + placement.height);
        } catch (Throwable throwable) {
            diag("NATIVE_CANDIDATE exception=" + throwable.getClass().getSimpleName());
            hideNow();
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
        synchronized (LOCK) {
            host = activeHost.get();
            textView = activeTextView.get();
            hitbox = activeHitbox.get();
            restoreText = originalText;
            displayed = activeDisplay;

            activeHost.clear();
            activeTextView.clear();
            activeHitbox.clear();
            originalText = "";
            activeDisplay = "";
        }

        removeView(hitbox);
        if (host != null) {
            removeTaggedChildren(host);
        }

        try {
            // Do not overwrite a newer candidate that Gboard may already have rebound.
            if (textView != null
                    && textView.isAttachedToWindow()
                    && displayed.contentEquals(textView.getText())) {
                textView.setText(restoreText);
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

        // Gboard 18.0.3 keeps the suggestion strip in the top band of the keyboard panel.
        // Search slightly above and below the detected panel top because different layouts
        // include the strip in different containers.
        int bandTop = Math.max(hostTop, keyboardTop - dp(host, 64));
        int bandBottom = Math.min(hostBottom, keyboardTop + dp(host, 88));

        List<TextView> candidates = new ArrayList<>();
        collectCandidateTextViews(host, host, bandTop, bandBottom, 0, candidates);
        if (candidates.isEmpty()) {
            return null;
        }

        // Prefer the top-most candidate row, then the left-most text in that row. This matches
        // the first visual candidate slot used by the existing dev.18 overlay placement.
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
        if (current instanceof TextView textView && isCandidateTextView(host, textView, bandTop, bandBottom)) {
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
        if (view.getHeight() > dp(host, 72) || view.getWidth() > Math.round(host.getWidth() * 0.75f)) {
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
                    >= hostBottom - dp(host, 48)) {
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
