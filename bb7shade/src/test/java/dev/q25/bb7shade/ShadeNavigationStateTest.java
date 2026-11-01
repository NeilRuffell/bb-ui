package dev.q25.bb7shade;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ShadeNavigationStateTest {
    @Test public void defaultsToNotificationAppRail() {
        ShadeNavigationState state = new ShadeNavigationState();
        assertEquals(ShadeNavigationState.Page.NOTIFICATIONS, state.page());
        assertEquals(ShadeNavigationState.Region.APP_RAIL, state.region());
        assertNull(state.selectedPackage());
    }

    @Test public void traversesAppsWithoutWrappingOrChangingPages() {
        ShadeNavigationState state = new ShadeNavigationState();
        state.updatePackages(Arrays.asList("org.alpha", "org.beta"));
        assertTrue(state.moveAppSelection(1));
        assertEquals("org.alpha", state.selectedPackage());
        assertTrue(state.moveAppSelection(1));
        assertEquals("org.beta", state.selectedPackage());
        assertFalse(state.moveAppSelection(1));
        assertEquals(ShadeNavigationState.Page.NOTIFICATIONS, state.page());
        assertTrue(state.moveAppSelection(-2));
        assertNull(state.selectedPackage());
    }

    @Test public void keepsSelectionAcrossAppListRefresh() {
        ShadeNavigationState state = new ShadeNavigationState();
        state.updatePackages(Arrays.asList("org.alpha", "org.beta"));
        state.selectPackage("org.beta");
        state.enterNotificationPane();
        state.updatePackages(Arrays.asList("org.beta", "org.gamma"));
        assertEquals("org.beta", state.selectedPackage());
        assertEquals(ShadeNavigationState.Region.NOTIFICATION_PANE, state.region());
    }

    @Test public void missingAppResetsSelectionAndReturnsToRail() {
        ShadeNavigationState state = new ShadeNavigationState();
        state.updatePackages(Arrays.asList("org.alpha", "org.beta"));
        state.selectPackage("org.beta");
        state.enterNotificationPane();
        state.updatePackages(Collections.singletonList("org.alpha"));
        assertNull(state.selectedPackage());
        assertEquals(ShadeNavigationState.Region.APP_RAIL, state.region());
    }

    @Test public void pageSwitchNeverHappensDuringAppListTraversal() {
        ShadeNavigationState state = new ShadeNavigationState();
        state.updatePackages(Collections.singletonList("org.alpha"));
        state.enterNotificationPane();
        assertFalse(state.moveAppSelection(1));
        assertEquals(ShadeNavigationState.Page.NOTIFICATIONS, state.page());
        state.showQuickSettings();
        assertEquals(ShadeNavigationState.Region.QUICK_SETTINGS, state.region());
        assertFalse(state.moveAppSelection(-1));
        state.showNotifications();
        assertEquals(ShadeNavigationState.Region.APP_RAIL, state.region());
    }

    @Test(expected = IllegalArgumentException.class)
    public void disallowsSelectionOfNonexistentApp() {
        ShadeNavigationState state = new ShadeNavigationState();
        state.selectPackage("org.absent");
    }
}
