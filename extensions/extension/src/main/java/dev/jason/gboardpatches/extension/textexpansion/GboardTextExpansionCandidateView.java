package dev.jason.gboardpatches.extension.textexpansion;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Optional UI-only preview for an exact Text Expansion shortcut match.
 *
 * The stable dev.17 expansion path remains authoritative. This view is created only after the
 * raw token already exactly matches a saved shortcut, and it occupies only the visual first
 * candidate slot. Pressing Space still uses the normal Text Expansion runtime even if this
 * preview cannot be placed.
 */
final class GboardTextExpansionCandidateView {
    private static final String VIEW_TAG =
            "gboard-patches-text-expansion-first-candidate-v2";
    private static final int HEIGHT_DP = 44;
    private static final Object LOCK = new Object();

    private static WeakReference<FrameLayout> activeHost = new WeakReference<>(null);
    private static WeakReference<TextView> activeView = new WeakReference<>(null);
    private static String activeText = "";

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
            // Never mutate Gboard's view hierarchy inside the pointer dispatch itself.
            anchor.post(() -> showNow(anchor, display, acceptAction));
        } catch (Throwable ignored) {
            // Candidate preview is optional and must never affect typing.
        }
    }

    static void hide() {
        TextView view;
        synchronized (LOCK) {
            view = activeView.get();
            activeView.clear();
            activeHost.clear();
            activeText = "";
        }
        if (view == null) {
            return;
        }
        try {
            view.post(() -> removeView(view));
        } catch (Throwable ignored) {
            removeView(view);
        }
    }

    private static void showNow(View anchor, String display, Runnable acceptAction) {
        try {
            if (!anchor.isAttachedToWindow()) {
                return;
            }
            Placement placement = resolvePlacement(anchor);
            if (placement == null) {
                return;
            }

            synchronized (LOCK) {
                FrameLayout currentHost = activeHost.get();
                TextView current = activeView.get();
                if (currentHost == placement.host
                        && current != null
                        && current.getParent() == currentHost
                        && activeText.equals(display)) {
                    bindClick(current, acceptAction);
                    return;
                }
            }

            hideNow();

            Context context = placement.host.getContext();
            Palette palette = Palette.from(context);
            TextView candidate = new TextView(context);
            candidate.setTag(VIEW_TAG);
            candidate.setGravity(Gravity.CENTER);
            candidate.setSingleLine(true);
            candidate.setEllipsize(TextUtils.TruncateAt.END);
            candidate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            candidate.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            candidate.setTextColor(palette.text);
            candidate.setText(display);
            candidate.setPadding(dp(context, 12), 0, dp(context, 12), 0);
            candidate.setBackground(rounded(palette.surface, dp(context, 18)));
            candidate.setElevation(dp(context, 24));
            candidate.setClickable(true);
            candidate.setFocusable(true);
            candidate.setContentDescription("快捷文本：" + display);
            bindClick(candidate, acceptAction);

            int available = Math.max(dp(context, 120), placement.host.getWidth() - dp(context, 24));
            int width = Math.max(dp(context, 120), Math.min(dp(context, 300), available / 3));
            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(width, dp(context, HEIGHT_DP));
            params.gravity = Gravity.TOP | Gravity.START;
            params.leftMargin = dp(context, 8);
            params.topMargin = placement.topMargin;

            placement.host.addView(candidate, params);
            candidate.bringToFront();
            synchronized (LOCK) {
                activeHost = new WeakReference<>(placement.host);
                activeView = new WeakReference<>(candidate);
                activeText = display;
            }
        } catch (Throwable ignored) {
            // UI preview failure must leave stock Gboard fully usable.
        }
    }

    private static void bindClick(TextView view, Runnable acceptAction) {
        view.setOnClickListener(ignored -> {
            if (acceptAction != null) {
                acceptAction.run();
            }
        });
    }

    private static void hideNow() {
        TextView view;
        FrameLayout host;
        synchronized (LOCK) {
            view = activeView.get();
            host = activeHost.get();
            activeView.clear();
            activeHost.clear();
            activeText = "";
        }
        if (view != null) {
            removeView(view);
        }
        if (host != null) {
            removeTaggedChildren(host);
        }
    }

    private static void removeView(TextView view) {
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
                if (child != null && VIEW_TAG.equals(child.getTag())) {
                    host.removeViewAt(index);
                }
            }
        } catch (Throwable ignored) {
            // Optional UI only.
        }
    }

    private static Placement resolvePlacement(View anchor) {
        FrameLayout host = outermostFrameLayout(anchor);
        if (host == null || host.getWidth() <= 0 || host.getHeight() <= 0) {
            return null;
        }

        int[] hostLocation = new int[2];
        host.getLocationOnScreen(hostLocation);
        int keyboardTop = findKeyboardPanelTop(
                host, host, hostLocation[1], Integer.MAX_VALUE, 0);
        int topMargin = keyboardTop == Integer.MAX_VALUE
                ? dp(host.getContext(), 4)
                : Math.max(0, keyboardTop - hostLocation[1] + dp(host.getContext(), 4));
        return new Placement(host, topMargin);
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
                && current.getHeight() >= dp(host.getContext(), 160)) {
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

    private static String singleLine(String value) {
        return value == null ? "" : value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim();
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radiusPx);
        return background;
    }

    private static int dp(Context context, int value) {
        float density = context != null
                ? context.getResources().getDisplayMetrics().density : 1f;
        return Math.max(1, Math.round(value * density));
    }

    private static final class Placement {
        final FrameLayout host;
        final int topMargin;

        Placement(FrameLayout host, int topMargin) {
            this.host = host;
            this.topMargin = topMargin;
        }
    }

    private static final class Palette {
        final int surface;
        final int text;

        Palette(int surface, int text) {
            this.surface = surface;
            this.text = text;
        }

        static Palette from(Context context) {
            boolean dark = context != null
                    && (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            return dark
                    ? new Palette(0xff303134, Color.WHITE)
                    : new Palette(0xfff1f3f4, 0xff202124);
        }
    }
}
