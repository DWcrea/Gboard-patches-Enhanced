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

/**
 * Draws the active Text Expansion value over the first visible candidate slot.
 *
 * This intentionally stays UI-only: the real replacement still goes through
 * {@link GboardTextExpansionRuntime}, so hiding or failing to place the preview never affects
 * normal typing.
 */
final class GboardTextExpansionCandidateView {
    private static final String VIEW_TAG = "gboard-patches-text-expansion-first-candidate";
    private static final int HEIGHT_DP = 44;

    private GboardTextExpansionCandidateView() {
    }

    static Placement resolvePlacement(View anchor) {
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

    static Handle show(Placement placement, String expansion, Runnable acceptAction) {
        if (placement == null || placement.host == null) {
            return null;
        }
        FrameLayout host = placement.host;
        Context context = host.getContext();
        Palette palette = Palette.from(context);

        removeFrom(host);

        TextView candidate = new TextView(context);
        candidate.setTag(VIEW_TAG);
        candidate.setGravity(Gravity.CENTER);
        candidate.setSingleLine(true);
        candidate.setEllipsize(TextUtils.TruncateAt.END);
        candidate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        candidate.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        candidate.setTextColor(palette.text);
        candidate.setText(singleLine(expansion));
        candidate.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        candidate.setBackground(rounded(palette.surface, dp(context, 18)));
        candidate.setElevation(dp(context, 24));
        candidate.setClickable(true);
        candidate.setFocusable(true);
        candidate.setContentDescription("快捷文本：" + singleLine(expansion));
        candidate.setOnClickListener(ignored -> {
            if (acceptAction != null) {
                acceptAction.run();
            }
        });

        int available = Math.max(dp(context, 120), host.getWidth() - dp(context, 24));
        int width = Math.max(
                dp(context, 120),
                Math.min(dp(context, 280), available / 3));
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(width, dp(context, HEIGHT_DP));
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = dp(context, 8);
        params.topMargin = placement.topMargin;

        host.addView(candidate, params);
        candidate.bringToFront();
        return new Handle(host, candidate, singleLine(expansion));
    }

    static void removeFrom(FrameLayout host) {
        if (host == null) {
            return;
        }
        for (int index = host.getChildCount() - 1; index >= 0; index--) {
            View child = host.getChildAt(index);
            if (child != null && VIEW_TAG.equals(child.getTag())) {
                host.removeViewAt(index);
            }
        }
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
        if (current instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) current;
            for (int index = 0; index < group.getChildCount(); index++) {
                bestScreenTop = findKeyboardPanelTop(
                        host, group.getChildAt(index), hostScreenTop, bestScreenTop, depth + 1);
            }
        }
        return bestScreenTop;
    }

    private static String singleLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ')
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

    static final class Placement {
        final FrameLayout host;
        final int topMargin;

        Placement(FrameLayout host, int topMargin) {
            this.host = host;
            this.topMargin = topMargin;
        }
    }

    static final class Handle {
        private final FrameLayout host;
        private final TextView candidate;
        private final String displayedText;

        Handle(FrameLayout host, TextView candidate, String displayedText) {
            this.host = host;
            this.candidate = candidate;
            this.displayedText = displayedText;
        }

        boolean matches(Placement placement, String text) {
            return placement != null
                    && host == placement.host
                    && candidate.getParent() == host
                    && displayedText.equals(singleLine(text));
        }

        void close() {
            candidate.post(() -> {
                try {
                    if (candidate.getParent() instanceof ViewGroup) {
                        ((ViewGroup) candidate.getParent()).removeView(candidate);
                    }
                } catch (Throwable ignored) {
                    // Candidate preview is optional UI and must not affect typing.
                }
            });
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
