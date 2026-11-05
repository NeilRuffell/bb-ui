package dev.q25.bb7pulldown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaModePolicyTest {
    private static final long SHUFFLE_ACTION = 1L << 21;
    private static final long REPEAT_ACTION = 1L << 18;

    @Test public void onlyAdvertisedReadyValidModesAppear() {
        assertTrue(MediaModePolicy.available(true, SHUFFLE_ACTION,
                SHUFFLE_ACTION, MediaModePolicy.SHUFFLE_NONE));
        assertTrue(MediaModePolicy.available(true, REPEAT_ACTION,
                REPEAT_ACTION, MediaModePolicy.REPEAT_ALL));
        assertFalse(MediaModePolicy.available(false, SHUFFLE_ACTION,
                SHUFFLE_ACTION, MediaModePolicy.SHUFFLE_ALL));
        assertFalse(MediaModePolicy.available(true, 0L,
                SHUFFLE_ACTION, MediaModePolicy.SHUFFLE_NONE));
        assertFalse(MediaModePolicy.available(true, SHUFFLE_ACTION,
                SHUFFLE_ACTION, MediaModePolicy.INVALID));
    }

    @Test public void collapsedStatusOnlyAppearsForAdvertisedEnabledModes() {
        assertFalse(MediaModePolicy.showShuffleStatus(true, MediaModePolicy.SHUFFLE_NONE));
        assertFalse(MediaModePolicy.showShuffleStatus(false, MediaModePolicy.SHUFFLE_ALL));
        assertFalse(MediaModePolicy.showShuffleStatus(true, MediaModePolicy.INVALID));
        assertTrue(MediaModePolicy.showShuffleStatus(true, MediaModePolicy.SHUFFLE_ALL));

        assertFalse(MediaModePolicy.showRepeatStatus(true, MediaModePolicy.REPEAT_NONE));
        assertFalse(MediaModePolicy.showRepeatStatus(false, MediaModePolicy.REPEAT_ALL));
        assertFalse(MediaModePolicy.showRepeatStatus(true, MediaModePolicy.INVALID));
        assertTrue(MediaModePolicy.showRepeatStatus(true, MediaModePolicy.REPEAT_ONE));
        assertTrue(MediaModePolicy.showRepeatStatus(true, MediaModePolicy.REPEAT_ALL));
    }

    @Test public void shuffleTogglesOnlyNoneAndAll() {
        assertEquals(MediaModePolicy.SHUFFLE_ALL,
                MediaModePolicy.nextShuffle(MediaModePolicy.SHUFFLE_NONE));
        assertEquals(MediaModePolicy.SHUFFLE_NONE,
                MediaModePolicy.nextShuffle(MediaModePolicy.SHUFFLE_ALL));
    }

    @Test public void repeatCyclesNoneAllOneNone() {
        assertEquals(MediaModePolicy.REPEAT_ALL,
                MediaModePolicy.nextRepeat(MediaModePolicy.REPEAT_NONE));
        assertEquals(MediaModePolicy.REPEAT_ONE,
                MediaModePolicy.nextRepeat(MediaModePolicy.REPEAT_ALL));
        assertEquals(MediaModePolicy.REPEAT_NONE,
                MediaModePolicy.nextRepeat(MediaModePolicy.REPEAT_ONE));
    }

    @Test public void mediaModeNavigationNeverWrapsAndSkipsAbsentActions() {
        assertEquals(-1, MediaModePolicy.firstAvailable(false, false));
        assertEquals(MediaModePolicy.SHUFFLE,
                MediaModePolicy.firstAvailable(true, true));
        assertEquals(MediaModePolicy.REPEAT,
                MediaModePolicy.firstAvailable(false, true));
        assertEquals(MediaModePolicy.REPEAT,
                MediaModePolicy.stepMode(MediaModePolicy.SHUFFLE, 1, true, true));
        assertEquals(MediaModePolicy.REPEAT,
                MediaModePolicy.stepMode(MediaModePolicy.REPEAT, 1, true, true));
        assertEquals(MediaModePolicy.SHUFFLE,
                MediaModePolicy.stepMode(MediaModePolicy.REPEAT, -1, true, true));
        assertEquals(MediaModePolicy.REPEAT,
                MediaModePolicy.stepMode(MediaModePolicy.SHUFFLE, 1, false, true));
    }
}
