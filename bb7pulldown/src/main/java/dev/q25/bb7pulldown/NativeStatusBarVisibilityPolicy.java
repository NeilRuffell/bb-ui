package dev.q25.bb7pulldown;

/** Shared, pure native home-status-bar decisions for the BB shade. */
final class NativeStatusBarVisibilityPolicy {
    private NativeStatusBarVisibilityPolicy() {}

    /**
     * Keep Android's home status-bar contents eligible while BB UI is
     * mounted. When BB is absent, the original native decision is unchanged.
     * Other native clock/icon user preferences remain independently applied.
     */
    static boolean keepHomeStatusBarAllowed(boolean nativeAllowed, boolean bbMounted) {
        return nativeAllowed || bbMounted;
    }
}
