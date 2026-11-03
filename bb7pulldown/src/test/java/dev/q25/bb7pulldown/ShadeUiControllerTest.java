package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import org.junit.Test;

/** Tests the reusable session owner with no SystemUI, WindowManager, or Xposed. */
public final class ShadeUiControllerTest {
    private static final class FakeHost implements ShadeUiController.Listener {
        ShadeUiController navigation = new ShadeUiController(this);
        int exits;
        int recents;
        int contentFocus;
        int opened;
        int switched;
        int focusZone = ShadeStatusNavigation.NONE;

        @Override public void onStatusFocusChanged(int zone) {
            focusZone = zone;
        }

        @Override public void onShowPage(int page) {
            if (navigation.activePage() == ShadeStatusNavigation.NONE) {
                opened++;
                navigation.onShadeOpened(page);
            } else {
                switched++;
                navigation.onPageChanged(page);
            }
            navigation.focusContent();
        }

        @Override public void onFocusShadeContent() {
            contentFocus++;
            navigation.focusContent();
        }

        @Override public void onExit() {
            exits++;
            // The real adapter animates before tearing down the shade.
            navigation.onShadeClosed();
        }

        @Override public void onNativeRecents() {
            recents++;
            navigation.leaveStandaloneStatusBar();
        }
    }

    private static void key(FakeHost fake, int code) {
        assertTrue(fake.navigation.handleKey(code, KeyEvent.ACTION_DOWN));
        assertTrue(fake.navigation.handleKey(code, KeyEvent.ACTION_UP));
    }

    @Test public void backFromStandaloneStatusBarExitsWithoutOpeningShade() {
        FakeHost host = new FakeHost();
        host.navigation.enterStandaloneStatusBar();
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, host.focusZone);
        key(host, KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.exits);
        assertEquals(0, host.contentFocus);
        assertEquals(0, host.opened);
        assertEquals(ShadeStatusNavigation.NONE, host.navigation.focusedZone());
    }

    @Test public void statusBarThenShadeThenUpThenBackClosesEntireShade() {
        FakeHost host = new FakeHost();
        host.navigation.enterStandaloneStatusBar();
        key(host, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, host.opened);
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, host.navigation.activePage());
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.NOTIFICATIONS);
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, host.focusZone);
        key(host, KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.exits);
        assertEquals(0, host.contentFocus);
        assertEquals(ShadeStatusNavigation.NONE, host.navigation.activePage());
        assertEquals(ShadeStatusNavigation.NONE, host.navigation.focusedZone());
    }

    @Test public void quickSettingsBackFromStatusBarExitsNotFirstRadioRow() {
        FakeHost host = new FakeHost();
        host.navigation.onShadeOpened(ShadeStatusNavigation.QUICK_SETTINGS);
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, host.focusZone);
        key(host, KeyEvent.KEYCODE_BACK);
        assertEquals(1, host.exits);
        assertEquals(0, host.contentFocus);
        assertEquals(ShadeStatusNavigation.NONE, host.navigation.focusedZone());
    }

    @Test public void downFromShadeStatusBarReturnsToPageContent() {
        FakeHost host = new FakeHost();
        host.navigation.onShadeOpened(ShadeStatusNavigation.QUICK_SETTINGS);
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
        key(host, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.contentFocus);
        assertEquals(0, host.exits);
        assertEquals(0, host.recents);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, host.navigation.activePage());
    }

    @Test public void downFromStandaloneStatusBarStillOpensNativeRecents() {
        FakeHost host = new FakeHost();
        host.navigation.enterStandaloneStatusBar();
        key(host, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, host.recents);
        assertEquals(0, host.contentFocus);
        assertEquals(0, host.exits);
    }

    @Test public void switchingPagesDoesNotExitShadeAndFocusStartsInContent() {
        FakeHost host = new FakeHost();
        host.navigation.onShadeOpened(ShadeStatusNavigation.NOTIFICATIONS);
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.NOTIFICATIONS);
        key(host, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, host.focusZone);
        key(host, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, host.switched);
        assertEquals(0, host.exits);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, host.navigation.activePage());
        assertFalse(host.navigation.isStatusBarFocused());
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
        key(host, KeyEvent.KEYCODE_DPAD_LEFT);
        key(host, KeyEvent.KEYCODE_ENTER);
        assertEquals(2, host.switched);
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, host.navigation.activePage());
    }

    @Test public void inactivePageCannotCaptureStatusBarFocus() {
        FakeHost host = new FakeHost();
        host.navigation.onShadeOpened(ShadeStatusNavigation.QUICK_SETTINGS);
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.NOTIFICATIONS);
        assertFalse(host.navigation.isStatusBarFocused());
        host.navigation.focusStatusBarFromPage(ShadeStatusNavigation.QUICK_SETTINGS);
        assertTrue(host.navigation.isStatusBarFocused());
    }

    @Test public void contentNavigationKeysAreNotConsumedByController() {
        FakeHost host = new FakeHost();
        host.navigation.onShadeOpened(ShadeStatusNavigation.NOTIFICATIONS);
        assertFalse(host.navigation.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP));
        assertFalse(host.navigation.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
    }
}
