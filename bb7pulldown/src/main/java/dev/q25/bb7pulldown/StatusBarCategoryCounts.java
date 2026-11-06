package dev.q25.bb7pulldown;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Status-bar counters consume only notifications already admitted to the native
 * SystemUI status-bar icon container; never all posted notifications.
 *
 * The collector is responsible for native eligibility and passes only its
 * included notification records here. Deduplicate group summaries before
 * categorizing, so a summary with no type cannot appear again under Other.
 */
final class StatusBarCategoryCounts {
    private StatusBarCategoryCounts() {}

    static Map<String, Integer> count(List<NotificationItem> nativeIcons) {
        if (nativeIcons == null || nativeIcons.isEmpty()) return Collections.emptyMap();
        List<NotificationItem> entries =
                NotificationSelectionPolicy.withoutRedundantGroupSummaries(nativeIcons);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (NotificationItem item : entries) {
            if (item == null) continue;
            String category = NotificationTypePolicy.typeOf(item);
            Integer current = counts.get(category);
            counts.put(category, current == null ? 1 : current + 1);
        }
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String type : NotificationTypePolicy.categories(false)) {
            Integer value = counts.get(type);
            if (value != null && value > 0) ordered.put(type, value);
        }
        return Collections.unmodifiableMap(ordered);
    }
}
