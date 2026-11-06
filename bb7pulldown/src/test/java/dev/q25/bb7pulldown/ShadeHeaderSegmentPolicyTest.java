package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ShadeHeaderSegmentPolicyTest {
    @Test public void touchesChooseCorrespondingPage() {
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(0, 200));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(99, 200));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(100, 200));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(199, 200));
    }

    @Test public void invalidCoordinatesDoNotSelectAnything() {
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(-1, 200));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(200, 200));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(2, 0));
    }

    @Test public void oddWidthsHaveOneExactHalfwayBoundary() {
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(100, 201));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(101, 201));
    }
}
