package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import org.junit.Test;

/** Reusable session state tests, independent of SystemUI or WindowManager. */
public final class ShadeUiControllerTest {
    private static final class FakeHost implements ShadeUiController.Listener {
        final ShadeUiController navigation = new ShadeUiController(this);
        int popupDismissals;
        int opened;
        int switched;
        int recents;
        int closed;
        int focusZone = ShadeStatusNavigation.NONE;

        @Override public void onStatusFocusChanged(int zone) { focusZone = zone; }

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

        @Override public void onCloseApplication() { closed++; }
        @Override public void onSwitchApplication() { recents++; }

        @Override public void onExit() {
            popupDismissals++;
            if (navigation.activePage() == ShadeStatusNavigation.NONE) {
                navigation.leaveStandaloneStatusBar();
            } else {
                navigation.focusContent();
            }
        }
    }

    private static void key(FakeHost f, int code) {
        assertTrue(f.navigation.handleKey(code, KeyEvent.ACTION_DOWN));
        assertTrue(f.navigation.handleKey(code, KeyEvent.ACTION_UP));
    }

    @Test public void bbDisplaysPopupButDoesNotOpenShade() {
        FakeHost f = new FakeHost();
        f.navigation.enterStandaloneStatusBar();
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, f.focusZone);
        assertEquals(0, f.opened);
        key(f, KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, f.focusZone);
        key(f, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, f.opened);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, f.navigation.activePage());
        assertFalse(f.navigation.isStatusBarFocused());
    }

    @Test public void touchSelectsRowWithoutImmediateActivation() {
        FakeHost f = new FakeHost();
        f.navigation.enterStandaloneStatusBar();
        f.navigation.selectPopupZone(ShadeStatusNavigation.QUICK_SETTINGS);
        assertEquals(0, f.opened);
        key(f, KeyEvent.KEYCODE_ENTER);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, f.navigation.activePage());
    }

    @Test public void popupCannotBeSelectedUnlessFocused() {
        FakeHost f = new FakeHost();
        f.navigation.selectPopupZone(ShadeStatusNavigation.QUICK_SETTINGS);
        assertFalse(f.navigation.isStatusBarFocused());
        f.navigation.onShadeOpened(ShadeStatusNavigation.NOTIFICATIONS);
        f.navigation.selectPopupZone(ShadeStatusNavigation.QUICK_SETTINGS);
        assertFalse(f.navigation.isStatusBarFocused());
    }

    @Test public void backDismissesStandalonePopupWithoutOpeningShade() {
        FakeHost f = new FakeHost();
        f.navigation.enterStandaloneStatusBar();
        key(f, KeyEvent.KEYCODE_BACK);
        assertEquals(1, f.popupDismissals);
        assertEquals(0, f.opened);
        assertEquals(ShadeStatusNavigation.NONE, f.navigation.activePage());
    }

    @Test public void reopenedPopupBackPreservesExpandedShade() {
        FakeHost f = new FakeHost();
        f.navigation.onShadeOpened(ShadeStatusNavigation.NOTIFICATIONS);
        f.navigation.openPopupFromBlackBerryKey(ShadeStatusNavigation.NOTIFICATIONS);
        key(f, KeyEvent.KEYCODE_BACK);
        assertEquals(1, f.popupDismissals);
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, f.navigation.activePage());
        assertFalse(f.navigation.isStatusBarFocused());
    }

    @Test public void popupSwitchesRetainedShadePage() {
        FakeHost f = new FakeHost();
        f.navigation.onShadeOpened(ShadeStatusNavigation.NOTIFICATIONS);
        f.navigation.openPopupFromBlackBerryKey(ShadeStatusNavigation.NOTIFICATIONS);
        key(f, KeyEvent.KEYCODE_DPAD_DOWN);
        key(f, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, f.switched);
        assertEquals(0, f.popupDismissals);
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS, f.navigation.activePage());
        f.navigation.openPopupFromBlackBerryKey(ShadeStatusNavigation.QUICK_SETTINGS);
        key(f, KeyEvent.KEYCODE_DPAD_UP);
        key(f, KeyEvent.KEYCODE_ENTER);
        assertEquals(2, f.switched);
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS, f.navigation.activePage());
    }

    @Test public void shadeContentsKeepTheirOwnKeyHandlers() {
        FakeHost f = new FakeHost();
        f.navigation.onShadeOpened(ShadeStatusNavigation.QUICK_SETTINGS);
        assertFalse(f.navigation.handleKey(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP));
        assertFalse(f.navigation.handleKey(KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_UP));
    }

    @Test public void applicationMenuActionsAreIndependentOfShadePages() {
        FakeHost host = new FakeHost();
        host.navigation.setCloseApplicationAvailable(true);
        host.navigation.enterStandaloneStatusBar();
        key(host, KeyEvent.KEYCODE_DPAD_UP);
        key(host, KeyEvent.KEYCODE_DPAD_UP);
        assertEquals(ShadeStatusNavigation.CLOSE_APPLICATION, host.focusZone);
        key(host, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, host.closed);
        assertEquals(0, host.recents);
        assertEquals(0, host.opened);
        assertEquals(ShadeStatusNavigation.NONE, host.navigation.activePage());

        host.navigation.enterStandaloneStatusBar();
        key(host, KeyEvent.KEYCODE_DPAD_UP);
        key(host, KeyEvent.KEYCODE_ENTER);
        assertEquals(1, host.closed);
        assertEquals(1, host.recents);
        assertEquals(0, host.opened);
    }

    @Test public void upwardNavigationInEitherOpenShadeNeverOpensPopup() {
        for (int page : new int[]{
                ShadeStatusNavigation.NOTIFICATIONS,
                ShadeStatusNavigation.QUICK_SETTINGS}) {
            FakeHost f = new FakeHost();
            f.navigation.onShadeOpened(page);
            assertFalse(f.navigation.handleKey(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN));
            assertFalse(f.navigation.handleKey(
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP));
            assertFalse(f.navigation.isStatusBarFocused());
            assertEquals(ShadeStatusNavigation.NONE, f.focusZone);
            assertEquals(page, f.navigation.activePage());
        }
    }
}
