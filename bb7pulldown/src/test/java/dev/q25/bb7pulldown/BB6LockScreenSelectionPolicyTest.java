package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;
import org.junit.Test;

public class BB6LockScreenSelectionPolicyTest {
    @Test public void trackpadAndEnterSelectionHaveNoAction() {
        assertTrue(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_DPAD_CENTER));
        assertTrue(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_ENTER));
        assertTrue(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_NUMPAD_ENTER));
    }

    @Test public void spaceRemainsOwnedByNativeCredentialHook() {
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_SPACE));
    }

    @Test public void dpadTraversalIsNotConsumed() {
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_DPAD_UP));
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_DPAD_DOWN));
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_DPAD_LEFT));
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_DPAD_RIGHT));
    }

    @Test public void backIsNotConsumed() {
        assertFalse(BB6LockScreenSelectionPolicy.isNoopSelectionKey(
                KeyEvent.KEYCODE_BACK));
    }
}
