package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeNotificationRenderPolicyTest {
    @Test public void skipsNativeCardsOnlyInsideTheBbOwnedShadeWindow() {
        assertTrue(NativeNotificationRenderPolicy.shouldSuppress(true, true));
    }

    @Test public void keepsStockCardsWhenBbShadeIsClosed() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(false, true));
    }

    @Test public void keepsStockCardsInOtherWindowsEvenWhileBbIsOpen() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(true, false));
    }

    @Test public void keepsStockCardsWhenNeitherConditionHolds() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(false, false));
    }
}
