package dev.q25.bb7shade;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShadeRailVisibilityPolicyTest {
    @Test public void hidesWhenSystemUiReportsKeyguard() {
        assertFalse(ShadeRailVisibilityPolicy.shouldShow(true, false));
        assertFalse(ShadeRailVisibilityPolicy.shouldShow(true, true));
    }

    @Test public void unlockSignalOverridesStaleStartupLockState() {
        assertTrue(ShadeRailVisibilityPolicy.shouldShow(false, true));
        assertTrue(ShadeRailVisibilityPolicy.shouldShow(false, false));
    }

    @Test public void unknownSystemUiStateUsesConservativePlatformFallback() {
        assertFalse(ShadeRailVisibilityPolicy.shouldShow(null, true));
        assertTrue(ShadeRailVisibilityPolicy.shouldShow(null, false));
    }
}
