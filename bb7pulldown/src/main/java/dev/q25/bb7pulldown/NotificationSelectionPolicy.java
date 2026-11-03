package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Presentation-only filtering. Android notifications stay intact. */
final class NotificationSelectionPolicy {
    private NotificationSelectionPolicy() {}

    static List<NotificationItem> forPackage(List<NotificationItem> all, String selectedPackage) {
        if (selectedPackage == null || NotificationCategoryPolicy.isSilent(selectedPackage)) {
            return Collections.emptyList();
        }
        return filter(all, selectedPackage, false);
    }

    static List<NotificationItem> forSilent(List<NotificationItem> all) {
        return filter(all, null, true);
    }

    /** Filter UI duplicates only; platform notification state remains intact. */
    static List<NotificationItem> withoutRepresentedSilentMedia(
            List<NotificationItem> all, Set<String> representedMediaKeys) {
        if (all == null || all.isEmpty()) return Collections.emptyList();
        if (representedMediaKeys == null || representedMediaKeys.isEmpty()) return all;
        List<NotificationItem> visible = new ArrayList<>(all.size());
        for (NotificationItem item : all) {
            if (item != null && item.silent && representedMediaKeys.contains(item.key)) {
                continue;
            }
            visible.add(item);
        }
        return Collections.unmodifiableList(visible);
    }

    private static List<NotificationItem> filter(List<NotificationItem> all, String packageName,
            boolean silentOnly) {
        if (all == null || all.isEmpty()) return Collections.emptyList();

        // Only group children within this category suppress their group summary.
        Set<String> childGroups = new HashSet<>();
        for (NotificationItem item : all) {
            if (belongs(item, packageName, silentOnly)
                    && !item.groupSummary && item.groupKey != null) {
                childGroups.add(item.groupKey);
            }
        }
        List<NotificationItem> result = new ArrayList<>();
        for (NotificationItem item : all) {
            if (!belongs(item, packageName, silentOnly)) continue;
            if (item.groupSummary && item.groupKey != null
                    && childGroups.contains(item.groupKey)) continue;
            result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean belongs(NotificationItem item, String packageName, boolean silentOnly) {
        return item != null && (silentOnly ? item.silent
                : !item.silent && packageName.equals(item.packageName));
    }
}
