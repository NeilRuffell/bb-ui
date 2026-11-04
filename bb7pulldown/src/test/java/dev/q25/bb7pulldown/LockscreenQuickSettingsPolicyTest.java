package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LockscreenQuickSettingsPolicyTest {
    @Test public void onlyInteractiveBb6KeyguardMayShow() {
        int keyguard = LockscreenNativeStackPolicy.KEYGUARD;
        assertTrue(LockscreenQuickSettingsPolicy.mayShow(
                true, true, false, false, true, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                false, true, false, false, true, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, false, false, false, true, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, true, true, false, true, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, true, false, true, true, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, true, false, false, false, keyguard));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, true, false, false, true,
                LockscreenNativeStackPolicy.SHADE_LOCKED));
        assertFalse(LockscreenQuickSettingsPolicy.mayShow(
                true, true, false, false, true,
                LockscreenNativeStackPolicy.UNKNOWN));
    }

    @Test public void lockedControlsAreExactlyTheCurrentThree() {
        assertTrue(LockscreenQuickSettingsPolicy.isAllowedControl(
                ConnectionControlPolicy.MOBILE));
        assertTrue(LockscreenQuickSettingsPolicy.isAllowedControl(
                ConnectionControlPolicy.WIFI));
        assertTrue(LockscreenQuickSettingsPolicy.isAllowedControl(
                ConnectionControlPolicy.BLUETOOTH));
        assertFalse(LockscreenQuickSettingsPolicy.isAllowedControl(-1));
        assertFalse(LockscreenQuickSettingsPolicy.isAllowedControl(3));
        assertFalse(LockscreenQuickSettingsPolicy.isAllowedControl(100));
    }

    @Test public void lockedControlsArePinnedAndReturnDefensiveCopies() {
        int[] first = LockscreenQuickSettingsPolicy.visibleKinds();
        assertTrue(first.length == 3);
        assertTrue(first[0] == ConnectionControlPolicy.MOBILE);
        assertTrue(first[1] == ConnectionControlPolicy.WIFI);
        assertTrue(first[2] == ConnectionControlPolicy.BLUETOOTH);
        first[0] = -1;
        assertTrue(LockscreenQuickSettingsPolicy.visibleKinds()[0]
                == ConnectionControlPolicy.MOBILE);
    }

    @Test public void noControlCanActivateOutsideTheLockedKeyguard() {
        for (int kind = 0; kind < ConnectionControlPolicy.COUNT; kind++) {
            assertTrue(LockscreenQuickSettingsPolicy.mayActivate(kind, true));
            assertFalse(LockscreenQuickSettingsPolicy.mayActivate(kind, false));
        }
        assertFalse(LockscreenQuickSettingsPolicy.mayActivate(3, true));
    }

    @Test public void nativeSpecsAreConstrainedToTheirLockedRows() {
        assertTrue(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.MOBILE, "internet", true));
        assertTrue(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.MOBILE, "cell", true));
        assertTrue(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.WIFI, "internet", true));
        assertTrue(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.WIFI, "wifi", true));
        assertTrue(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.BLUETOOTH, "bt", true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.MOBILE, "bt", true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.WIFI, "flashlight", true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.BLUETOOTH, "internet", true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.BLUETOOTH, "custom(pkg/.Service)", true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.WIFI, "wifi", false));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                ConnectionControlPolicy.WIFI, null, true));
        assertFalse(LockscreenQuickSettingsPolicy.mayActivateSpec(
                -1, "internet", true));
    }

    @Test public void lockedScreenNeverLaunchesSettingsFallback() {
        assertFalse(LockscreenQuickSettingsPolicy.mayLaunchExternalSettings(true));
        assertTrue(LockscreenQuickSettingsPolicy.mayLaunchExternalSettings(false));
    }
}
