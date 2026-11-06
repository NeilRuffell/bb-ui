package dev.q25.bb7pulldown;

import java.util.List;

/**
 * Display-only counts for the shade's left column.
 *
 * Use the same filtering rules as the right pane. Group summaries must not be
 * counted twice and represented silent media notifications must already be
 * excluded by the caller's visible-notification snapshot.
 */
final class NotificationCategoryCounts {
    private NotificationCategoryCounts() {}

    static int countFor(List<NotificationItem> visible, FavoritesRules favorites,
            String category) {
        return NotificationShadeModePolicy.select(visible, favorites, category).size();
    }
}
