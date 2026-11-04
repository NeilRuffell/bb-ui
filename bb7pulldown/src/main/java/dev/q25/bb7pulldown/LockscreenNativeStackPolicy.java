package dev.q25.bb7pulldown;

/**
 * Keyguard/shade state policy for native lock-screen composition. The same
 * notification subtree is shared with shade, so it must not be detached
 * outside the interactive KEYGUARD state.
 */
final class LockscreenNativeStackPolicy {
    static final int UNKNOWN = -1;
    static final int SHADE = 0;
    static final int KEYGUARD = 1;
    static final int SHADE_LOCKED = 2;

    private LockscreenNativeStackPolicy() {}

    static boolean hideNativeStack(boolean deviceLocked, int statusBarState) {
        if (!deviceLocked) return false;
        // Until the state controller is ready, use the BB6 keyguard only
        // while Android confirms the device is locked.
        return statusBarState == KEYGUARD || statusBarState == UNKNOWN;
    }
}
