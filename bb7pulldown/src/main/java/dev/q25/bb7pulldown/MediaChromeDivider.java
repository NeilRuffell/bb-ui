package dev.q25.bb7pulldown;

import android.graphics.drawable.GradientDrawable;

/** Shared BBOS6 silver horizontal media-chrome divider. */
final class MediaChromeDivider {
    // Single source of truth for both the seekbar bottom divider and the
    // shuffle/repeat header top divider.
    static final int HEIGHT_DP = 1;

    private MediaChromeDivider() { }

    /** Shared vertical background for the expanded seekbar and media-mode header. */
    static GradientDrawable darkMediaBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xff222422, 0xff181818});
    }

    static GradientDrawable silverLine() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xffb5b6b5, 0xffffffff, 0xffb5b6b5});
    }
}
