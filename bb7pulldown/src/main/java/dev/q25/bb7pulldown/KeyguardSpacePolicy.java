package dev.q25.bb7pulldown;

/**
 * Android-independent Space routing for SystemUI's keyguard window.
 *
 * The native bouncer remains the only authority for credentials and unlock.
 * Consume DOWN and its matching UP together, including when focus changes
 * during the bouncer transition.
 */
final class KeyguardSpacePolicy {
    static final int PASS = 0;
    static final int CONSUME = 1;
    static final int REQUEST_BOUNCER = 2;

    // android.view.KeyEvent ACTION_DOWN and ACTION_UP values.
    private static final int DOWN = 0;
    private static final int UP = 1;
    private long consumedDownTime = -1L;

    int route(int action, long downTime, int repeatCount, boolean locked,
            boolean primaryBouncerShowing, boolean bouncerInTransit,
            boolean managerReady, boolean textEditorFocused) {
        // A matching UP must not be forwarded into a new focus target.
        if (consumedDownTime == downTime && action == UP) {
            consumedDownTime = -1L;
            return CONSUME;
        }
        if (consumedDownTime == downTime && action == DOWN) return CONSUME;

        if (!locked || primaryBouncerShowing) return PASS;

        // A focused text editor owns Space, including passwords. A QS tile
        // or notification action is not a text editor.
        if (textEditorFocused) return PASS;

        // All other Space keys on the initial lockscreen are blocked from
        // reaching focusable notifications, QS tiles, or their click handlers.
        if (action == DOWN) {
            consumedDownTime = downTime;
            return repeatCount == 0 && managerReady && !bouncerInTransit
                    ? REQUEST_BOUNCER : CONSUME;
        }
        if (action == UP) return CONSUME;
        return CONSUME;
    }
}
