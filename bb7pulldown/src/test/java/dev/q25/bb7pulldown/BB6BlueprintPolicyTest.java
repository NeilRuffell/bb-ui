package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BB6BlueprintPolicyTest {
    @Test public void removesNativeDuplicateSectionsAtCompositionTime() {
        assertTrue(BB6BlueprintPolicy.omitStockSection("ClockSection"));
        assertTrue(BB6BlueprintPolicy.omitStockSection("SmartspaceSection"));
        assertTrue(BB6BlueprintPolicy.omitStockSection("KeyguardSliceViewSection"));
        assertTrue(BB6BlueprintPolicy.omitStockSection("AodNotificationIconsSection"));
        assertTrue(BB6BlueprintPolicy.omitStockSection("AodPromotedNotificationSection"));
        assertTrue(BB6BlueprintPolicy.omitStockSection("DefaultNotificationStackScrollLayoutSection"));
    }

    @Test public void preservesNativeCarrierStatusAndAuthentication() {
        assertFalse(BB6BlueprintPolicy.omitStockSection("DefaultStatusBarSection"));
        assertFalse(BB6BlueprintPolicy.omitStockSection("DefaultDeviceEntrySection"));
        assertFalse(BB6BlueprintPolicy.omitStockSection("DefaultIndicationAreaSection"));
        assertFalse(BB6BlueprintPolicy.omitStockSection("AodBurnInSection"));
        assertFalse(BB6BlueprintPolicy.omitStockSection("AccessibilityActionsSection"));
    }

    @Test public void activatesOnlyForAwakeNonBouncerNativeKeyguard() {
        assertTrue(BB6BlueprintPolicy.enabledForState(
                true, true, false, false, LockscreenNativeStackPolicy.KEYGUARD));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                true, false, false, false, LockscreenNativeStackPolicy.KEYGUARD));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                true, true, true, false, LockscreenNativeStackPolicy.KEYGUARD));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                true, true, false, true, LockscreenNativeStackPolicy.KEYGUARD));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                true, true, false, false, LockscreenNativeStackPolicy.SHADE_LOCKED));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                false, true, false, false, LockscreenNativeStackPolicy.SHADE));
        assertFalse(BB6BlueprintPolicy.enabledForState(
                true, true, false, false, LockscreenNativeStackPolicy.UNKNOWN));
    }
}
