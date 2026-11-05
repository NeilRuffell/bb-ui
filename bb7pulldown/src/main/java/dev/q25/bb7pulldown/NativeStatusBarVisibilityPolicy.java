package dev.q25.bb7pulldown;

/** Let the native status bar display while BB UI replaces the shade surface. */
final class NativeStatusBarVisibilityPolicy {
    private NativeStatusBarVisibilityPolicy() {}

    /** In legacy mode false means the expanded shade does not hide the bar. */
    static boolean isStockShadeVisible(boolean nativeShadeVisible, boolean bbMounted) {
        return nativeShadeVisible && !bbMounted;
    }
}
