package dev.q25.bb7pulldown;

/** Pure hit testing for two equal-width, currently unlabeled header segments. */
final class ShadeHeaderSegmentPolicy {
    private ShadeHeaderSegmentPolicy() {}

    static int zoneAt(float x, int width) {
        if (width <= 0 || x < 0f || x >= width) return ShadeStatusNavigation.NONE;
        return x < width / 2f
                ? ShadeStatusNavigation.NOTIFICATIONS : ShadeStatusNavigation.QUICK_SETTINGS;
    }
}
