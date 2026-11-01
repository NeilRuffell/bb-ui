package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Presentation-only filtering. Android notifications stay intact. */
final class NotificationSelectionPolicy {
    private NotificationSelectionPolicy() {}

    static List<NotificationItem> forPackage(List<NotificationItem> all, String selectedPackage) {
        if (selectedPackage == null || all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        List<NotificationItem> result = new ArrayList<>();
        for (NotificationItem item : all) {
            if (item != null && selectedPackage.equals(item.packageName)) result.add(item);
        }
        return Collections.unmodifiableList(result);
    }
}
