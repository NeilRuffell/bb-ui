package dev.q25.bb7pulldown;

/** Hit test for the vertically stacked, conditionally sized application menu. */
final class ShadeHeaderSegmentPolicy {
    private ShadeHeaderSegmentPolicy() {}

    static int zoneAt(float y, int height, boolean closeAvailable) {
        if (height <= 0 || y < 0f || y >= height) return ShadeStatusNavigation.NONE;
        int visible = closeAvailable ? 4 : 3;
        int position = Math.min(visible - 1, (int) (y * visible / height));
        if (closeAvailable) {
            switch (position) {
                case 0: return ShadeStatusNavigation.CLOSE_APPLICATION;
                case 1: return ShadeStatusNavigation.SWITCH_APPLICATION;
                case 2: return ShadeStatusNavigation.NOTIFICATIONS;
                default: return ShadeStatusNavigation.QUICK_SETTINGS;
            }
        }
        switch (position) {
            case 0: return ShadeStatusNavigation.SWITCH_APPLICATION;
            case 1: return ShadeStatusNavigation.NOTIFICATIONS;
            default: return ShadeStatusNavigation.QUICK_SETTINGS;
        }
    }
}
