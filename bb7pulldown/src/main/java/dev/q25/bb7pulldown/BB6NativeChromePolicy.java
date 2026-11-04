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

    /**
     * Only native awake heads-up interruptions duplicate the interactive
     * BB6 lock-screen presentation. Never block bubbles, pulses, or the
     * underlying notification pipeline.
     */
    static boolean suppressStockInterruption(String callback, boolean bb6Active) {
        return bb6Active && "suppressAwakeHeadsUp".equals(callback);
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
