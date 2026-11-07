package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ShadeHeaderSegmentPolicyTest {
    @Test public void threeRowsWhenNoAppToClose() {
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(0, 144, false));
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(47, 144, false));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(48, 144, false));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(95, 144, false));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(96, 144, false));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(143, 144, false));
    }

    @Test public void fourRowsWhenForegroundAppIsEligible() {
        assertEquals(ShadeStatusNavigation.CLOSE_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(0, 192, true));
        assertEquals(ShadeStatusNavigation.CLOSE_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(47, 192, true));
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(48, 192, true));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(96, 192, true));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(144, 192, true));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(191, 192, true));
    }

    @Test public void invalidCoordinatesDoNotSelectAnything() {
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(-1, 144, false));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(144, 144, false));
        assertEquals(ShadeStatusNavigation.NONE,
                ShadeHeaderSegmentPolicy.zoneAt(2, 0, true));
    }

    @Test public void oddHeightsStillPartitionRowsExactly() {
        assertEquals(ShadeStatusNavigation.SWITCH_APPLICATION,
                ShadeHeaderSegmentPolicy.zoneAt(48, 145, false));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(49, 145, false));
        assertEquals(ShadeStatusNavigation.NOTIFICATIONS,
                ShadeHeaderSegmentPolicy.zoneAt(96, 145, false));
        assertEquals(ShadeStatusNavigation.QUICK_SETTINGS,
                ShadeHeaderSegmentPolicy.zoneAt(97, 145, false));
    }
}
