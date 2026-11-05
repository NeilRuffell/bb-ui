package dev.q25.bb7pulldown;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MediaSeekPolicyTest {
    @Test public void reportsPausedPositionWithoutExtrapolation() {
        assertEquals(20000L, MediaSeekPolicy.positionAt(
                20000L, 180000L, 1000L, 1f, false, 10000L));
    }

    @Test public void projectsPlayingPositionFromAndroidElapsedRealtime() {
        assertEquals(23000L, MediaSeekPolicy.positionAt(
                20000L, 180000L, 1000L, 1f, true, 4000L));
        assertEquals(26000L, MediaSeekPolicy.positionAt(
                20000L, 180000L, 1000L, 2f, true, 4000L));
    }

    @Test public void capsProgressAtDurationAndHandlesUnknownDuration() {
        assertEquals(60000L, MediaSeekPolicy.positionAt(
                59000L, 60000L, 1000L, 1f, true, 8000L));
        assertEquals(0, MediaSeekPolicy.toProgress(10000L, 0L));
        assertEquals(0L, MediaSeekPolicy.fromProgress(500, 0L));
        assertEquals(1000, MediaSeekPolicy.toProgress(500000L, 60000L));
        assertEquals(60000L, MediaSeekPolicy.fromProgress(1000, 60000L));
    }

    @Test public void convertsSeekBarProgressWithoutOverflow() {
        long longDuration = 12_000_000_000L;
        assertEquals(6_000_000_000L, MediaSeekPolicy.fromProgress(500, longDuration));
        assertEquals(500, MediaSeekPolicy.toProgress(6_000_000_000L, longDuration));
    }

    @Test public void seeksFiveSecondsWithoutCrossingMediaBoundaries() {
        assertEquals(15000L, MediaSeekPolicy.seekStep(10000L, 60000L, 1));
        assertEquals(5000L, MediaSeekPolicy.seekStep(10000L, 60000L, -1));
        assertEquals(0L, MediaSeekPolicy.seekStep(1000L, 60000L, -1));
        assertEquals(60000L, MediaSeekPolicy.seekStep(59000L, 60000L, 1));
        assertEquals(0L, MediaSeekPolicy.seekStep(10000L, 0L, 1));
    }

    @Test public void formatsHoursAndMinutesAsMediaTimestamps() {
        assertEquals("0:00", MediaSeekPolicy.formatTime(-1L));
        assertEquals("3:07", MediaSeekPolicy.formatTime(187000L));
        assertEquals("1:01:05", MediaSeekPolicy.formatTime(3665000L));
    }
}
