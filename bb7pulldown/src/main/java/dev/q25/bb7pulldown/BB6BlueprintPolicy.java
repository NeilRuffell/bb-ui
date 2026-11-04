package dev.q25.bb7pulldown;

/** Plain-Java, testable selection and composition policy for Q25 SystemUI. */
final class BB6BlueprintPolicy {
    static final String ID = "bb6-interactive";
    static final String DEFAULT = "default";

    private BB6BlueprintPolicy() {}

    static boolean omitStockSection(String simpleName) {
        return "ClockSection".equals(simpleName)
                || "SmartspaceSection".equals(simpleName)
                || "KeyguardSliceViewSection".equals(simpleName)
                || "AodNotificationIconsSection".equals(simpleName)
                || "AodPromotedNotificationSection".equals(simpleName)
                || "DefaultNotificationStackScrollLayoutSection".equals(simpleName);
    }

    static boolean enabledForState(boolean locked, boolean interactive,
            boolean dozing, boolean bouncer, int nativeState) {
        return locked && interactive && !dozing && !bouncer
                && nativeState == LockscreenNativeStackPolicy.KEYGUARD;
    }
}
