package dev.q25.bbmusic;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;

/** Shared BBOS6 silver horizontal media-chrome divider. */
final class MediaChromeDivider {
    // Single source of truth for both the seekbar bottom divider and the
    // shuffle/repeat header top divider.
    static final int HEIGHT_DP = 1;
    static final int DARK_RULE_HEIGHT_DP = 2;

    private MediaChromeDivider() { }

    /** Shared vertical background for the expanded seekbar and media-mode header. */
    static GradientDrawable darkMediaBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xff222422, 0xff181818});
    }

    /** Matches the 2dp dark top rule of the seekbar area. */
    static ColorDrawable darkRule() {
        return new ColorDrawable(0xff393c3c);
    }

    static GradientDrawable silverLine() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xffb5b6b5, 0xffffffff, 0xffb5b6b5});
    }
}
