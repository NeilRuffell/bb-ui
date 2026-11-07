package dev.q25.trackpad;

import static org.junit.Assert.*;
import org.junit.Test;

/** Pure layout policy: TaskView contents and lifecycle are owned by Quickstep. */
public final class RecentsIconGridLayoutTest {
    @Test public void sixTasksShareOneNativePage() {
        RecentsIconGridLayout layout = RecentsIconGridLayout.calculate(
                480, 640, 1f, 0, 6);
        assertNotNull(layout);
        assertEquals(1, layout.pageCount);
        assertEquals(0, layout.scrollForTask(5));
        assertEquals(layout.x(0), layout.x(3));
        assertTrue(layout.y(3) > layout.y(0));
        assertTrue(layout.x(1) > layout.x(0));
    }

    @Test public void seventhTaskBeginsNextNativePage() {
        RecentsIconGridLayout layout = RecentsIconGridLayout.calculate(
                480, 640, 1f, 12, 7);
        assertNotNull(layout);
        assertEquals(2, layout.pageCount);
        assertEquals(480, layout.scrollForTask(6));
        assertEquals(480, layout.lastTaskScroll());
        assertTrue(layout.x(6) > layout.x(0));
    }

    @Test public void keyboardMovementRetainsGridGeometry() {
        RecentsIconGridLayout layout = RecentsIconGridLayout.calculate(
                480, 640, 1f, 0, 8);
        assertEquals(1, layout.nextIndex(0, 3)); // right
        assertEquals(0, layout.nextIndex(0, 2)); // left blocked
        assertEquals(3, layout.nextIndex(0, 1)); // down
        assertEquals(0, layout.nextIndex(3, 0)); // up
        assertEquals(2, layout.nextIndex(2, 3)); // right edge blocked
        assertEquals(6, layout.nextIndex(3, 1)); // next page
        assertEquals(6, layout.nextIndex(6, 1)); // no further task
    }

    @Test public void tooSmallViewportFailsClosed() {
        assertNull(RecentsIconGridLayout.calculate(100, 120, 1f, 0, 4));
        assertNull(RecentsIconGridLayout.calculate(0, 640, 1f, 0, 1));
        assertNull(RecentsIconGridLayout.calculate(480, 640, 0, 0, 1));
    }

    @Test public void emptyTasksNeverHaveNegativeScroll() {
        RecentsIconGridLayout layout = RecentsIconGridLayout.calculate(
                480, 640, 1f, 0, 0);
        assertEquals(0, layout.pageCount);
        assertEquals(0, layout.lastTaskScroll());
        assertEquals(-1, layout.nextIndex(0, 1));
    }
}
