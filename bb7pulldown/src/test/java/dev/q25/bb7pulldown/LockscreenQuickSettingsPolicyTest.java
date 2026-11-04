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

    @Test public void noControlCanActivateOutsideTheLockedKeyguard() {
        for (int kind = 0; kind < ConnectionControlPolicy.COUNT; kind++) {
            assertTrue(LockscreenQuickSettingsPolicy.mayActivate(kind, true));
            assertFalse(LockscreenQuickSettingsPolicy.mayActivate(kind, false));
        }
        assertFalse(LockscreenQuickSettingsPolicy.mayActivate(3, true));
    }

    @Test public void lockedScreenNeverLaunchesSettingsFallback() {
        assertFalse(LockscreenQuickSettingsPolicy.mayLaunchExternalSettings(true));
        assertTrue(LockscreenQuickSettingsPolicy.mayLaunchExternalSettings(false));
    }
}
