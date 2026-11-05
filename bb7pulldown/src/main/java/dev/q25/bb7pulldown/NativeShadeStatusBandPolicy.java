package dev.q25.bb7pulldown;

/** Pure pixel-space clipping of native shade drawing below the status bar. */
final class NativeShadeStatusBandPolicy {
    private NativeShadeStatusBandPolicy() {}

    /** [left, top, right, bottom] suitable for constructing Android Rect. */
    static int[] clipBounds(int windowWidth, int windowHeight, int statusBarHeight) {
        int right = Math.max(0, windowWidth);
        int bottom = Math.max(0, windowHeight);
        int top = Math.max(0, Math.min(statusBarHeight, bottom));
        return new int[] {0, top, right, bottom};
    }
}
