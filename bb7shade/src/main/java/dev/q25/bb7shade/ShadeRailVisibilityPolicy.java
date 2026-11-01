package dev.q25.bb7shade;

/**
 * The initial Android keyguard state is sampled during SystemUI startup
 * and can be stale. When SystemUI's own KeyguardStateController is known,
 * it is the authoritative source for whether the app rail may appear.
 *
 * This is presentation-only: neither the lockscreen notification privacy
 * rules nor keyguard state is modified.
 */
final class ShadeRailVisibilityPolicy {
    private ShadeRailVisibilityPolicy() {}

    static boolean shouldShow(Boolean systemUiShowing, boolean platformLocked) {
        return systemUiShowing != null ? !systemUiShowing : !platformLocked;
    }
}
