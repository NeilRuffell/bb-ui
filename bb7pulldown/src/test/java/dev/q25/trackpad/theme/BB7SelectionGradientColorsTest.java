package dev.q25.trackpad.theme;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class BB7SelectionGradientColorsTest {
    @Test public void darkModePreservesOriginalThreeStopGradient() {
        assertArrayEquals(new int[]{
                0xff459fd9, 0xff3285c5, 0xff2368aa
        }, BB7SelectionGradientColors.colors(true));
    }

    @Test public void lightModeUsesEverySampledBbos6Shade() {
        assertArrayEquals(new int[]{
                0xff088eef, 0xff088aef, 0xff0886ef, 0xff0882ef,
                0xff087de7, 0xff0879e7, 0xff1075e7, 0xff1071e7,
                0xff106dde, 0xff1069de, 0xff1065de
        }, BB7SelectionGradientColors.colors(false));
    }

    @Test public void callerCannotModifySharedPalette() {
        int[] one = BB7SelectionGradientColors.colors(false);
        one[0] = 0;
        assertEquals(0xff088eef, BB7SelectionGradientColors.colors(false)[0]);

        int[] dark = BB7SelectionGradientColors.colors(true);
        dark[0] = 0;
        assertEquals(0xff459fd9, BB7SelectionGradientColors.colors(true)[0]);
    }
}
