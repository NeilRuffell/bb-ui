package dev.q25.bb7pulldown;

/** Pure eligibility check for the Q25 native status-bar-only artwork hook. */
final class StatusBarColorIconPolicy {
    private StatusBarColorIconPolicy() {}

    static boolean eligible(String entryKey, String viewKey, String packageName,
            boolean conversationAvatar) {
        return !conversationAvatar
                && entryKey != null
                && entryKey.equals(viewKey)
                && packageName != null
                && !packageName.trim().isEmpty();
    }
}
