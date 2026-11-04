package dev.q25.bb7pulldown;

/**
 * Decides whether BB6 owns the interactive keyguard presentation. The normal
 * keyguard, AOD, native shade and authentication presenters remain available.
 */
final class BB6NativeChromePolicy {
    private BB6NativeChromePolicy() {}

    static boolean useBb6Composition(boolean locked, boolean interactive,
            int statusBarState, boolean bouncerShowing) {
        return interactive && !bouncerShowing
                && LockscreenNativeStackPolicy.hideNativeStack(locked, statusBarState);
    }

    /** Suppress only the stock generic swipe instruction at its producer.
     * Charging, accessibility, biometric state and security alerts pass.
     */
    static boolean redundantSwipeHint(boolean bb6Active, String text,
            String nativeSwipeInstruction, boolean hasFollowUp, boolean isSuccess) {
        return bb6Active && !hasFollowUp && !isSuccess
                && nativeSwipeInstruction != null
                && nativeSwipeInstruction.equals(text);
    }
}
