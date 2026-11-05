package dev.q25.bb7pulldown;

/**
 * Maps native QSTile state metadata to an optional BBOS6 checkbox.
 *
 * BooleanState is used by many toggles, but not exclusively: the LineageOS
 * Internet dialog tile also uses BooleanState. Conversely, modern Wi-Fi and
 * mobile data tiles use regular State despite exposing native toggle actions.
 *
 * The checkbox is present only when a native toggle is identified by
 * accessibility semantics, a verified secondary toggle action, or a native
 * BooleanState that is not an expand-only/dialog presentation.
 *
 * Return null for action-only/dialog tiles, false for toggles that are off,
 * and true for toggles that are on.
 */
final class QuickSettingsToggleIndicatorPolicy {
    private QuickSettingsToggleIndicatorPolicy() {}

    static Boolean checked(String spec, Boolean nativeBooleanValue,
            boolean hasSecondaryAction, String expandedAccessibilityClassName,
            boolean forceExpandIcon, boolean stateActive) {
        boolean toggleAccessibility = "android.widget.Switch".equals(
                expandedAccessibilityClassName)
                || "android.widget.CheckBox".equals(expandedAccessibilityClassName)
                || "android.widget.ToggleButton".equals(expandedAccessibilityClassName);
        boolean verifiedToggleAction = hasSecondaryAction
                && QuickSettingsPressPolicy.hasVerifiedNativeToggleClick(spec);

        // The native BooleanState.value is more precise than ACTIVE/INACTIVE,
        // including while a toggle is transitioning.
        if (nativeBooleanValue != null) {
            if (toggleAccessibility || verifiedToggleAction || !forceExpandIcon) {
                return nativeBooleanValue;
            }
            return null;
        }

        // Modern tiles such as Wi-Fi and mobile data advertise toggle
        // semantics/actions but do not have a BooleanState.value field.
        if (toggleAccessibility || verifiedToggleAction) return stateActive;
        return null;
    }
}
