package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Shared status-bar focus and keyboard routing for both BB UI shades.
 *
 * The native Android status bar remains the visual target. Each panel keeps
 * its own content focus, while this controller owns the two focus zones and
 * identical Up / Down / Left / Right / Enter / Back behavior.
 *
 * Down and Back are separate callbacks. The owning ShadeUiController decides
 * their session-level consequences; individual pages cannot redefine them.
 */
final class ShadeStatusNavigation {
    static final int NONE = -1;
    static final int NOTIFICATIONS = 0;
    static final int QUICK_SETTINGS = 1;

    interface Listener {
        void onZoneFocus(int zone);
        void onDown();
        void onBack();
        void onNotificationsSelected();
        void onQuickSettingsSelected();
    }

    private final Listener listener;
    private int zone = NONE;

    ShadeStatusNavigation(Listener listener) {
        this.listener = listener;
    }

    int zone() {
        return zone;
    }

    boolean isFocused() {
        return zone != NONE;
    }

    void enter(int selectedZone) {
        if (selectedZone != NOTIFICATIONS && selectedZone != QUICK_SETTINGS) {
            throw new IllegalArgumentException("Invalid status-bar zone");
        }
        changeZone(selectedZone);
    }

    void leave() {
        changeZone(NONE);
    }

    /**
     * Returns false outside the status bar, leaving all content key handling
     * and hardware Backspace/Delete behavior untouched.
     *
     * Key-down changes selection; key-up activates a control exactly once.
     */
    boolean handleKey(int code, int action) {
        if (!isFocused()) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (action == KeyEvent.ACTION_DOWN) {
                    changeZone(code == KeyEvent.KEYCODE_DPAD_LEFT
                            ? NOTIFICATIONS : QUICK_SETTINGS);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (action == KeyEvent.ACTION_UP) {
                    leave();
                    listener.onDown();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (action == KeyEvent.ACTION_UP) {
                    leave();
                    listener.onBack();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (action == KeyEvent.ACTION_UP) {
                    int selectedZone = zone;
                    leave();
                    if (selectedZone == NOTIFICATIONS) {
                        listener.onNotificationsSelected();
                    } else {
                        listener.onQuickSettingsSelected();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private void changeZone(int next) {
        if (zone == next) return;
        zone = next;
        listener.onZoneFocus(zone);
    }
}
