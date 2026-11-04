package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable inputs for a lock-screen app notification strip. Counts refer to
 * distinct currently posted Android notification records, not unread
 * messages inside an app. Group-summary records are not double-counted when
 * their child notifications are present.
 *
 * Caller applies native lock-screen notification visibility settings before
 * supplying records. No notification text or app content is retained here.
 */
final class LockscreenNotificationCounts {
    static final class Record {
        final String packageName;
        final String groupKey;
        final boolean groupSummary;
        final boolean visible;

        Record(String packageName, String groupKey,
                boolean groupSummary, boolean visible) {
            this.packageName = packageName;
            this.groupKey = groupKey;
            this.groupSummary = groupSummary;
            this.visible = visible;
        }
    }

    private LockscreenNotificationCounts() {}

    static Map<String, Integer> count(List<Record> input) {
        if (input == null || input.isEmpty()) return Collections.emptyMap();

        Set<String> childGroups = new HashSet<>();
        for (Record record : input) {
            if (record == null || !record.visible || record.groupSummary
                    || record.groupKey == null) continue;
            childGroups.add(record.groupKey);
        }

        // LinkedHashMap keeps native notification arrival order stable.
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (Record record : input) {
            if (record == null || !record.visible
                    || record.packageName == null || record.packageName.isEmpty()) continue;
            if (record.groupSummary && record.groupKey != null
                    && childGroups.contains(record.groupKey)) continue;
            Integer count = counts.get(record.packageName);
            counts.put(record.packageName, count == null ? 1 : count + 1);
        }
        return Collections.unmodifiableMap(counts);
    }
}
