package dev.q25.trackpad.theme;

import android.graphics.drawable.GradientDrawable;
import android.content.Context;
import android.content.res.Configuration;

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

    /** Resolve the system's active night mode, including automatic schedules. */
    public static Palette palette(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return dark ? Palette.DARK : Palette.LIGHT;
    }

    public static final class Palette {
        public static final Palette DARK = new Palette(
                0xff10141a, 0xff222a34, 0xff161c24, 0xff1c2530,
                0xff283442, 0xff354354, 0xffd7e7f4, 0xffffffff,
                0xffd5dde6, 0xff99aab8, 0xffe0e9f4, 0xff476f8f);
        public static final Palette LIGHT = new Palette(
                0xfff4f6f8, 0xffe5eaf0, 0xfff9fafc, 0xffedf1f5,
                0xffffffff, 0xffe4eaf1, 0xff263747, 0xff182838,
                0xff435568, 0xff66798a, 0xff263747, 0xff9eafbe);

        public final int canvas, left, right, heading, card, action;
        public final int headingText, title, body, muted, actionText, divider;

        private Palette(int canvas, int left, int right, int heading,
                int card, int action, int headingText, int title,
                int body, int muted, int actionText, int divider) {
            this.canvas = canvas;
            this.left = left;
            this.right = right;
            this.heading = heading;
            this.card = card;
            this.action = action;
            this.headingText = headingText;
            this.title = title;
            this.body = body;
            this.muted = muted;
            this.actionText = actionText;
            this.divider = divider;
        }
    }

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
