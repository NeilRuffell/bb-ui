package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BB6NativeChromePolicyTest {
    @Test public void onlyInteractiveKeyguardUsesBb6Chrome() {
        assertTrue(BB6NativeChromePolicy.useBb6Composition(
                true, true, LockscreenNativeStackPolicy.KEYGUARD, false));
        assertFalse(BB6NativeChromePolicy.useBb6Composition(
                true, false, LockscreenNativeStackPolicy.KEYGUARD, false));
        assertFalse(BB6NativeChromePolicy.useBb6Composition(
                true, true, LockscreenNativeStackPolicy.SHADE_LOCKED, false));
        assertFalse(BB6NativeChromePolicy.useBb6Composition(
                false, true, LockscreenNativeStackPolicy.SHADE, false));
        assertFalse(BB6NativeChromePolicy.useBb6Composition(
                true, true, LockscreenNativeStackPolicy.KEYGUARD, true));
    }

    @Test public void suppressesOnlyDuplicateAwakeHeadsUpDuringBb6Lockscreen() {
        assertTrue(BB6NativeChromePolicy.suppressStockInterruption(
                "suppressAwakeHeadsUp", true));
        assertFalse(BB6NativeChromePolicy.suppressStockInterruption(
                "suppressAwakeHeadsUp", false));
        assertFalse(BB6NativeChromePolicy.suppressStockInterruption(
                "suppressAwakeInterruptions", true));
        assertFalse(BB6NativeChromePolicy.suppressStockInterruption(
                "suppressInterruptions", true));
    }

    @Test public void suppressesOnlyPlainDefaultSwipeInstruction() {
        String nativePrompt = "Swipe up to unlock";
        assertTrue(BB6NativeChromePolicy.redundantSwipeHint(
                true, nativePrompt, nativePrompt, false, false));
        assertFalse(BB6NativeChromePolicy.redundantSwipeHint(
                false, nativePrompt, nativePrompt, false, false));
        assertFalse(BB6NativeChromePolicy.redundantSwipeHint(
                true, "Charging rapidly", nativePrompt, false, false));
        assertFalse(BB6NativeChromePolicy.redundantSwipeHint(
                true, nativePrompt, nativePrompt, true, false));
        assertFalse(BB6NativeChromePolicy.redundantSwipeHint(
                true, nativePrompt, nativePrompt, false, true));
    }
}
