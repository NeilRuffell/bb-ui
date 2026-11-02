package dev.q25.bb7pulldown;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Route custom notification-card activation through LineageOS SystemUI.
 *
 * Prefer a native row click (full stock notification click semantics).
 * If the stock shade never inflated a row, use SystemUI's existing
 * ActivityStarter to dispatch the original notification PendingIntent.
 * This fixes the Android background-activity-start handling that ordinary
 * PendingIntent.send() bypassed. No custom app-launch intent is constructed.
 */
final class NativeNotificationClicks {
    private static final String TAG = "BB7-PullDown";
    private static final String CLICKER_CLASS =
            "com.android.systemui.statusbar.notification.NotificationClicker";
    private static final String NOTIFICATIONS_CONTROLLER =
            "com.android.systemui.statusbar.notification.init.NotificationsControllerImpl";
    private static final String NOTIFICATION_ACTIVITY_STARTER =
            "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter";
    private static final String ACTIVITY_STARTER_IMPL =
            "com.android.systemui.statusbar.phone.ActivityStarterImpl";
    private static final String NOTIF_COLLECTION =
            "com.android.systemui.statusbar.notification.collection.NotifCollection";
    private static final String USER_INTERACTION =
            "com.android.systemui.statusbar.notification.collection.inflation.OnUserInteractionCallbackImpl";
    private static final String CLICK_NOTIFIER =
            "com.android.systemui.statusbar.NotificationClickNotifier";
    private static volatile WeakReference<Object> systemActivityStarter =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> notificationActivityStarter =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> nativeCollection =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> nativeInteraction =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> nativeClickNotifier =
            new WeakReference<>(null);

    // Row lifecycle remains owned by SystemUI. No strong references to rows.
    private static final Map<String, WeakReference<View>> CLICKABLE_ROWS = new HashMap<>();

    static void install(ClassLoader loader) {
        installClickerObserver(loader);
        installActivityStarterObserver(loader);
        installNotificationPipelineObservers(loader);
    }

    private static void installClickerObserver(ClassLoader loader) {
        Class<?> clicker = XposedHelpers.findClassIfExists(CLICKER_CLASS, loader);
        if (clicker == null) return;
        try {
            XposedBridge.hookAllMethods(clicker, "register", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length != 2
                            || !(param.args[0] instanceof View)
                            || !(param.args[1] instanceof StatusBarNotification)) return;
                    View row = (View) param.args[0];
                    StatusBarNotification sbn = (StatusBarNotification) param.args[1];
                    synchronized (CLICKABLE_ROWS) {
                        if (row.hasOnClickListeners()) {
                            CLICKABLE_ROWS.put(sbn.getKey(), new WeakReference<>(row));
                        } else {
                            CLICKABLE_ROWS.remove(sbn.getKey());
                        }
                    }
                }
            });
            Log.i(TAG, "Native notification row observer registered");
        } catch (Throwable e) {
            Log.e(TAG, "Unable to observe native notification rows", e);
        }
    }

    /**
     * Capture the existing ActivityStarter at construction time rather than
     * waiting for NotificationsControllerImpl.initialize(). SystemUI may
     * initialize its notifications controller before that hook is called.
     *
     * LineageOS 24's StatusBarNotificationActivityStarter has the injected
     * mActivityStarter field; ActivityStarterImpl is the singleton that
     * implements the native launch API. Hook both normal DI construction
     * paths, retaining initialize() as a compatibility fallback.
     */
    private static void installActivityStarterObserver(ClassLoader loader) {
        Class<?> implementation = XposedHelpers.findClassIfExists(
                ACTIVITY_STARTER_IMPL, loader);
        if (implementation != null) {
            try {
                XposedBridge.hookAllConstructors(implementation, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) {
                            captureDirectActivityStarter(param.thisObject, "constructor");
                        }
                    }
                });
                Log.i(TAG, "SystemUI ActivityStarterImpl constructor observer registered");
            } catch (Throwable error) {
                Log.e(TAG, "Unable to observe ActivityStarterImpl construction", error);
            }
        } else {
            Log.w(TAG, "SystemUI ActivityStarterImpl absent in this classloader");
        }

        Class<?> starter = XposedHelpers.findClassIfExists(
                NOTIFICATION_ACTIVITY_STARTER, loader);
        if (starter != null) {
            try {
                XposedBridge.hookAllConstructors(starter, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable()) {
                            captureActivityStarter(param.thisObject, "notification constructor");
                        }
                    }
                });
                Log.i(TAG, "Notification activity starter constructor observer registered");
            } catch (Throwable error) {
                Log.e(TAG, "Unable to observe notification starter construction", error);
            }
        } else {
            Log.w(TAG, "StatusBarNotificationActivityStarter absent in this classloader");
        }

        Class<?> controller = XposedHelpers.findClassIfExists(
                NOTIFICATIONS_CONTROLLER, loader);
        if (controller != null) {
            try {
                XposedBridge.hookAllMethods(controller, "initialize", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!param.hasThrowable() && param.args.length >= 3) {
                            captureActivityStarter(param.args[2], "notifications initialize");
                        }
                    }
                });
                Log.i(TAG, "SystemUI notifications initialize observer registered");
            } catch (Throwable error) {
                Log.e(TAG, "Unable to observe SystemUI notifications initialize", error);
            }
        }
    }

    /**
     * Capture SystemUI's independently constructed notification services.
     * On the Q25, ActivityStarterImpl is constructed while
     * StatusBarNotificationActivityStarter may never be constructed.
     * None of these services is constructed or replaced by this module.
     */
    private static void installNotificationPipelineObservers(ClassLoader loader) {
        observeConstructor(loader, NOTIF_COLLECTION, "NotifCollection",
                object -> nativeCollection = new WeakReference<>(object));
        observeConstructor(loader, USER_INTERACTION, "OnUserInteractionCallback",
                object -> {
                    nativeInteraction = new WeakReference<>(object);
                    try {
                        Object collection = XposedHelpers.getObjectField(
                                object, "mNotifCollection");
                        if (collection != null) {
                            nativeCollection = new WeakReference<>(collection);
                        }
                    } catch (Throwable error) {
                        Log.w(TAG, "Native interaction collection unavailable", error);
                    }
                });
        observeConstructor(loader, CLICK_NOTIFIER, "NotificationClickNotifier",
                object -> nativeClickNotifier = new WeakReference<>(object));
    }

    private interface Capture { void accept(Object value); }

    private static void observeConstructor(ClassLoader loader, String className,
            String label, Capture capture) {
        Class<?> type = XposedHelpers.findClassIfExists(className, loader);
        if (type == null) {
            Log.i(TAG, "Native " + label + " class absent in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null) return;
                    try {
                        capture.accept(param.thisObject);
                        Log.i(TAG, "Native " + label + " captured");
                    } catch (Throwable error) {
                        Log.w(TAG, "Unable to capture native " + label, error);
                    }
                }
            });
            Log.i(TAG, "Native " + label + " constructor observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to observe native " + label, error);
        }
    }

    private static void captureActivityStarter(Object nativeNotificationStarter, String source) {
        if (nativeNotificationStarter == null) return;
        notificationActivityStarter = new WeakReference<>(nativeNotificationStarter);
        try {
            Object interaction = XposedHelpers.getObjectField(
                    nativeNotificationStarter, "mOnUserInteractionCallback");
            if (interaction != null) {
                nativeInteraction = new WeakReference<>(interaction);
                Object collection = XposedHelpers.getObjectField(
                        interaction, "mNotifCollection");
                if (collection != null) nativeCollection = new WeakReference<>(collection);
            }
            Object notifier = XposedHelpers.getObjectField(
                    nativeNotificationStarter, "mClickNotifier");
            if (notifier != null) nativeClickNotifier = new WeakReference<>(notifier);
        } catch (Throwable error) {
            Log.i(TAG, "Notification starter lacks optional click service references");
        }
        try {
            Object nativeStarter = XposedHelpers.getObjectField(
                    nativeNotificationStarter, "mActivityStarter");
            captureDirectActivityStarter(nativeStarter, source);
            Log.i(TAG, "Native notification click services captured via " + source);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to obtain SystemUI native launcher from " + source, error);
        }
    }

    private static void captureDirectActivityStarter(Object nativeStarter, String source) {
        if (nativeStarter == null) return;
        systemActivityStarter = new WeakReference<>(nativeStarter);
        Log.i(TAG, "Native SystemUI ActivityStarter captured via " + source);
    }

    /** Retrieve an actual SystemUI NotificationEntry by its notification key. */
    private static Object getNativeEntry(String key) {
        Object collection = nativeCollection.get();
        if (collection == null) {
            Log.i(TAG, "Native NotifCollection unavailable (launch remains permitted)");
            return null;
        }
        try {
            Object entry = XposedHelpers.callMethod(collection, "getEntry", key);
            if (entry == null) Log.i(TAG, "No native entry for selected notification");
            return entry;
        } catch (Throwable error) {
            Log.w(TAG, "Native NotifCollection entry lookup failed", error);
            return null;
        }
    }

    /**
     * Copy the native StatusBarNotificationActivityStarter's policy and calls,
     * not a custom notification removal. This is executed immediately after
     * dispatching ActivityStarter, as stock SystemUI does after starting an
     * activity, rather than waiting for an asynchronous intent-sent callback.
     *
     * Keep auto-dismiss independent from NMS click reporting: availability of
     * one native service must not suppress the other.
     */
    private static void recordNativeClick(String key, Object entry) {
        Object interaction = nativeInteraction.get();
        Object notifier = nativeClickNotifier.get();
        Log.i(TAG, "Native click services: entry=" + (entry != null)
                + " interaction=" + (interaction != null)
                + " notifier=" + (notifier != null));
        if (entry == null) {
            Log.w(TAG, "No native notification entry for click bookkeeping");
            return;
        }
        StatusBarNotification sbn;
        boolean canBubble;
        try {
            sbn = (StatusBarNotification) XposedHelpers.callMethod(entry, "getSbn");
            canBubble = (Boolean) XposedHelpers.callMethod(entry, "canBubble");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to read native notification click policy", error);
            return;
        }

        boolean autoCancel = !canBubble && sbn != null && sbn.getNotification() != null
                && (sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL)
                == Notification.FLAG_AUTO_CANCEL;
        Log.i(TAG, "Native click policy: autoCancel=" + autoCancel
                + " canBubble=" + canBubble);

        // Matches StatusBarNotificationActivityStarter:
        // registerFutureDismissal(entry, REASON_CLICK) and execute its
        // system-owned Runnable on SystemUI main. No manual cancel call.
        if (autoCancel) {
            if (interaction == null) {
                Log.w(TAG, "Native auto-cancel unavailable: interaction callback not captured");
            } else {
                try {
                    Runnable removal = (Runnable) XposedHelpers.callMethod(
                            interaction, "registerFutureDismissal",
                            entry, NotificationListenerService.REASON_CLICK);
                    removal.run();
                    Log.i(TAG, "Native auto-cancel notification dismissal dispatched");
                } catch (Throwable error) {
                    Log.e(TAG, "Native auto-cancel notification dismissal failed", error);
                }
            }
        }

        // The stock notification-click notifier is independent of auto-cancel.
        if (notifier == null || interaction == null) {
            Log.w(TAG, "Native notification click reporting unavailable");
            return;
        }
        try {
            Object visibilityProvider = XposedHelpers.getObjectField(
                    interaction, "mVisibilityProvider");
            Object visibility = XposedHelpers.callMethod(
                    visibilityProvider, "obtain", entry, true);
            XposedHelpers.callMethod(notifier, "onNotificationClick", key, visibility);
            Log.i(TAG, "Native notification click reported to notification manager");
        } catch (Throwable error) {
            Log.e(TAG, "Native notification click reporting failed", error);
        }
    }

    /**
     * Prefer the actual clickable SystemUI row. For notifications whose stock
     * rows have not been inflated, delegate to SystemUI's own ActivityStarter
     * and then its actual click/auto-cancel machinery (using the native entry).
     */
    static void open(String notificationKey, Runnable onDispatched) {
        if (notificationKey == null
                || !SystemUiNotificationObserver.isActive(notificationKey)) {
            Log.i(TAG, "Notification click skipped: notification is no longer active");
            return;
        }

        Object entry = getNativeEntry(notificationKey);
        View row = null;
        if (entry != null) {
            try {
                Object nativeRow = XposedHelpers.callMethod(entry, "getRow");
                if (nativeRow instanceof View) row = (View) nativeRow;
            } catch (Throwable error) {
                Log.w(TAG, "Native entry has no usable notification row", error);
            }
        }
        if (row == null) {
            synchronized (CLICKABLE_ROWS) {
                WeakReference<View> reference = CLICKABLE_ROWS.get(notificationKey);
                row = reference == null ? null : reference.get();
            }
        }
        if (row != null) {
            if (!row.hasOnClickListeners() || !row.isEnabled()) {
                Log.i(TAG, "Notification click unavailable: native row not actionable");
                return;
            }
            try {
                if (row.performClick()) {
                    Log.i(TAG, "Native notification row click dispatched");
                    onDispatched.run();
                } else {
                    Log.w(TAG, "Native notification row rejected click");
                }
            } catch (Throwable error) {
                Log.e(TAG, "Native notification row click failed", error);
            }
            return;
        }

        // Match StatusBarNotificationActivityStarter exactly: it uses
        // entry.canBubble() when selecting the native bubble expansion path,
        // and excludes those entries from FLAG_AUTO_CANCEL dismissal.
        try {
            if (entry != null && (Boolean) XposedHelpers.callMethod(entry, "canBubble")) {
                Object nativeStarter = notificationActivityStarter.get();
                if (nativeStarter == null) {
                    Log.w(TAG, "Native bubble click unavailable: notification starter missing");
                    return;
                }
                java.util.Optional<?> bubbles =
                        (java.util.Optional<?>) XposedHelpers.getObjectField(
                                nativeStarter, "mBubblesManagerOptional");
                if (!bubbles.isPresent()) {
                    Log.i(TAG, "Native bubble manager absent; leaving pull-down open");
                    return;
                }
                XposedHelpers.callMethod(
                        nativeStarter, "expandBubbleStackOnMainThread", entry);
                recordNativeClick(notificationKey, entry);
                Log.i(TAG, "Native notification bubble expansion dispatched");
                onDispatched.run();
                return;
            }
        } catch (Throwable error) {
            Log.e(TAG, "Native notification bubble dispatch failed", error);
            return;
        }

        PendingIntent action = SystemUiNotificationObserver.getOpenAction(notificationKey);
        if (action == null) {
            Log.i(TAG, "Notification has no native opening action");
            return;
        }
        Object starter = systemActivityStarter.get();
        if (starter == null) {
            Log.w(TAG, "Native SystemUI ActivityStarter not captured; leaving panel open");
            return;
        }

        try {
            final Object nativeEntry = entry;
            Log.i(TAG, "Dispatching via native SystemUI ActivityStarter"
                    + " nativeEntry=" + (nativeEntry != null));
            XposedHelpers.callMethod(starter, "startPendingIntentDismissingKeyguard",
                    action, (Runnable) () -> {
                        Log.i(TAG, "Native SystemUI ActivityStarter intent-sent callback");
                        onDispatched.run();
                    });
            // Stock SystemUI registers the native future-dismissal immediately
            // after requesting the launch, not in the intent-sent callback.
            recordNativeClick(notificationKey, nativeEntry);
        } catch (Throwable error) {
            Log.e(TAG, "Native SystemUI activity start failed", error);
        }
    }

    static void forget(String key) {
        if (key == null) return;
        synchronized (CLICKABLE_ROWS) {
            CLICKABLE_ROWS.remove(key);
        }
    }

    private NativeNotificationClicks() {}
}
