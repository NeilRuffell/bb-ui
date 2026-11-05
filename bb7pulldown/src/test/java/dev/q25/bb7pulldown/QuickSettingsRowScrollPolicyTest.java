package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure row-navigation/viewport geometry: independent of Android focus timing. */
public final class QuickSettingsRowScrollPolicyTest {
    @Test public void hardwareNavigationAlwaysMovesOneRowWithoutSkipping() {
        assertEquals(1, QuickSettingsRowScrollPolicy.nextIndex(0, 20, 1));
        assertEquals(2, QuickSettingsRowScrollPolicy.nextIndex(1, 20, 1));
        assertEquals(1, QuickSettingsRowScrollPolicy.nextIndex(2, 20, -1));
        assertEquals(0, QuickSettingsRowScrollPolicy.nextIndex(0, 20, -1));
        assertEquals(19, QuickSettingsRowScrollPolicy.nextIndex(19, 20, 1));
        assertEquals(-1, QuickSettingsRowScrollPolicy.nextIndex(-1, 20, 1));
    }

    @Test public void visibleRowsDoNotMoveViewport() {
        assertEquals(0, QuickSettingsRowScrollPolicy.scrollDelta(
                0, 0, 220, 1200, 56, 111));
        assertEquals(0, QuickSettingsRowScrollPolicy.scrollDelta(
                0, 0, 220, 1200, 165, 220));
    }

    @Test public void descendingBeyondViewportRevealsExactlySelectedRow() {
        // 55px rows separated by 1px. The next row first clips by 3px.
        assertEquals(3, QuickSettingsRowScrollPolicy.scrollDelta(
                0, 0, 220, 1200, 168, 223));
        // Subsequent selections cross the viewport one row at a time.
        assertEquals(56, QuickSettingsRowScrollPolicy.scrollDelta(
                3, 3, 223, 1200, 224, 279));
        assertEquals(56, QuickSettingsRowScrollPolicy.scrollDelta(
                59, 59, 279, 1200, 280, 335));
    }

    @Test public void ascendingRevealsOnlyTheSelectedRow() {
        assertEquals(-56, QuickSettingsRowScrollPolicy.scrollDelta(
                112, 112, 332, 1200, 56, 111));
        assertEquals(-56, QuickSettingsRowScrollPolicy.scrollDelta(
                56, 56, 276, 1200, 0, 55));
    }

    @Test public void scrollStopsAtContentBoundaries() {
        assertEquals(-5, QuickSettingsRowScrollPolicy.scrollDelta(
                5, 5, 225, 1200, -40, 15));
        assertEquals(2, QuickSettingsRowScrollPolicy.scrollDelta(
                778, 778, 998, 1000, 990, 1050));
    }

    @Test public void invalidViewportDoesNotScroll() {
        assertEquals(0, QuickSettingsRowScrollPolicy.scrollDelta(
                0, 0, 0, 1200, 0, 55));
    }
}
