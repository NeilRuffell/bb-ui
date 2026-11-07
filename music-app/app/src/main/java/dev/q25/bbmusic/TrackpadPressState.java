package dev.q25.bbmusic;

/**
 * Portable key-press state machine, copied from BB UI's proven QS trackpad
 * handler with no build or runtime dependency on the BB UI module.
 */
final class TrackpadPressState {
    private static final int NO_KEY = -1;
    private int heldKey = NO_KEY;
    private boolean longFired;

    boolean press(int keyCode) {
        if (heldKey != NO_KEY) return false;
        heldKey = keyCode;
        longFired = false;
        return true;
    }

    boolean fireLongPress() {
        if (heldKey == NO_KEY || longFired) return false;
        longFired = true;
        return true;
    }

    boolean release(int keyCode) {
        boolean click = heldKey == keyCode && !longFired;
        reset();
        return click;
    }

    boolean isHeld() {
        return heldKey != NO_KEY;
    }

    void reset() {
        heldKey = NO_KEY;
        longFired = false;
    }
}
