package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ShadeHeaderSegmentPolicyTest {
    @Test public void touchesChooseCorrespondingVerticalRow() {
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(0, 96));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(47, 96));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(48, 96));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(95, 96));
    }

    @Test public void invalidCoordinatesDoNotSelectAnything() {
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(-1, 96));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(96, 96));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(2, 0));
    }

    @Test public void oddHeightsHaveOneHalfwayBoundary() {
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(100, 201));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(101, 201));
    }
}
