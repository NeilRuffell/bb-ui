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
        int toContent;
        int toContentBack;
        int notifications;
        int quick;

        @Override public void onZoneFocus(int zone) {
            focused = zone;
            changes++;
        }
        @Override public void onReturnToContent(boolean viaBack) {
            toContent++;
            if (viaBack) toContentBack++;
        }
        @Override public void onNotificationsSelected() { notifications++; }
        @Override public void onQuickSettingsSelected() { quick++; }
    }

    @Test public void disabledInContentSoExistingKeyHandlersStayUntouched() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        assertFalse(nav.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN));
        assertFalse(nav.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
        assertEquals(0, callbacks.changes);
    }

    @Test public void quickShadeCanNavigateUpAcrossBarThenOpenNotifications() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        nav.enter(ShadeStatusNavigation.QUICK_SETTINGS);
        assertEquals(1, callbacks.focused);
        assertTrue(nav.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN));
        assertTrue(nav.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN));
        assertEquals(0, callbacks.focused);
        assertTrue(nav.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN));
        assertEquals(0, callbacks.notifications);
        assertTrue(nav.handleKey(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP));
        assertEquals(1, callbacks.notifications);
        assertEquals(0, callbacks.quick);
        assertEquals(ShadeStatusNavigation.NONE, nav.zone());
    }

    @Test public void notificationsCanSwitchToQuickSettings() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        nav.enter(ShadeStatusNavigation.NOTIFICATIONS);
        nav.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN);
        nav.handleKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP);
        assertEquals(1, callbacks.quick);
        assertEquals(0, callbacks.notifications);
        assertFalse(nav.isFocused());
    }

    @Test public void downAndBackReturnToContentButDifferInReason() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        nav.enter(ShadeStatusNavigation.QUICK_SETTINGS);
        nav.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP);
        assertEquals(1, callbacks.toContent);
        assertEquals(0, callbacks.toContentBack);
        assertEquals(ShadeStatusNavigation.NONE, callbacks.focused);
        nav.enter(ShadeStatusNavigation.QUICK_SETTINGS);
        nav.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP);
        assertEquals(2, callbacks.toContent);
        assertEquals(1, callbacks.toContentBack);
    }

    @Test public void upIsBoundedOnStatusBarAndRepeatedDirectionsDoNotSpam() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        nav.enter(ShadeStatusNavigation.QUICK_SETTINGS);
        nav.handleKey(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN);
        nav.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN);
        assertEquals(1, callbacks.changes);
        assertEquals(1, nav.zone());
    }

    @Test public void onlyRecognizedKeyboardControlsAreConsumed() {
        Callbacks callbacks = new Callbacks();
        ShadeStatusNavigation nav = new ShadeStatusNavigation(callbacks);
        nav.enter(ShadeStatusNavigation.NOTIFICATIONS);
        assertFalse(nav.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
        assertFalse(nav.handleKey(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP));
        assertTrue(nav.handleKey(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP));
        assertEquals(1, callbacks.notifications);
    }
}
