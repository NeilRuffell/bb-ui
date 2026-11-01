package dev.q25.bb7shade;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedHelpers;

/**
 * App-centric, read-only index of entries eligible for the stock shade.
 *
 * Data is derived from NotifPipeline's already filtered, ordered pre-render
 * list, not getAllNotifs(), which includes notifications hidden from the shade.
 * GroupEntry and BundleEntry are expanded for sidebar app counts.
 *
 * No notification text, sender, user data or notification keys are logged.
 */
final class ShadeNotificationIndex {
    private static final String TAG = "BB7-Shade";
    private static final String NOTIFICATION_ENTRY =
            "com.android.systemui.statusbar.notification.collection.NotificationEntry";
    private static final String GROUP_ENTRY =
            "com.android.systemui.statusbar.notification.collection.GroupEntry";
    private static final String BUNDLE_ENTRY =
            "com.android.systemui.statusbar.notification.collection.BundleEntry";

    static final class Snapshot {
        final Map<String, Integer> appCounts;
        final int notificationCount;

        Snapshot(LinkedHashMap<String, Integer> counts, int total) {
            appCounts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            notificationCount = total;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(new LinkedHashMap<>(), 0);

    private ShadeNotificationIndex() {}

    static Snapshot current() {
        return snapshot;
    }

    static void update(List<?> renderedEntries) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (Object entry : renderedEntries) {
            collect(entry, counts, keys);
        }
        Snapshot updated = new Snapshot(counts, keys.size());
        Snapshot previous = snapshot;
        snapshot = updated;
        if (!updated.appCounts.equals(previous.appCounts)
                || updated.notificationCount != previous.notificationCount) {
            Log.i(TAG, "Rendered notifications indexed: apps=" + counts.size()
                    + " entries=" + updated.notificationCount);
        }
    }

    private static void collect(Object entry, Map<String, Integer> counts, Set<String> keys) {
        if (entry == null) return;
        Class<?> cls = entry.getClass();

        if (isClassOrSubclass(cls, NOTIFICATION_ENTRY)) {
            try {
                String key = (String) XposedHelpers.callMethod(entry, "getKey");
                StatusBarNotification sbn = (StatusBarNotification)
                        XposedHelpers.callMethod(entry, "getSbn");
                if (key == null || sbn == null || !keys.add(key)) return;
                String pkg = sbn.getPackageName();
                if (pkg == null || pkg.isEmpty()) return;
                Integer count = counts.get(pkg);
                counts.put(pkg, count == null ? 1 : count + 1);
            } catch (Throwable error) {
                // A single malformed row must never disrupt shade rendering.
                Log.w(TAG, "Skipped unsupported notification entry class");
            }
            return;
        }

        if (isClassOrSubclass(cls, GROUP_ENTRY)) {
            try {
                collect(XposedHelpers.callMethod(entry, "getSummary"), counts, keys);
                Object children = XposedHelpers.callMethod(entry, "getChildren");
                if (children instanceof List) {
                    for (Object child : (List<?>) children) collect(child, counts, keys);
                }
            } catch (Throwable error) {
                Log.w(TAG, "Skipped unsupported notification group");
            }
            return;
        }

        if (isClassOrSubclass(cls, BUNDLE_ENTRY)) {
            try {
                Object children = XposedHelpers.callMethod(entry, "getChildren");
                if (children instanceof List) {
                    for (Object child : (List<?>) children) collect(child, counts, keys);
                }
            } catch (Throwable error) {
                Log.w(TAG, "Skipped unsupported notification bundle");
            }
        }
    }

    private static boolean isClassOrSubclass(Class<?> cls, String qualifiedName) {
        for (Class<?> current = cls; current != null; current = current.getSuperclass()) {
            if (qualifiedName.equals(current.getName())) return true;
        }
        return false;
    }
}
