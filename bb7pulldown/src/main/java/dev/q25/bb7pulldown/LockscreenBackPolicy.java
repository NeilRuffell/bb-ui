package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Scope physical Back/Escape ownership to the foreground limited QS panel.
 * Only the complete Back press is consumed; Space remains in the existing
 * native credential handler. Android predictive Back uses a window callback.
 */
final class LockscreenBackPolicy {
    static final int PASS = 0;
    static final int CONSUME = 1;
    static final int CLOSE = 2;

    private LockscreenBackPolicy() {}

    static int route(int keyCode, int action, boolean panelAttached,
            boolean nativeKeyguardEligible) {
        if (!panelAttached || !nativeKeyguardEligible
                || (keyCode != KeyEvent.KEYCODE_BACK
                    && keyCode != KeyEvent.KEYCODE_ESCAPE)) {
            return PASS;
        }
        if (action == KeyEvent.ACTION_DOWN) return CONSUME;
        if (action == KeyEvent.ACTION_UP) return CLOSE;
        return PASS;
    }
}
