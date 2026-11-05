package dev.q25.bb7pulldown;

/** Pure off-screen travel policy for the existing native shade root. */
final class NativeShadeSlidePolicy {
    private NativeShadeSlidePolicy() {}

    /** Ensure the full native window leaves the display before collapsing it. */
    static int travelDistance(int measuredWindowHeight, int displayHeight) {
        return Math.max(1, Math.max(measuredWindowHeight, displayHeight));
    }

    /** Preserve non-zero native translations rather than assuming zero. */
    static float hiddenTranslation(float originalTranslation, int distance) {
        return originalTranslation - Math.max(1, distance);
    }
}
