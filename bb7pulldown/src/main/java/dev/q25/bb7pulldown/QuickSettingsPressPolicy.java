package dev.q25.bb7pulldown;

/**
 * Maps BBUI press gestures to *existing native QSTile actions*.
 *
 * LineageOS 24's wifi, bt, and cell tiles have a native secondary/toggle
 * action. Their primary action opens SystemUI's details dialog; their native
 * longClick opens the relevant Android Settings page. BBUI maps short press
 * to the native toggle action and long press to the native long-click action.
 *
 * Never infer toggle support from an active-state icon or the existence of a
 * dialog. Other tiles retain their native primary and long-press semantics.
 */
final class QuickSettingsPressPolicy {
    enum Action { PRIMARY, SECONDARY, LONG_PRESS }

    private QuickSettingsPressPolicy() {}

    static Action select(String spec, boolean nativeSecondaryAvailable, boolean longPress) {
        // A long hold always delegates to the installed tile's native
        // long-click behavior (typically its Settings page), not its dialog.
        if (longPress) return Action.LONG_PRESS;
        if (nativeSecondaryAvailable && hasVerifiedNativeToggleClick(spec)) {
            return Action.SECONDARY;
        }
        return Action.PRIMARY;
    }

    static boolean hasVerifiedNativeToggleClick(String spec) {
        return "wifi".equals(spec) || "bt".equals(spec) || "cell".equals(spec);
    }
}
