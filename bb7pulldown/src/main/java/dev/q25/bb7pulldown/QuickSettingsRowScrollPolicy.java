package dev.q25.bb7pulldown;

/**
 * One selection step per hardware Up/Down event, with the minimum scroll
 * required to make that selected row fully visible.
 *
 * All geometry is in the ScrollView child's coordinate space. No screen
 * fractions, prefetch rows, fading-edge allowances or fixed scroll amounts.
 */
final class QuickSettingsRowScrollPolicy {
    private QuickSettingsRowScrollPolicy() {}

    static int nextIndex(int current, int count, int direction) {
        if (current < 0 || current >= count) return -1;
        if (direction > 0) return Math.min(count - 1, current + 1);
        if (direction < 0) return Math.max(0, current - 1);
        return current;
    }

    static int scrollDelta(int scrollY, int viewportTop, int viewportBottom,
            int contentBottom, int rowTop, int rowBottom) {
        if (viewportBottom <= viewportTop || rowBottom <= rowTop) return 0;
        int delta = 0;
        if (rowTop < viewportTop) {
            delta = rowTop - viewportTop;
        } else if (rowBottom > viewportBottom) {
            delta = rowBottom - viewportBottom;
        }

        // A ScrollView may not scroll past the beginning or end of its child.
        int maxScroll = Math.max(0, contentBottom - (viewportBottom - scrollY));
        return Math.max(-scrollY, Math.min(delta, maxScroll - scrollY));
    }
}
