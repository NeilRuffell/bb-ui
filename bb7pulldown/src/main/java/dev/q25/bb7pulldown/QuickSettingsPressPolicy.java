package dev.q25.bb7pulldown;

/**
 * Maps BBUI press gestures to *existing native QSTile actions*.
 *
 * LineageOS 24's wifi, bt, and cell tiles have a native secondary/toggle
 * action. Their primary action shows SystemUI's details dialog; their native
 * longClick opens Android Settings and is not the desired BBUI detail action.
 *
 * Never infer toggle support from an active-state icon or the existence of a
 * dialog. Other tiles keep their native primary and long-press semantics.
 */
final class QuickSettingsPressPolicy {
    enum Action { PRIMARY, SECONDARY, LONG_PRESS }

    private QuickSettingsPressPolicy() {}

    static Action select(String spec, boolean nativeSecondaryAvailable, boolean longPress) {
        if (nativeSecondaryAvailable && hasVerifiedNativeToggleClick(spec)) {
            return longPress ? Action.PRIMARY : Action.SECONDARY;
        }
        return longPress ? Action.LONG_PRESS : Action.PRIMARY;
    }

    static boolean hasVerifiedNativeToggleClick(String spec) {
        return "wifi".equals(spec) || "bt".equals(spec) || "cell".equals(spec);
    }
}
