package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NativeStatusBarVisibilityPolicyTest {
    @Test public void nativeStateUnchangedOutsideBbShade() {
        assertTrue(NativeStatusBarVisibilityPolicy.isStockShadeVisible(true, false));
        assertFalse(NativeStatusBarVisibilityPolicy.isStockShadeVisible(false, false));
    }
    @Test public void bbShadeDoesNotHideGenuineStatusBar() {
        assertFalse(NativeStatusBarVisibilityPolicy.isStockShadeVisible(true, true));
        assertFalse(NativeStatusBarVisibilityPolicy.isStockShadeVisible(false, true));
    }
}
