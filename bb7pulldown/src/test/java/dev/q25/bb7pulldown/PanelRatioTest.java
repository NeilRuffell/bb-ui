package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PanelRatioTest {
    @Test public void columnsAlwaysUseFifteenEightyFiveSplit() {
        assertEquals(100f, PanelRatio.TOTAL_WEIGHT, 0f);
        assertEquals(0.15f, PanelRatio.LEFT_WEIGHT / PanelRatio.TOTAL_WEIGHT, 0.00001f);
        assertEquals(0.85f, PanelRatio.RIGHT_WEIGHT / PanelRatio.TOTAL_WEIGHT, 0.00001f);
    }
}
