package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Only Enter/trackpad-center activates the limited locked Quick Settings panel.
 * The physical Space key MUST remain available to BB6KeyguardKeyboardHook,
 * which handles it at WindowRootView before child key dispatch.
 */
final class BB6LockScreenSelectionPolicy {
    private BB6LockScreenSelectionPolicy() {}

    static boolean isActivationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }
}
