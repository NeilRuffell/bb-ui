package dev.q25.trackpad;

import static org.junit.Assert.*;
import org.junit.Test;

/** Quickstep keeps the original TaskView; these are only icon cell coordinates. */
public final class RecentsIconGridLayoutTest {
    @Test public void sixteenIconsFitOnePage() {
        RecentsIconGridLayout grid = RecentsIconGridLayout.calculate(
                720, 720, 2f, 0, 16);
        assertNotNull(grid);
        assertEquals(4, RecentsIconGridLayout.COLUMNS);
        assertEquals(4, RecentsIconGridLayout.ROWS);
        assertEquals(1, grid.pageCount);
        assertEquals(0, grid.scrollForTask(15));
        assertEquals(grid.x(0), grid.x(4));
        assertEquals(grid.y(0), grid.y(1));
        assertTrue(grid.x(1) > grid.x(0));
        assertTrue(grid.y(4) > grid.y(0));
        assertEquals(0, grid.lastTaskScroll());
    }

    @Test public void seventeenthIconUsesNativeNextPage() {
        RecentsIconGridLayout grid = RecentsIconGridLayout.calculate(
                720, 720, 2f, 0, 17);
        assertNotNull(grid);
        assertEquals(2, grid.pageCount);
        assertEquals(720, grid.scrollForTask(16));
        assertEquals(720, grid.lastTaskScroll());
        assertTrue(grid.x(16) > grid.x(0));
    }

    @Test public void keyboardMovementFollowsIconRows() {
        RecentsIconGridLayout grid = RecentsIconGridLayout.calculate(
                720, 720, 2f, 0, 20);
        assertNotNull(grid);
        assertEquals(1, grid.nextIndex(0, 3)); // right
        assertEquals(0, grid.nextIndex(0, 2)); // left blocked
        assertEquals(4, grid.nextIndex(0, 1)); // down
        assertEquals(0, grid.nextIndex(4, 0)); // up
        assertEquals(3, grid.nextIndex(3, 3)); // right edge blocked
        assertEquals(16, grid.nextIndex(12, 1)); // next page
        assertEquals(16, grid.nextIndex(16, 1)); // bottom clamp
    }

    @Test public void smallViewportsFailClosed() {
        assertNull(RecentsIconGridLayout.calculate(100, 120, 1f, 0, 4));
        assertNull(RecentsIconGridLayout.calculate(0, 640, 1f, 0, 1));
        assertNull(RecentsIconGridLayout.calculate(480, 640, 0, 0, 1));
    }

    @Test public void emptyTasksHaveNoNegativeScroll() {
        RecentsIconGridLayout grid = RecentsIconGridLayout.calculate(
                720, 720, 2f, 0, 0);
        assertNotNull(grid);
        assertEquals(0, grid.pageCount);
        assertEquals(0, grid.lastTaskScroll());
        assertEquals(-1, grid.nextIndex(0, 1));
    }
}
