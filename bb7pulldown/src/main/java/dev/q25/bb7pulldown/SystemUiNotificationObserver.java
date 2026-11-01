package dev.q25.bb7pulldown;

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
    private static final Map<String, String> NOTIFICATIONS = new LinkedHashMap<>();
    private static volatile List<String> activePackages = Collections.emptyList();
    private static volatile WeakReference<NotificationListenerService> listener =
            new WeakReference<>(null);
    private static boolean snapshotLogged;
    private static boolean refreshErrorLogged;

    static List<String> snapshot() {
        return activePackages;
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
                        NOTIFICATIONS.put(sbn.getKey(), sbn.getPackageName());
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
                    NOTIFICATIONS.put(sbn.getKey(), sbn.getPackageName());
                }
                if (!snapshotLogged || "open".equals(source)) {
                    snapshotLogged = true;
                    Log.i(TAG, "Native notification snapshot: source=" + source
                            + " entries=" + current.length + " apps="
                            + NotificationAppIndex.uniquePackages(
                                    new ArrayList<>(NOTIFICATIONS.values())).size());
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

    /** Must hold LOCK. Publish immutable snapshots, no lock held during UI update. */
    private static void publishLocked() {
        List<String> packages = NotificationAppIndex.uniquePackages(
                new ArrayList<>(NOTIFICATIONS.values()));
        if (packages.equals(activePackages)) return;
        activePackages = packages;
        Log.i(TAG, "Notification app index updated: apps=" + packages.size());
        PullDownHook.onNotificationPackagesUpdated(packages);
    }

    private SystemUiNotificationObserver() {}
}
