package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Presentation-only categories: alerting apps first, then one shared Silent entry. */
final class NotificationCategoryPolicy {
    // An invalid Android package name prevents a collision with real app identities.
    static final String SILENT_ID = "@bbui:all-silent";
    static final String FAVORITES_ID = "@bbui:favorites";

    private NotificationCategoryPolicy() {}

    static boolean isSilent(String category) {
        return SILENT_ID.equals(category);
    }

    static boolean isFavorites(String category) {
        return FAVORITES_ID.equals(category);
    }

    static List<String> categoriesFor(List<NotificationItem> notifications) {
        return categoriesFor(notifications, false);
    }

    static List<String> categoriesFor(List<NotificationItem> notifications,
            boolean favoritesEnabled) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (favoritesEnabled) categories.add(FAVORITES_ID);
        if (notifications == null || notifications.isEmpty()) {
            return Collections.unmodifiableList(new ArrayList<>(categories));
        }
        boolean hasSilent = false;
        for (NotificationItem item : notifications) {
            if (item == null) continue;
            if (item.silent) {
                hasSilent = true;
            } else if (item.packageName != null && !item.packageName.isEmpty()) {
                categories.add(item.packageName);
            }
        }
        if (hasSilent) categories.add(SILENT_ID);
        return Collections.unmodifiableList(new ArrayList<>(categories));
    }
}
