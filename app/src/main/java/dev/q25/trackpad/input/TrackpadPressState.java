package dev.q25.trackpad.input;

/**
 * Small, Android-independent press state machine shared by trackpad-friendly
 * interfaces. A press becomes either a click on release or a long click after
 * the platform timeout, never both. The caller owns key filtering and timers.
 */
public final class TrackpadPressState {
    private static final int NO_KEY = -1;
    private int heldKey = NO_KEY;
    private boolean longFired;

    public boolean press(int keyCode) {
        if (heldKey != NO_KEY) return false;
        heldKey = keyCode;
        longFired = false;
        return true;
    }

    public boolean fireLongPress() {
        if (heldKey == NO_KEY || longFired) return false;
        longFired = true;
        return true;
    }

    public boolean release(int keyCode) {
        boolean click = heldKey == keyCode && !longFired;
        reset();
        return click;
    }

    public boolean isHeld() {
        return heldKey != NO_KEY;
    }

    public void reset() {
        heldKey = NO_KEY;
        longFired = false;
    }
}
