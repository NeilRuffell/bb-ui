package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NativeShadeSlidePolicyTest {
    @Test public void usesDisplayHeightWhenNativeWindowNotYetMeasured() {
        assertEquals(2400, NativeShadeSlidePolicy.travelDistance(0, 2400));
    }

    @Test public void usesFullWindowHeightWhenLargerThanDisplay() {
        assertEquals(2500, NativeShadeSlidePolicy.travelDistance(2500, 2300));
    }

    @Test public void neverChoosesZeroTravel() {
        assertEquals(1, NativeShadeSlidePolicy.travelDistance(0, 0));
    }

    @Test public void keepsNativeTranslationAsReference() {
        assertEquals(-2420f, NativeShadeSlidePolicy.hiddenTranslation(-20f, 2400), 0f);
    }
}
