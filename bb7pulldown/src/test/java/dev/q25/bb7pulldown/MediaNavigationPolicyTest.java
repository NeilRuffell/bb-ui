package dev.q25.bb7pulldown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaNavigationPolicyTest {
    @Test public void downFromTransportStopsAtTransport() {
        assertEquals(MediaNavigationPolicy.TRANSPORT_CONTROLS,
                MediaNavigationPolicy.moveRow(MediaNavigationPolicy.TRANSPORT_CONTROLS, 1));
    }

    @Test public void upFromSeekBarStopsAtSeekBar() {
        assertEquals(MediaNavigationPolicy.SEEK_BAR,
                MediaNavigationPolicy.moveRow(MediaNavigationPolicy.SEEK_BAR, -1));
    }

    @Test public void transportAndSeekMoveToEachOtherOnce() {
        assertEquals(MediaNavigationPolicy.SEEK_BAR,
                MediaNavigationPolicy.moveRow(MediaNavigationPolicy.TRANSPORT_CONTROLS, -1));
        assertEquals(MediaNavigationPolicy.TRANSPORT_CONTROLS,
                MediaNavigationPolicy.moveRow(MediaNavigationPolicy.SEEK_BAR, 1));
    }

    @Test public void sessionNavigationStopsAtBothEnds() {
        assertEquals(0, MediaNavigationPolicy.moveSession(0, -1, 3));
        assertEquals(1, MediaNavigationPolicy.moveSession(0, 1, 3));
        assertEquals(2, MediaNavigationPolicy.moveSession(2, 1, 3));
        assertEquals(1, MediaNavigationPolicy.moveSession(2, -1, 3));
    }

    @Test public void singleOrMissingSessionsCannotWrap() {
        assertEquals(0, MediaNavigationPolicy.moveSession(0, 1, 1));
        assertEquals(0, MediaNavigationPolicy.moveSession(0, -1, 1));
        assertEquals(-1, MediaNavigationPolicy.moveSession(0, 1, 0));
    }
}
