package dev.q25.bb7pulldown;

/** Pure input arbitration and touch-release behaviour for the native shade. */
final class NativeShadeDragPolicy {
    private static final float SETTLE_THRESHOLD = 0.35f;
    private static final float FLING_SPEED_PX_PER_SECOND = 550f;

    private NativeShadeDragPolicy() {}

    static float fraction(float pixelDelta, float travel) {
        if (travel <= 0f) return 0f;
        return Math.max(0f, Math.min(1f, pixelDelta / travel));
    }

    static boolean isUpwardDrag(float dx, float dy, float touchSlop) {
        return dy < -Math.max(1f, touchSlop) && -dy > Math.abs(dx);
    }

    static boolean shouldOpen(float progress, float velocityY, boolean canceled) {
        if (canceled) return false;
        if (velocityY >= FLING_SPEED_PX_PER_SECOND) return true;
        if (velocityY <= -FLING_SPEED_PX_PER_SECOND) return false;
        return progress >= SETTLE_THRESHOLD;
    }

    static boolean shouldClose(float progress, float velocityY, boolean canceled) {
        if (canceled) return false;
        if (velocityY <= -FLING_SPEED_PX_PER_SECOND) return true;
        if (velocityY >= FLING_SPEED_PX_PER_SECOND) return false;
        return progress >= SETTLE_THRESHOLD;
    }
}
