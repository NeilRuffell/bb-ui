package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class QuickSettingsPressPolicyTest {
    @Test public void nativeToggleAndDetailsAreReversedForVerifiedConnectivityTiles() {
        for (String spec : new String[] {"wifi", "bt", "cell"}) {
            assertEquals(QuickSettingsPressPolicy.Action.SECONDARY,
                    QuickSettingsPressPolicy.select(spec, true, false));
            assertEquals(QuickSettingsPressPolicy.Action.PRIMARY,
                    QuickSettingsPressPolicy.select(spec, true, true));
        }
    }

    @Test public void missingSecondaryActionNeverAttemptsNativeToggle() {
        assertEquals(QuickSettingsPressPolicy.Action.PRIMARY,
                QuickSettingsPressPolicy.select("wifi", false, false));
        assertEquals(QuickSettingsPressPolicy.Action.LONG_PRESS,
                QuickSettingsPressPolicy.select("wifi", false, true));
    }

    @Test public void otherTilesRetainTheirOriginalActions() {
        for (String spec : new String[] {"internet", "flashlight", "custom(app/pkg)"}) {
            assertEquals(QuickSettingsPressPolicy.Action.PRIMARY,
                    QuickSettingsPressPolicy.select(spec, true, false));
            assertEquals(QuickSettingsPressPolicy.Action.LONG_PRESS,
                    QuickSettingsPressPolicy.select(spec, true, true));
        }
    }
}
