package dev.q25.bb7shade;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * Immutable UI-facing package/notification index, populated exclusively from
 * the finalized ShadeListBuilder render list.
 *
 * This is NOT a filter in NotifPipeline: selection must never alter the
 * canonical notification collection, grouping, alerts, or ordering.
 */
final class ShadeNotificationIndex {
    private static final String TAG = "BB7-Shade";
    private static final String NOTIFICATION_ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String GROUP_ENTRY =
            "com.android.systemui.statusbar.notification.collection.GroupEntry";
    private static final String BUNDLE_ENTRY =
            "com.android.systemui.statusbar.notification.collection.BundleEntry";

    static final class NotificationRef {
        final String key;
        final String packageName;

        NotificationRef(String key, String packageName) {
            this.key = key;
            this.packageName = packageName;
        }
    }

    static final class Snapshot {
        final Map<String, List<NotificationRef>> notificationsByApp;
        final Map<String, Integer> appCounts;
        final int notificationCount;

        Snapshot(LinkedHashMap<String, List<NotificationRef>> items) {
            LinkedHashMap<String, List<NotificationRef>> lists = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (Map.Entry<String, List<NotificationRef>> app : items.entrySet()) {
                List<NotificationRef> copy =
                        Collections.unmodifiableList(new ArrayList<>(app.getValue()));
                lists.put(app.getKey(), copy);
                counts.put(app.getKey(), copy.size());
                total += copy.size();
            }
            notificationsByApp = Collections.unmodifiableMap(lists);
            appCounts = Collections.unmodifiableMap(counts);
            notificationCount = total;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(new LinkedHashMap<>());
    private static volatile boolean firstUpdate = true;

    private ShadeNotificationIndex() {}

    static Snapshot current() {
        return snapshot;
    }

    static void update(List<?> renderedEntries) {
        LinkedHashMap<String, List<NotificationRef>> byApp = new LinkedHashMap<>();
        Set<String> uniqueKeys = new HashSet<>();
        for (Object entry : renderedEntries) {
            collect(entry, byApp, uniqueKeys);
        }
        Snapshot next = new Snapshot(byApp);
        Snapshot previous = snapshot;
        snapshot = next;
        boolean isFirst = firstUpdate;
        firstUpdate = false;
        if (isFirst || !next.appCounts.equals(previous.appCounts)
                || next.notificationCount != previous.notificationCount) {
            // Privacy: only counts are logged, never package names, keys,
            // notification text, sender information or user details.
            Log.i(TAG, "Rendered notification index updated: apps="
                    + next.appCounts.size() + " entries=" + next.notificationCount);
        }
    }

    private static void collect(Object entry,
            LinkedHashMap<String, List<NotificationRef>> byApp,
            Set<String> keys) {
        if (entry == null) return;
        Class<?> type = entry.getClass();
        if (isClassOrSubclass(type, NOTIFICATION_ENTRY)) {
            try {
                String key = (String) XposedHelpers.callMethod(entry, "getKey");
                StatusBarNotification sbn = (StatusBarNotification)
                        XposedHelpers.callMethod(entry, "getSbn");
                if (key == null || sbn == null) return;
                String packageName = sbn.getPackageName();
                if (packageName == null || packageName.isEmpty()
                        || !keys.add(key)) return;
                List<NotificationRef> app = byApp.get(packageName);
                if (app == null) {
                    app = new ArrayList<>();
                    byApp.put(packageName, app);
                }
                app.add(new NotificationRef(key, packageName));
            } catch (Throwable error) {
                Log.w(TAG, "Skipping an incompatible notification entry");
            }
            return;
        }
        if (isClassOrSubclass(type, GROUP_ENTRY)) {
            try {
                Object children = XposedHelpers.callMethod(entry, "getChildren");
                if (children instanceof List && !((List<?>) children).isEmpty()) {
                    // A group summary is presentation metadata; count its
                    // children rather than double-counting that summary.
                    for (Object child : (List<?>) children) {
                        collect(child, byApp, keys);
                    }
                } else {
                    collect(XposedHelpers.callMethod(entry, "getSummary"), byApp, keys);
                }
            } catch (Throwable error) {
                Log.w(TAG, "Skipping an incompatible notification group");
            }
            return;
        }
        if (isClassOrSubclass(type, BUNDLE_ENTRY)) {
            try {
                Object children = XposedHelpers.callMethod(entry, "getChildren");
                if (children instanceof List) {
                    for (Object child : (List<?>) children) {
                        collect(child, byApp, keys);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Skipping an incompatible notification bundle");
            }
        }
    }

    private static boolean isClassOrSubclass(Class<?> cls, String qualifiedName) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (qualifiedName.equals(c.getName())) return true;
        }
        return false;
    }
}
