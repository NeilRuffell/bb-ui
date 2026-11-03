package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NotificationSwipePolicyTest {
    @Test public void bothHorizontalDirectionsRecognized() {
        assertTrue(NotificationSwipePolicy.isHorizontal(40, 5, 8));
        assertTrue(NotificationSwipePolicy.isHorizontal(-40, 5, 8));
    }

    @Test public void verticalScrollAndSmallMovementsAreNotSwipe() {
        assertFalse(NotificationSwipePolicy.isHorizontal(5, 40, 8));
        assertFalse(NotificationSwipePolicy.isHorizontal(9, 30, 8));
        assertFalse(NotificationSwipePolicy.isHorizontal(7, 0, 8));
    }

    @Test public void bothDirectionsDismissAfterThreshold() {
        assertTrue(NotificationSwipePolicy.shouldDismiss(81, 320, 8));
        assertTrue(NotificationSwipePolicy.shouldDismiss(-81, 320, 8));
        assertFalse(NotificationSwipePolicy.shouldDismiss(79, 320, 8));
        assertFalse(NotificationSwipePolicy.shouldDismiss(-79, 320, 8));
    }

    @Test public void zeroWidthCannotDismissAndSmallCardsStillNeedDistance() {
        assertFalse(NotificationSwipePolicy.shouldDismiss(100, 0, 8));
        assertFalse(NotificationSwipePolicy.shouldDismiss(10, 40, 8));
        assertTrue(NotificationSwipePolicy.shouldDismiss(16, 40, 8));
    }
}
