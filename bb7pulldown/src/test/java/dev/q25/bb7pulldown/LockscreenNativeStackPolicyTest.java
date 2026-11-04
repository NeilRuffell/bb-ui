package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LockscreenNativeStackPolicyTest {
    @Test public void keyguardHidesEntireNativeNotificationStack() {
        assertTrue(LockscreenNativeStackPolicy.hideNativeStack(
                true, LockscreenNativeStackPolicy.KEYGUARD));
    }

    @Test public void lockedShadeStillShowsNormalAndroidNotifications() {
        assertFalse(LockscreenNativeStackPolicy.hideNativeStack(
                true, LockscreenNativeStackPolicy.SHADE_LOCKED));
    }

    @Test public void unlockedShadeRestoresNativeNotifications() {
        assertFalse(LockscreenNativeStackPolicy.hideNativeStack(
                false, LockscreenNativeStackPolicy.SHADE));
    }

    @Test public void passwordBouncerRetainsLockscreenSuppression() {
        assertTrue(LockscreenNativeStackPolicy.hideNativeStack(
                true, LockscreenNativeStackPolicy.KEYGUARD));
    }

    @Test public void unknownStateFailsClosedOnlyWhenDeviceLocked() {
        assertTrue(LockscreenNativeStackPolicy.hideNativeStack(
                true, LockscreenNativeStackPolicy.UNKNOWN));
        assertFalse(LockscreenNativeStackPolicy.hideNativeStack(
                false, LockscreenNativeStackPolicy.UNKNOWN));
    }

    @Test public void reportedShadeStateDoesNotHideNativeNotificationShade() {
        assertFalse(LockscreenNativeStackPolicy.hideNativeStack(
                true, LockscreenNativeStackPolicy.SHADE));
    }
}
