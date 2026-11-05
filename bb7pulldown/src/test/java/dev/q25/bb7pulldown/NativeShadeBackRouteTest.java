package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NativeShadeBackRouteTest {
    @Test public void nativeBackExitsStatusFocusBeforeTheShade() {
        assertEquals(NativeShadeBackRoute.STATUS_BAR,
                NativeShadeBackRoute.route(true, ShadeStatusNavigation.NOTIFICATIONS));
        assertEquals(NativeShadeBackRoute.STATUS_BAR,
                NativeShadeBackRoute.route(true, ShadeStatusNavigation.QUICK_SETTINGS));
    }

    @Test public void nativeBackFromNotificationPageUsesNotificationDepth() {
        assertEquals(NativeShadeBackRoute.NOTIFICATIONS,
                NativeShadeBackRoute.route(false, ShadeStatusNavigation.NOTIFICATIONS));
    }

    @Test public void nativeBackFromQuickSettingsClosesCurrentShade() {
        assertEquals(NativeShadeBackRoute.QUICK_SETTINGS,
                NativeShadeBackRoute.route(false, ShadeStatusNavigation.QUICK_SETTINGS));
    }
}
