package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class QuickSettingsToggleIndicatorPolicyTest {
    @Test public void wifiUsesNativeStateWhenNoBooleanStateExists() {
        assertEquals(Boolean.TRUE, QuickSettingsToggleIndicatorPolicy.checked(
                "wifi", null, true, "android.widget.Switch", false, true));
        assertEquals(Boolean.FALSE, QuickSettingsToggleIndicatorPolicy.checked(
                "wifi", null, true, "android.widget.Switch", false, false));
    }

    @Test public void mobileDataUsesVerifiedNativeSecondaryToggle() {
        assertEquals(Boolean.TRUE, QuickSettingsToggleIndicatorPolicy.checked(
                "cell", null, true, null, false, true));
        assertEquals(Boolean.FALSE, QuickSettingsToggleIndicatorPolicy.checked(
                "cell", null, true, null, false, false));
        assertNull(QuickSettingsToggleIndicatorPolicy.checked(
                "cell", null, false, null, false, false));
    }

    @Test public void bluetoothUsesNativeBooleanEvenWhenPresentingDetails() {
        assertEquals(Boolean.TRUE, QuickSettingsToggleIndicatorPolicy.checked(
                "bt", true, true, "android.widget.Button", true, false));
        assertEquals(Boolean.FALSE, QuickSettingsToggleIndicatorPolicy.checked(
                "bt", false, true, "android.widget.Button", true, true));
    }

    @Test public void regularBooleanStateTilesRetainCheckboxes() {
        assertEquals(Boolean.TRUE, QuickSettingsToggleIndicatorPolicy.checked(
                "flashlight", true, false, "android.widget.Switch", false, true));
        assertEquals(Boolean.FALSE, QuickSettingsToggleIndicatorPolicy.checked(
                "airplane", false, false, null, false, false));
    }

    @Test public void modernThirdPartySwitchUsesNativeActivationState() {
        assertEquals(Boolean.TRUE, QuickSettingsToggleIndicatorPolicy.checked(
                "custom(com.example/.Tile)", null, false,
                "android.widget.Switch", false, true));
    }

    @Test public void dialogOnlyBooleanStateDoesNotGetMisleadingCheckbox() {
        assertNull(QuickSettingsToggleIndicatorPolicy.checked(
                "internet", false, false, "android.widget.Button", true, true));
    }

    @Test public void arbitraryMenuAndStatusOnlyTilesHaveNoCheckbox() {
        assertNull(QuickSettingsToggleIndicatorPolicy.checked(
                "alarm", null, false, "android.widget.Button", true, true));
        assertNull(QuickSettingsToggleIndicatorPolicy.checked(
                "custom(com.example/.DetailTile)", null, true, null, true, true));
    }
}
