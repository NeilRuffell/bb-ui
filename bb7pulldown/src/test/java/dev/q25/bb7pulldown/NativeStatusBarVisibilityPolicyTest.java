package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NativeStatusBarVisibilityPolicyTest {
    @Test public void stockStatusBarAvailabilityUnchangedWithoutBbShade() {
        assertTrue(NativeStatusBarVisibilityPolicy.keepHomeStatusBarAllowed(true, false));
        assertFalse(NativeStatusBarVisibilityPolicy.keepHomeStatusBarAllowed(false, false));
    }

    @Test public void bbNativeShadeKeepsActualStatusBarAvailable() {
        assertTrue(NativeStatusBarVisibilityPolicy.keepHomeStatusBarAllowed(true, true));
        assertTrue(NativeStatusBarVisibilityPolicy.keepHomeStatusBarAllowed(false, true));
    }
}
