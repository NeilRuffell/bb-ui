package dev.q25.bb7pulldown;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read active notification *package identities* from the same registered
 * NotificationListenerService instance owned by SystemUI.
 *
 * The shade-list hook alone can miss its first dispatch when the pipeline
 * was built before Vector hooked it. The native listener supplies both a
 * complete initial snapshot and post/remove callbacks. No notification
 * text/actions, UI views or platform notification state are changed.
 */
final class SystemUiNotificationObserver {
    private static final String TAG = "BB7-PullDown";
    private static final String LISTENER =
            "com.android.systemui.statusbar.NotificationListener";
    private static final Object LOCK = new Object();
    // Keys avoid duplicate posts and preserve the insertion order across apps.
    private static final Map<String, NotificationItem> NOTIFICATIONS = new LinkedHashMap<>();
    private static volatile List<String> activePackages = Collections.emptyList();
    private static volatile List<NotificationItem> activeItems = Collections.emptyList();
    private static volatile WeakReference<NotificationListenerService> listener =
            new WeakReference<>(null);
    private static boolean snapshotLogged;
    private static boolean refreshErrorLogged;

    static List<String> snapshot() {
        return activePackages;
    }

    static List<NotificationItem> itemsSnapshot() {
        return activeItems;
    }

    static void install(ClassLoader loader) {
        Class<?> type = XposedHelpers.findClassIfExists(LISTENER, loader);
        if (type == null) {
            Log.i(TAG, "SystemUI notification listener absent in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(type, "onListenerConnected", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    captureAndRefresh(param.thisObject, "connected");
                }
            });
            XposedBridge.hookAllMethods(type, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !(param.args[0] instanceof StatusBarNotification)) return;
                    StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                    listener = new WeakReference<>((NotificationListenerService) param.thisObject);
                    synchronized (LOCK) {
                        NOTIFICATIONS.put(sbn.getKey(), toItem(sbn));
                        publishLocked();
                    }
                }
            });
            XposedBridge.hookAllMethods(type, "onNotificationRemoved", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !(param.args[0] instanceof StatusBarNotification)) return;
                    StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                    synchronized (LOCK) {
                        NOTIFICATIONS.remove(sbn.getKey());
                        NativeNotificationClicks.forget(sbn.getKey());
                        publishLocked();
                    }
                }
            });
            Log.i(TAG, "SystemUI active-notification listener observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register active-notification observer", error);
        }
    }

    /** Refresh on opening as well, in case the listener connected early. */
    static void refreshBeforeOpen() {
        NotificationListenerService service = listener.get();
        if (service != null) refresh(service, "open");
        else Log.i(TAG, "Notification listener not captured yet; apps="
                + activePackages.size());
    }

    private static void captureAndRefresh(Object service, String source) {
        if (!(service instanceof NotificationListenerService)) return;
        NotificationListenerService notificationListener = (NotificationListenerService) service;
        listener = new WeakReference<>(notificationListener);
        refresh(notificationListener, source);
    }

    private static void refresh(NotificationListenerService service, String source) {
        try {
            StatusBarNotification[] current = service.getActiveNotifications();
            if (current == null) {
                Log.w(TAG, "Active notification snapshot unavailable source=" + source);
                return;
            }
            synchronized (LOCK) {
                NOTIFICATIONS.clear();
                for (StatusBarNotification sbn : current) {
                    if (sbn == null) continue;
                    NOTIFICATIONS.put(sbn.getKey(), toItem(sbn));
                }
                if (!snapshotLogged || "open".equals(source)) {
                    snapshotLogged = true;
                    Log.i(TAG, "Native notification snapshot: source=" + source
                            + " entries=" + current.length + " apps="
                            + packageNames().size());
                }
                publishLocked();
            }
        } catch (Throwable error) {
            if (!refreshErrorLogged) {
                refreshErrorLogged = true;
                Log.w(TAG, "Cannot read active notifications from SystemUI", error);
            }
        }
    }

    /** Guard against stale notification cards during live notification updates. */
    static boolean isActive(String notificationKey) {
        synchronized (LOCK) {
            return NOTIFICATIONS.containsKey(notificationKey);
        }
    }

    /** Must hold LOCK. Publish on new, changed or removed notification data. */
    private static void publishLocked() {
        List<NotificationItem> items = Collections.unmodifiableList(
                new ArrayList<>(NOTIFICATIONS.values()));
        if (items.equals(activeItems)) return;
        List<String> packages = packageNames();
        boolean appsChanged = !packages.equals(activePackages);
        activeItems = items;
        activePackages = packages;
        if (appsChanged) {
            Log.i(TAG, "Notification app index updated: apps=" + packages.size());
        }
        Log.i(TAG, "Notification summaries updated: entries=" + items.size());
        PullDownHook.onNotificationItemsUpdated();
    }

    private static List<String> packageNames() {
        List<String> names = new ArrayList<>();
        for (NotificationItem item : NOTIFICATIONS.values()) {
            names.add(item.packageName);
        }
        return NotificationAppIndex.uniquePackages(names);
    }

    private static NotificationItem toItem(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        CharSequence title = extras == null ? null
                : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = extras == null ? null
                : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null || body.length() == 0) {
            body = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if ((body == null || body.length() == 0) && extras != null) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                StringBuilder text = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null || line.length() == 0) continue;
                    if (text.length() != 0) text.append("\n");
                    text.append(line);
                    if (text.length() >= 700) break;
                }
                body = text;
            }
        }
        if ((body == null || body.length() == 0) && notification != null) {
            body = notification.tickerText;
        }
        return new NotificationItem(sbn.getKey(), sbn.getPackageName(),
                plain(title, 140), plain(body, 700), sbn.getPostTime());
    }

    /** Drop app-supplied formatting before displaying text in the panel. */
    private static String plain(CharSequence text, int max) {
        if (text == null) return "";
        String value = text.toString().trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private SystemUiNotificationObserver() {}
}
