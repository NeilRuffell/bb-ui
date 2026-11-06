package dev.q25.bb7pulldown;

import java.util.List;

/** Choice of left-column organization. Native notification state is unaffected. */
final class NotificationShadeModePolicy {
    static final String APPS = "apps";
    static final String TYPES = "types";

    private NotificationShadeModePolicy() {}

    static String normalized(String mode) {
        return TYPES.equals(mode) ? TYPES : APPS;
    }

    static List<String> categoriesFor(List<NotificationItem> visible,
            boolean favoritesEnabled, String mode) {
        if (TYPES.equals(normalized(mode))) {
            return NotificationTypePolicy.categories(favoritesEnabled);
        }
        return NotificationCategoryPolicy.categoriesFor(visible, favoritesEnabled);
    }

    static List<NotificationItem> select(List<NotificationItem> visible,
            FavoritesRules favorites, String category) {
        if (NotificationCategoryPolicy.isFavorites(category)) {
            return favorites == null ? java.util.Collections.emptyList()
                    : favorites.filter(visible);
        }
        if (NotificationCategoryPolicy.isSilent(category)) {
            return NotificationSelectionPolicy.forSilent(visible);
        }
        if (NotificationTypePolicy.isType(category)) {
            return NotificationTypePolicy.filter(visible, category);
        }
        return NotificationSelectionPolicy.forPackage(visible, category);
    }
}
