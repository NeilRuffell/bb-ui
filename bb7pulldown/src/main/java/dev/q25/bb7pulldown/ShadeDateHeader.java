package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;

/**
 * Identical BBOS6-inspired date header used by both notification and QS shades.
 * The native status bar already owns the time, so this header displays only
 * today's date. TextClock handles midnight, date and timezone changes.
 */
final class ShadeDateHeader extends TextClock {
    private static final String DATE_FORMAT = "EEE, MMM d, yyyy";
    private static final int HEIGHT_DP = 49;
    private static final float TEXT_SIZE_SP = 22f;
    private final BB7Theme.Palette palette;
    // Initially false so construction always assigns our blue gradient, even
    // if the platform theme supplied a default TextView background.
    private boolean contentFocused;

    ShadeDateHeader(Context context) {
        super(context);
        palette = BB7Theme.palette(context);
        setFormat12Hour(DATE_FORMAT);
        setFormat24Hour(DATE_FORMAT);
        setTextSize(TEXT_SIZE_SP);
        setTypeface(null, Typeface.NORMAL);
        setTextColor(Color.WHITE);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setPadding(dp(8), 0, dp(8), 0);
        setShadeContentFocused(true);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(null);
    }

    /**
     * Blue when focus is within the shade's content; neutral graphite/steel
     * while navigating the native status bar. Both pages use this one view.
     */
    void setShadeContentFocused(boolean focused) {
        if (contentFocused == focused && getBackground() != null) return;
        contentFocused = focused;
        final int[] colors;
        if (focused) {
            colors = BB7Theme.gradientColors();
        } else if (palette == BB7Theme.Palette.DARK) {
            colors = new int[]{0xff526477, 0xff3b4e60, 0xff263747};
        } else {
            colors = new int[]{0xfff9fbfe, 0xffe5ecf4, 0xffc8d4e1};
        }
        setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, colors));
        setTextColor(focused || palette == BB7Theme.Palette.DARK
                ? Color.WHITE : palette.title);
    }

    static LinearLayout.LayoutParams layoutParams(Context context) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (HEIGHT_DP * context.getResources().getDisplayMetrics().density + 0.5f));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
