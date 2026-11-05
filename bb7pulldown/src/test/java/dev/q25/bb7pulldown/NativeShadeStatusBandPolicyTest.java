package dev.q25.bb7pulldown;

import static org.junit.Assert.assertArrayEquals;
import org.junit.Test;

public class NativeShadeStatusBandPolicyTest {
    @Test public void nativeShadeDoesNotDrawInsideReservedStatusBarBand() {
        assertArrayEquals(new int[] {0, 52, 720, 1440},
                NativeShadeStatusBandPolicy.clipBounds(720, 1440, 52));
    }

    @Test public void rotationOrUnusualStatusBarHeightCannotOverrunWindow() {
        assertArrayEquals(new int[] {0, 720, 1440, 720},
                NativeShadeStatusBandPolicy.clipBounds(1440, 720, 900));
    }

    @Test public void negativeInsetsAreClamped() {
        assertArrayEquals(new int[] {0, 0, 720, 1440},
                NativeShadeStatusBandPolicy.clipBounds(720, 1440, -4));
    }
}
