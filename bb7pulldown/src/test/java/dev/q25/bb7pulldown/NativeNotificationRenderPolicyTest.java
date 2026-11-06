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

    @Test public void hidesOnlyDuplicateCardsOnInteractiveBb6Keyguard() {
        assertTrue(NativeNotificationRenderPolicy.shouldSuppress(false, false, true, true));
    }

    @Test public void unlockRestoresStockDrawingWithoutRemovingAnyNativeView() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(false, false, false, true));
    }

    @Test public void unlockedNativeShadeUsesOriginalOwnershipGate() {
        assertTrue(NativeNotificationRenderPolicy.shouldSuppress(true, true, false, false));
    }

    @Test public void wrongLockscreenWindowCannotSuppressAnotherStack() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(false, false, true, false));
    }

    @Test public void noPresentationPreservesNativeDrawing() {
        assertFalse(NativeNotificationRenderPolicy.shouldSuppress(false, false, false, false));
    }
}
