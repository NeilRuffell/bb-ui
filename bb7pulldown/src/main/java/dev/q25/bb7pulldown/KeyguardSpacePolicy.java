package dev.q25.bb7pulldown;

/** Pure routing policy; Android's existing authentication UI handles credentials. */
final class KeyguardSpacePolicy {
    static final int PASS = 0;
    static final int CONSUME = 1;
    static final int REQUEST_NATIVE_ENTRY = 2;

    private static final int DOWN = 0;
    private static final int UP = 1;

    private long interceptedDownTime = -1;

    int route(int action, long downTime, int repeatCount,
            boolean keyguardShowing, boolean bouncerShowing, boolean textEditorFocused) {
        // A matching release cannot reach a newly focused QS or notification view.
        if (downTime == interceptedDownTime) {
            if (action == UP) {
                interceptedDownTime = -1;
                return CONSUME;
            }
            if (action == DOWN) return CONSUME;
        }

        if (!keyguardShowing || bouncerShowing || textEditorFocused) return PASS;

        if (action == DOWN) {
            interceptedDownTime = downTime;
            return repeatCount == 0 ? REQUEST_NATIVE_ENTRY : CONSUME;
        }
        // An orphan Space UP on the lock screen must not click a selected item.
        if (action == UP) return CONSUME;
        return PASS;
    }
}
