package dev.q25.bb7pulldown;

/** Gesture thresholds shared by left and right notification-card dismissal. */
final class NotificationSwipePolicy {
    private NotificationSwipePolicy() {}

    /** Horizontal intent must beat Android touch slop and vertical displacement. */
    static boolean isHorizontal(float dx, float dy, int touchSlop) {
        return Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f;
    }

    /** Require at least a quarter-card swipe, with a minimum touch-slop distance. */
    static boolean shouldDismiss(float dx, int cardWidth, int touchSlop) {
        return cardWidth > 0
                && Math.abs(dx) >= Math.max(cardWidth * 0.25f, touchSlop * 2f);
    }
}
