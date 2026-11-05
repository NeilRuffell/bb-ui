package dev.q25.bb7pulldown;

/**
 * Only a real application notification on the status-bar-specific icon view
 * may substitute its app artwork. Shelf, AOD, system, and bundle icons keep
 * their original drawable.
 */
final class StatusBarAppIconPolicy {
    private StatusBarAppIconPolicy() {}

    static boolean shouldReplace(boolean isStatusBarIcon, String packageName) {
        return isStatusBarIcon && packageName != null && !packageName.trim().isEmpty();
    }
}
