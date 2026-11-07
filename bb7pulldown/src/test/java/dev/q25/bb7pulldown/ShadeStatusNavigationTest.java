package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import org.junit.Test;

public class ShadeStatusNavigationTest {
    private static final class Callbacks implements ShadeStatusNavigation.Listener {
        int focused = ShadeStatusNavigation.NONE;
        int changes;
        int backs;
        int notifications;
        int quick;
        int closed;
        int recents;
        @Override public void onZoneFocus(int zone) { focused = zone; changes++; }
        @Override public void onBack() { backs++; }
        @Override public void onNotificationsSelected() { notifications++; }
        @Override public void onQuickSettingsSelected() { quick++; }
        @Override public void onCloseApplicationSelected() { closed++; }
        @Override public void onSwitchApplicationSelected() { recents++; }
    }

    @Test public void contentKeysStayUntouchedOutsidePopup() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        assertFalse(n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN));
        assertFalse(n.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
        assertEquals(0, c.changes);
    }

    @Test public void downSelectsQuickSettingsThenEnterOpensOnce() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.enter(ShadeStatusNavigation.NOTIFICATIONS);
        assertTrue(n.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, c.focused);
        n.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN);
        assertEquals(0, c.quick);
        n.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP);
        assertEquals(1, c.quick);
        assertEquals(0, c.notifications);
        assertEquals(ShadeStatusNavigation.NONE, n.zone());
    }

    @Test public void upFromQuickSettingsReturnsToNotifications() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.enter(ShadeStatusNavigation.QUICK_SETTINGS);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        n.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP);
        assertEquals(1, c.notifications);
    }

    @Test public void switchApplicationExistsWithoutCloseApplication() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.enter(ShadeStatusNavigation.NOTIFICATIONS);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION, c.focused);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION, c.focused);
        n.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP);
        assertEquals(1, c.recents);
        assertEquals(0, c.closed);
    }

    @Test public void closeAvailableEnablesOneMoreUpRow() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.setCloseApplicationAvailable(true);
        n.enter(ShadeStatusNavigation.NOTIFICATIONS);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        assertEquals(ShadeStatusNavigation.CLOSE_APPLICATION, c.focused);
        n.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        assertEquals(ShadeStatusNavigation.CLOSE_APPLICATION, c.focused);
        n.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP);
        assertEquals(1, c.closed);
    }

    @Test public void unavailableCloseRowIsNotSelectable() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        boolean rejected = false;
        try {
            n.enter(ShadeStatusNavigation.CLOSE_APPLICATION);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        n.setCloseApplicationAvailable(true);
        n.enter(ShadeStatusNavigation.CLOSE_APPLICATION);
        n.setCloseApplicationAvailable(false);
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION, n.zone());
    }

    @Test public void backDismissesWithoutActivatingAnAction() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.enter(ShadeStatusNavigation.NOTIFICATIONS);
        n.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP);
        assertEquals(1, c.backs);
        assertEquals(0, c.notifications);
        assertEquals(0, c.quick);
        assertEquals(0, c.closed);
        assertEquals(0, c.recents);
        assertFalse(n.isFocused());
    }

    @Test public void lateralAndTextKeysAreNotConsumed() {
        Callbacks c = new Callbacks();
        ShadeStatusNavigation n = new ShadeStatusNavigation(c);
        n.enter(ShadeStatusNavigation.NOTIFICATIONS);
        assertFalse(n.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN));
        assertFalse(n.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN));
        assertFalse(n.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
        assertFalse(n.handleKey(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP));
        assertTrue(n.handleKey(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP));
        assertEquals(1, c.notifications);
    }
}
