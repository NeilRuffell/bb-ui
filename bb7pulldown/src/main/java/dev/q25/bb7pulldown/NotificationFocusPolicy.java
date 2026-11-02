package dev.q25.bb7pulldown;

import java.util.List;
import java.util.Objects;

/** Stable selection by notification key across live list changes. */
final class NotificationFocusPolicy {
    private NotificationFocusPolicy() {}

    static int indexOf(List<NotificationItem> items, String key) {
        if (key == null || items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            NotificationItem item = items.get(i);
            if (item != null && Objects.equals(key, item.key)) return i;
        }
        return -1;
    }

    static String retainedKey(List<NotificationItem> items, String key, int oldIndex) {
        if (items == null || items.isEmpty()) return null;
        int existing = indexOf(items, key);
        if (existing >= 0) return items.get(existing).key;
        return items.get(Math.min(Math.max(oldIndex, 0), items.size() - 1)).key;
    }
}
