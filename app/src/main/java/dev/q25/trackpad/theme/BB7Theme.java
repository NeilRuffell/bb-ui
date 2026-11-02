package dev.q25.trackpad.theme;

import android.graphics.drawable.GradientDrawable;

/**
 * Shared BBOS7 visual tokens for Settings, native notifications,
 * two-column shade, status selection and native Quickstep focus.
 *
 * Visuals only: never requests focus or changes Android navigation.
 */
public final class BB7Theme {
    private BB7Theme() {}

    public static final int BLUE_TOP = 0xff459fd9;
    public static final int BLUE_MIDDLE = 0xff3285c5;
    public static final int BLUE_BOTTOM = 0xff2368aa;
    public static final int BLUE_BORDER = 0xffb4e2fb;
    public static final int TITLE = 0xffffffff;
    public static final int SUMMARY = 0xffe7f2ff;
    public static final int CANVAS = 0xff10141a;
    public static final int PANE_LEFT = 0xff222a34;
    public static final int PANE_RIGHT = 0xff161c24;
    public static final int ROW = 0xff283442;
    public static final int ACTION = 0xff354354;

    public static int[] gradientColors() {
        return new int[] { BLUE_TOP, BLUE_MIDDLE, BLUE_BOTTOM };
    }

    public static GradientDrawable selected(float radiusPx, int borderPx) {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, gradientColors());
        background.setCornerRadius(radiusPx);
        if (borderPx > 0) background.setStroke(borderPx, BLUE_BORDER);
        return background;
    }
}
