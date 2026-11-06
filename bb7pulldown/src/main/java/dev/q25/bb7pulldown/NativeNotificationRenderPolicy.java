package dev.q25.bb7pulldown;

/** Exact, pure gating policy for native stock notification rendering. */
final class NativeNotificationRenderPolicy {
    private NativeNotificationRenderPolicy() {}

    /**
     * We may skip stock card drawing ONLY when the BB host owns the same
     * window as the native stack, not on keyguard or in a different window.
     * The integration has already checked whether the feature is enabled.
     */
    static boolean shouldSuppress(boolean bbHostMounted, boolean sameNativeWindow) {
        return shouldSuppress(bbHostMounted, sameNativeWindow, false, false);
    }

    /** Only suppress drawing inside a currently BB-owned shade or keyguard window. */
    static boolean shouldSuppress(boolean bbHostMounted, boolean sameNativeWindow,
            boolean interactiveBb6, boolean sameLockscreenWindow) {
        return (bbHostMounted && sameNativeWindow)
                || (interactiveBb6 && sameLockscreenWindow);
    }
}
