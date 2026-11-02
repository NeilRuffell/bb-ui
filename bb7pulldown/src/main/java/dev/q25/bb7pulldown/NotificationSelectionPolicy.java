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
        if (selectedPackage == null || all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        // Hide a group summary only when real children from that group are present.
        // Keep orphan summaries visible.
        Set<String> childGroups = new HashSet<>();
        for (NotificationItem item : all) {
            if (item != null && selectedPackage.equals(item.packageName)
                    && !item.groupSummary && item.groupKey != null) {
                childGroups.add(item.groupKey);
            }
        }
        List<NotificationItem> alerting = new ArrayList<>();
        List<NotificationItem> silent = new ArrayList<>();
        for (NotificationItem item : all) {
            if (item == null || !selectedPackage.equals(item.packageName)) continue;
            if (item.groupSummary && item.groupKey != null
                    && childGroups.contains(item.groupKey)) continue;
            (item.silent ? silent : alerting).add(item);
        }
        alerting.addAll(silent);
        return Collections.unmodifiableList(alerting);
    }
}
