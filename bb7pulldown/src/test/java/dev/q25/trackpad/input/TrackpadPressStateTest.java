package dev.q25.trackpad.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrackpadPressStateTest {
    @Test public void shortPressFiresOnlyOnMatchingRelease() {
        TrackpadPressState press = new TrackpadPressState();
        assertTrue(press.press(23));
        assertFalse(press.press(23));
        assertTrue(press.release(23));
        assertFalse(press.isHeld());
        assertFalse(press.release(23));
    }

    @Test public void longPressNeverAlsoClicks() {
        TrackpadPressState press = new TrackpadPressState();
        assertTrue(press.press(23));
        assertTrue(press.fireLongPress());
        assertFalse(press.fireLongPress());
        assertFalse(press.release(23));
        assertFalse(press.fireLongPress());
    }

    @Test public void cancelSuppressesBothActions() {
        TrackpadPressState press = new TrackpadPressState();
        assertTrue(press.press(23));
        press.reset();
        assertFalse(press.fireLongPress());
        assertFalse(press.release(23));
        assertTrue(press.press(23));
    }

    @Test public void mismatchedReleaseDoesNotActivate() {
        TrackpadPressState press = new TrackpadPressState();
        assertTrue(press.press(23));
        assertFalse(press.release(66));
        assertFalse(press.isHeld());
    }
}
