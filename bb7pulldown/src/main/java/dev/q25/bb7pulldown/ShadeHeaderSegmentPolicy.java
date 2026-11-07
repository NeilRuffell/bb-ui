package dev.q25.bb7pulldown;

/** Pure Y-axis hit testing for the two rows of the transient page popup. */
final class ShadeHeaderSegmentPolicy {
    private ShadeHeaderSegmentPolicy() {}

    static int zoneAt(float y, int height) {
        if (height <= 0 || y < 0f || y >= height) return ShadeStatusNavigation.NONE;
        return y < height / 2f
                ? ShadeStatusNavigation.NOTIFICATIONS : ShadeStatusNavigation.QUICK_SETTINGS;
    }
}
