package dev.q25.bb7pulldown;

/** Locked Space is a native credential-entry command, not a focused click. */
final class KeyguardSpacePolicy {
    static final int PASS = 0;
    static final int CONSUME = 1;
    static final int REQUEST_NATIVE_ENTRY = 2;

    private static final int DOWN = 0;
    private static final int UP = 1;
    private long interceptedDownTime = -1;

    int route(int action, long downTime, int repeatCount,
            boolean keyguardShowing, boolean bouncerShowing, boolean textEditorFocused) {
        if (downTime == interceptedDownTime) {
            if (action == UP) {
                interceptedDownTime = -1;
                // Do not request entry if an existing unlock UI appeared
                // in between physical DOWN and UP.
                return keyguardShowing && !bouncerShowing && !textEditorFocused
                        ? REQUEST_NATIVE_ENTRY : CONSUME;
            }
            if (action == DOWN) return CONSUME;
        }

        if (!keyguardShowing || bouncerShowing || textEditorFocused) return PASS;

        if (action == DOWN) {
            interceptedDownTime = downTime;
            return CONSUME;
        }
        // Orphan release must not activate a selected notification or QS tile.
        return action == UP ? CONSUME : PASS;
    }
}
