package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Popup row selection for both shade pages. Up/Down selects a row;
 * Enter activates it and Back dismisses the transient popup.
 */
final class ShadeStatusNavigation {
    static final int NONE = -1;
    static final int NOTIFICATIONS = 0;
    static final int QUICK_SETTINGS = 1;

    interface Listener {
        void onZoneFocus(int zone);
        void onBack();
        void onNotificationsSelected();
        void onQuickSettingsSelected();
    }

    private final Listener listener;
    private int zone = NONE;

    ShadeStatusNavigation(Listener listener) {
        this.listener = listener;
    }

    int zone() { return zone; }

    boolean isFocused() { return zone != NONE; }

    void enter(int selectedZone) {
        if (selectedZone != NOTIFICATIONS && selectedZone != QUICK_SETTINGS) {
            throw new IllegalArgumentException("Invalid popup row");
        }
        changeZone(selectedZone);
    }

    void leave() { changeZone(NONE); }

    /** Consume only popup keys; content retains all other key handling. */
    boolean handleKey(int code, int action) {
        if (!isFocused()) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    changeZone(code == KeyEvent.KEYCODE_DPAD_UP
                            ? NOTIFICATIONS : QUICK_SETTINGS);
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
                    if (selectedZone == NOTIFICATIONS) listener.onNotificationsSelected();
                    else listener.onQuickSettingsSelected();
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
