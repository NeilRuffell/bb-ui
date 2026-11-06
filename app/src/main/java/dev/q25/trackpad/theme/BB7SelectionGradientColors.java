package dev.q25.trackpad.theme;

/**
 * Shared BBUI focus-gradient colors. This class has no Android dependencies,
 * so the exact sampled BBOS6 palette can be verified with JVM unit tests.
 */
public final class BB7SelectionGradientColors {
    public static final int DARK_TOP = 0xff459fd9;
    public static final int DARK_MIDDLE = 0xff3285c5;
    public static final int DARK_BOTTOM = 0xff2368aa;

    private static final int[] DARK = {
            DARK_TOP, DARK_MIDDLE, DARK_BOTTOM
    };

    /**
     * Eleven exact RGB shades sampled from the user-supplied BBOS6 18x60
     * selection-gradient image, from top to bottom. Android interpolates
     * these stops across the selected View's actual height.
     */
    private static final int[] LIGHT = {
            0xff088eef, // #088EEF
            0xff088aef, // #088AEF
            0xff0886ef, // #0886EF
            0xff0882ef, // #0882EF
            0xff087de7, // #087DE7
            0xff0879e7, // #0879E7
            0xff1075e7, // #1075E7
            0xff1071e7, // #1071E7
            0xff106dde, // #106DDE
            0xff1069de, // #1069DE
            0xff1065de  // #1065DE
    };

    private BB7SelectionGradientColors() {}

    /** Caller owns the returned array; mutations cannot alter theme tokens. */
    public static int[] colors(boolean darkMode) {
        return (darkMode ? DARK : LIGHT).clone();
    }
}
