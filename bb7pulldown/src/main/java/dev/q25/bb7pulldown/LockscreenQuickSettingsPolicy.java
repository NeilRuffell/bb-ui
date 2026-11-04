package dev.q25.bb7pulldown;

/**
 * Locked Quick Settings is a presentation/security policy over the SAME
 * Android QSTileHost backend used by the unlocked BB6 connections page.
 *
 * Exactly the three currently implemented connection controls are exposed.
 * A tile can only be activated while the native interactive keyguard is
 * showing, and there is NEVER a Settings activity fallback while locked.
 */
final class LockscreenQuickSettingsPolicy {
    // Explicit separate view allowlist. Increasing the full QS COUNT in the
    // future must not silently expand the locked screen's capabilities.
    private static final int[] LOCKED_KINDS = {
            ConnectionControlPolicy.MOBILE,
            ConnectionControlPolicy.WIFI,
            ConnectionControlPolicy.BLUETOOTH
    };

    private LockscreenQuickSettingsPolicy() {}

    static int[] visibleKinds() {
        return LOCKED_KINDS.clone();
    }

    static boolean mayShow(boolean locked, boolean interactive, boolean dozing,
            boolean bouncerShowing, boolean bb6BlueprintApplied, int statusBarState) {
        return locked && interactive && !dozing && !bouncerShowing
                && bb6BlueprintApplied
                && statusBarState == LockscreenNativeStackPolicy.KEYGUARD;
    }

    static boolean isAllowedControl(int kind) {
        for (int allowed : LOCKED_KINDS) {
            if (kind == allowed) return true;
        }
        return false;
    }

    static boolean mayActivate(int kind, boolean mayShowPanel) {
        return mayShowPanel && isAllowedControl(kind);
    }

    /** Native Android QS tiles own all lock-screen security behavior. */
    static boolean mayLaunchExternalSettings(boolean locked) {
        return !locked;
    }
}
