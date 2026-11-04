package dev.q25.bb7pulldown;

import java.util.Set;

/**
 * Locked Quick Settings is a presentation/security policy over the SAME
 * Android QSHostAdapter/QSTile backend used by the unlocked native tile page.
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

    /**
     * A row must select only the already-installed native tile corresponding
     * to its locked presentation category. The category AND the tile spec are
     * checked again at activation, not just when the row was rendered.
     * Android 17 supports combined "internet" and split "cell"/"wifi".
     */
    static boolean mayActivateSpec(int kind, String spec, boolean mayShowPanel) {
        if (!mayActivate(kind, mayShowPanel) || spec == null) return false;
        switch (kind) {
            case ConnectionControlPolicy.MOBILE:
                return "cell".equals(spec) || "internet".equals(spec);
            case ConnectionControlPolicy.WIFI:
                return "wifi".equals(spec) || "internet".equals(spec);
            case ConnectionControlPolicy.BLUETOOTH:
                return "bt".equals(spec);
            default:
                return false;
        }
    }

    /**
     * Choose only a currently installed, available native tile. The mobile
     * and Wi-Fi labels may map to the same combined Internet tile.
     * This is a presentation mapping, not a radio state implementation.
     */
    static String selectNativeSpec(int kind, Set<String> availableSpecs) {
        if (availableSpecs == null) return null;
        switch (kind) {
            case ConnectionControlPolicy.MOBILE:
                if (availableSpecs.contains("cell")) return "cell";
                return availableSpecs.contains("internet") ? "internet" : null;
            case ConnectionControlPolicy.WIFI:
                if (availableSpecs.contains("wifi")) return "wifi";
                return availableSpecs.contains("internet") ? "internet" : null;
            case ConnectionControlPolicy.BLUETOOTH:
                return availableSpecs.contains("bt") ? "bt" : null;
            default:
                return null;
        }
    }

    /** Native Android QS tiles own all lock-screen security behavior. */
    static boolean mayLaunchExternalSettings(boolean locked) {
        return !locked;
    }
}
