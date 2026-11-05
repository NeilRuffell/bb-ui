package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import android.graphics.Rect;
import org.junit.Test;

public class NativeShadeStatusBandPolicyTest {
    @Test public void nativeShadeDoesNotDrawInsideReservedStatusBarBand() {
        Rect rect = NativeShadeStatusBandPolicy.clipBounds(720, 1440, 52);
        assertEquals(0, rect.left);
        assertEquals(52, rect.top);
        assertEquals(720, rect.right);
        assertEquals(1440, rect.bottom);
    }

    @Test public void rotationOrUnusualStatusBarHeightCannotOverrunWindow() {
        Rect rect = NativeShadeStatusBandPolicy.clipBounds(1440, 720, 900);
        assertEquals(720, rect.top);
        assertEquals(720, rect.bottom);
    }

    @Test public void negativeInsetsAreClamped() {
        assertEquals(0, NativeShadeStatusBandPolicy.clipBounds(720, 1440, -4).top);
    }
}
