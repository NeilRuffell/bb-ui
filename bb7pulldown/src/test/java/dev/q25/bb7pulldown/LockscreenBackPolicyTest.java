package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;
import org.junit.Test;

public class LockscreenBackPolicyTest {
    @Test public void physicalBackConsumesDownAndClosesOnUp() {
        assertEquals(LockscreenBackPolicy.CONSUME,
                LockscreenBackPolicy.route(KeyEvent.KEYCODE_BACK,
                        KeyEvent.ACTION_DOWN, true, true));
        assertEquals(LockscreenBackPolicy.CLOSE,
                LockscreenBackPolicy.route(KeyEvent.KEYCODE_BACK,
                        KeyEvent.ACTION_UP, true, true));
    }

    @Test public void escapeAlsoClosesTheCompactPanel() {
        assertEquals(LockscreenBackPolicy.CONSUME,
                LockscreenBackPolicy.route(KeyEvent.KEYCODE_ESCAPE,
                        KeyEvent.ACTION_DOWN, true, true));
        assertEquals(LockscreenBackPolicy.CLOSE,
                LockscreenBackPolicy.route(KeyEvent.KEYCODE_ESCAPE,
                        KeyEvent.ACTION_UP, true, true));
    }

    @Test public void nativeBackRemainsAvailableWhenPanelNotActive() {
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            assertEquals(LockscreenBackPolicy.PASS,
                    LockscreenBackPolicy.route(KeyEvent.KEYCODE_BACK,
                            action, false, true));
            assertEquals(LockscreenBackPolicy.PASS,
                    LockscreenBackPolicy.route(KeyEvent.KEYCODE_BACK,
                            action, true, false));
        }
    }

    @Test public void spaceAndNavigationAreUnaffected() {
        for (int key : new int[] {KeyEvent.KEYCODE_SPACE,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER}) {
            assertEquals(LockscreenBackPolicy.PASS,
                    LockscreenBackPolicy.route(key,
                            KeyEvent.ACTION_UP, true, true));
        }
    }

    @Test public void nonKeyboardEventsRemainNative() {
        assertEquals(LockscreenBackPolicy.PASS,
                LockscreenBackPolicy.route(KeyEvent.KEYCODE_BACK,
                        KeyEvent.ACTION_MULTIPLE, true, true));
    }
}
