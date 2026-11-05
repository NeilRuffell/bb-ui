package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NativeShadeModePolicyTest {
    @Test public void nativeModeNeverCreatesSecondShadeWindow() {
        assertFalse(NativeShadeModePolicy.useLegacyWindow(true));
        assertTrue(NativeShadeModePolicy.useLegacyWindow(false));
    }

    @Test public void nativeMountRequiresReadyUnlockedSystemUi() {
        assertTrue(NativeShadeModePolicy.mayMount(true, true, false));
        assertFalse(NativeShadeModePolicy.mayMount(true, false, false));
        assertFalse(NativeShadeModePolicy.mayMount(true, true, true));
        assertFalse(NativeShadeModePolicy.mayMount(false, true, false));
    }

    @Test public void externalNativeShadeGestureOpensSingleBbHost() {
        assertTrue(NativeShadeModePolicy.openFromNative(true, true, false, false));
        assertFalse(NativeShadeModePolicy.openFromNative(true, true, true, false));
        assertFalse(NativeShadeModePolicy.openFromNative(true, false, false, false));
        assertFalse(NativeShadeModePolicy.openFromNative(true, true, false, true));
    }

    @Test public void nativeCollapseDetachesTheBbChild() {
        assertTrue(NativeShadeModePolicy.detachOnCollapse(true, false, true));
        assertFalse(NativeShadeModePolicy.detachOnCollapse(true, true, true));
        assertFalse(NativeShadeModePolicy.detachOnCollapse(true, false, false));
    }
}
