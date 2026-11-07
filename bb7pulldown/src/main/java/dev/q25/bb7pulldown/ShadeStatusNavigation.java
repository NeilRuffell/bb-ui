package dev.q25.bb7pulldown;

import android.view.KeyEvent;

/**
 * Transient BB application menu navigation: Up/Down moves one visible row,
 * Enter executes the selected action, and Back dismisses the menu.
 */
final class ShadeStatusNavigation {
    static final int NONE = -1;
    static final int NOTIFICATIONS = 0;
    static final int QUICK_SETTINGS = 1;
    static final int CLOSE_APPLICATION = 2;
    static final int SWITCH_APPLICATION = 3;

    private static final int[] ORDER = {
            CLOSE_APPLICATION, SWITCH_APPLICATION, NOTIFICATIONS, QUICK_SETTINGS
    };

    interface Listener {
        void onZoneFocus(int zone);
        void onBack();
        void onNotificationsSelected();
        void onQuickSettingsSelected();
        void onCloseApplicationSelected();
        void onSwitchApplicationSelected();
    }

    private final Listener listener;
    private int zone = NONE;
    private boolean closeAvailable;

    ShadeStatusNavigation(Listener listener) { this.listener = listener; }

    int zone() { return zone; }
    boolean isFocused() { return zone != NONE; }

    void setCloseApplicationAvailable(boolean available) {
        closeAvailable = available;
        if (!available && zone == CLOSE_APPLICATION) changeZone(SWITCH_APPLICATION);
    }

    void enter(int selectedZone) {
        if (!selectable(selectedZone)) {
            throw new IllegalArgumentException("Invalid/unavailable application menu row");
        }
        changeZone(selectedZone);
    }

    void leave() { changeZone(NONE); }

    boolean handleKey(int code, int action) {
        if (!isFocused()) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (action == KeyEvent.ACTION_DOWN) {
                    move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
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
                    int selected = zone;
                    leave();
                    switch (selected) {
                        case NOTIFICATIONS: listener.onNotificationsSelected(); break;
                        case QUICK_SETTINGS: listener.onQuickSettingsSelected(); break;
                        case CLOSE_APPLICATION: listener.onCloseApplicationSelected(); break;
                        case SWITCH_APPLICATION: listener.onSwitchApplicationSelected(); break;
                        default: break;
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private boolean selectable(int candidate) {
        return candidate == NOTIFICATIONS || candidate == QUICK_SETTINGS
                || candidate == SWITCH_APPLICATION
                || (candidate == CLOSE_APPLICATION && closeAvailable);
    }

    private void move(int direction) {
        int current = -1;
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == zone) { current = i; break; }
        }
        if (current < 0) return;
        for (int i = current + direction; i >= 0 && i < ORDER.length; i += direction) {
            if (selectable(ORDER[i])) {
                changeZone(ORDER[i]);
                break;
            }
        }
    }

    private void changeZone(int next) {
        if (zone == next) return;
        zone = next;
        listener.onZoneFocus(zone);
    }
}
