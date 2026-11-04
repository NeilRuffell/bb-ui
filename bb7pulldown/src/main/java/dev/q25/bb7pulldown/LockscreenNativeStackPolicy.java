package dev.q25.bb7pulldown;

/**
 * Pure state policy for removing the native notification stack from the BB6
 * lock screen. The same native container is reused by the notification shade,
 * so that container must be restored when entering either shade state.
 */
final class LockscreenNativeStackPolicy {
    static final int UNKNOWN = -1;
    static final int SHADE = 0;
    static final int KEYGUARD = 1;
    static final int SHADE_LOCKED = 2;

    private LockscreenNativeStackPolicy() {}

    static boolean hideNativeStack(boolean deviceLocked, int statusBarState) {
        if (!deviceLocked) return false;
        // Before the native state controller is ready, prefer the BB6 lock
        // screen; preserve the full shade once an explicit shade is known.
        return statusBarState == KEYGUARD || statusBarState == UNKNOWN;
    }
}
