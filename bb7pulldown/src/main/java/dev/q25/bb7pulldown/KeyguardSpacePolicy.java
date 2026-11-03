package dev.q25.bb7pulldown;

/**
 * Routes the actual hardware Space event before SystemUI dispatches to a
 * focused notification or Quick Settings tile.
 *
 * Android's native keyguard handler is invoked on the matching UP, just as
 * its KeyguardKeyEventInteractor expects. No synthetic keys or clicks.
 */
final class KeyguardSpacePolicy {
    static final int PASS = 0;
    static final int CONSUME = 1;
    static final int REQUEST_BOUNCER = 2;

    private static final int DOWN = 0;
    private static final int UP = 1;
    private long consumedDownTime = -1L;

    int route(int action, long downTime, boolean keyguardShowing,
            boolean bouncerShowing, boolean textEditorFocused) {
        // Paired UP never reaches a newly focused notification or QS tile.
        if (consumedDownTime == downTime && action == UP) {
            consumedDownTime = -1L;
            return keyguardShowing && !bouncerShowing
                    ? REQUEST_BOUNCER : CONSUME;
        }
        if (consumedDownTime == downTime && action == DOWN) return CONSUME;
        if (!keyguardShowing || bouncerShowing || textEditorFocused) return PASS;

        if (action == DOWN) {
            consumedDownTime = downTime;
            return CONSUME;
        }
        // Orphan UP while locked must not activate a selected QS control.
        return action == UP ? CONSUME : PASS;
    }
}
