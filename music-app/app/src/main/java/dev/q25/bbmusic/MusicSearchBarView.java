package dev.q25.bbmusic;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * Pure visual port of the BBOS6 Music search chrome.
 *
 * The native EditText supplied by MusicActivity remains responsible for
 * editing and search. This view only owns the fixed-height gradient panel,
 * its upper rule and the rounded inset text-field background.
 */
final class MusicSearchBarView extends FrameLayout {
    private static final int HEIGHT_DP = 42;
    private static final int TOP_BORDER = 0xff5a5d63;
    private static final int TOP_GRADIENT = 0xff4b4e53;
    private static final int BOTTOM_GRADIENT = 0xff292c31;

    MusicSearchBarView(Context context, EditText input) {
        super(context);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClipChildren(false);

        // Screenshot: 1dp #5A5D63 rule at the very top, followed by the
        // #4B4E53 -> #292C31 charcoal panel gradient.
        GradientDrawable panel = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {TOP_GRADIENT, BOTTOM_GRADIENT});
        panel.setDither(true);
        setBackground(panel);
        setPadding(dp(6), dp(6), dp(6), dp(5));

        // Screenshot: an approximately 31dp-tall, near-white field with
        // softly rounded corners, a dark thin outer rim and an inset
        // gray-to-white highlight/shadow that fades near the field's top.
        GradientDrawable rim = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xff4e555b, 0xff6b7074});
        rim.setCornerRadius(dp(5));

        GradientDrawable inset = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {
                        0xffd4d5d6, 0xffe7e7e7, 0xfff7f7f7, Color.WHITE
                });
        inset.setColors(new int[] {
                0xffd4d5d6, 0xffe7e7e7, 0xfff7f7f7, Color.WHITE
        }, new float[] {0f, 0.10f, 0.22f, 1f});
        inset.setCornerRadius(dp(4));

        LayerDrawable field = new LayerDrawable(
                new android.graphics.drawable.Drawable[] {rim, inset});
        field.setLayerInset(1, dp(1), dp(1), dp(1), dp(1));

        input.setBackground(field);
        input.setTextColor(0xff24272b);
        input.setHintTextColor(0xffb6b7bb);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setMinimumHeight(0);
        input.setMinHeight(0);
        addView(input, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        View topRule = new View(context);
        topRule.setBackgroundColor(TOP_BORDER);
        FrameLayout.LayoutParams ruleLayout = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP);
        // The inset field and its text are unaffected by the overlay rule.
        addView(topRule, ruleLayout);
    }

    static int heightDp() {
        return HEIGHT_DP;
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
