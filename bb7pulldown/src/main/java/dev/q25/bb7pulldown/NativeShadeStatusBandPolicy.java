package dev.q25.bb7pulldown;

import android.graphics.Rect;

/**
 * Pixel-space native shade root clipping policy. Keep the original SystemUI
 * status-bar band uncovered while BB UI is mounted in the native shade.
 */
final class NativeShadeStatusBandPolicy {
    private NativeShadeStatusBandPolicy() {}

    static Rect clipBounds(int windowWidth, int windowHeight, int statusBarHeight) {
        int right = Math.max(0, windowWidth);
        int bottom = Math.max(0, windowHeight);
        int top = Math.max(0, Math.min(statusBarHeight, bottom));
        return new Rect(0, top, right, bottom);
    }
}
