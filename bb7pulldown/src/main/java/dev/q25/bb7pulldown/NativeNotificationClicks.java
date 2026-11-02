package dev.q25.bb7pulldown;

import android.app.PendingIntent;
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
 * Delegate notification clicks to the stock LineageOS SystemUI services.
 *
 * An inflated native row already owns a NotificationClicker: use its listener.
 * Otherwise, use ActivityStarter to launch the notification's actual intent
 * and NotificationClickNotifier to report the click to NotificationManager.
 *
 * NotificationManagerService.onNotificationClick() performs the stock
 * FLAG_AUTO_CANCEL policy. Do not cancel or dismiss notifications here.
 */
final class NativeNotificationClicks {
    private static final String TAG = "BB7-PullDown";
    private static final String CLICKER =
            "com.android.systemui.statusbar.notification.NotificationClicker";
    private static final String ACTIVITY_STARTER =
            "com.android.systemui.statusbar.phone.ActivityStarterImpl";
    private static final String CLICK_NOTIFIER =
            "com.android.systemui.statusbar.NotificationClickNotifier";
    // System framework type used by LineageOS's NotificationVisibilityProviderImpl.
    private static final String NOTIFICATION_VISIBILITY =
            "com.android.internal.statusbar.NotificationVisibility";

    private static volatile WeakReference<Object> systemActivityStarter =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> clickNotifier =
            new WeakReference<>(null);
    private static volatile Class<?> notificationVisibilityClass;

    private static final Map<String, WeakReference<View>> CLICKABLE_ROWS = new HashMap<>();

    static void install(ClassLoader loader) {
        observeConstructor(loader, ACTIVITY_STARTER, "ActivityStarter",
                instance -> systemActivityStarter = new WeakReference<>(instance));
        observeConstructor(loader, CLICK_NOTIFIER, "NotificationClickNotifier",
                instance -> clickNotifier = new WeakReference<>(instance));
        Class<?> type = XposedHelpers.findClassIfExists(NOTIFICATION_VISIBILITY, loader);
        if (type != null) {
            notificationVisibilityClass = type;
            Log.i(TAG, "Native NotificationVisibility framework class resolved");
        } else {
            Log.e(TAG, "Native NotificationVisibility framework class unavailable");
        }

        Class<?> clicker = XposedHelpers.findClassIfExists(CLICKER, loader);
        if (clicker == null) return;
        try {
            XposedBridge.hookAllMethods(clicker, "register", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length != 2
                            || !(param.args[0] instanceof View)
                            || !(param.args[1] instanceof StatusBarNotification)) return;
                    View row = (View) param.args[0];
                    String key = ((StatusBarNotification) param.args[1]).getKey();
                    synchronized (CLICKABLE_ROWS) {
                        if (row.hasOnClickListeners()) {
                            CLICKABLE_ROWS.put(key, new WeakReference<>(row));
                        } else {
                            CLICKABLE_ROWS.remove(key);
                        }
                    }
                }
            });
            Log.i(TAG, "Native notification row click observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Native notification row click observer registration failed", error);
        }
    }

    private interface Capture { void accept(Object instance); }

    private static void observeConstructor(ClassLoader loader, String className,
            String label, Capture callback) {
        Class<?> type = XposedHelpers.findClassIfExists(className, loader);
        if (type == null) {
            Log.w(TAG, "Native " + label + " class unavailable");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null) return;
                    callback.accept(param.thisObject);
                    Log.i(TAG, "Native " + label + " captured");
                }
            });
            Log.i(TAG, "Native " + label + " observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Native " + label + " observer registration failed", error);
        }
    }

    static void open(String key, Runnable onDispatched) {
        if (key == null || !SystemUiNotificationObserver.isActive(key)) {
            Log.i(TAG, "Notification click ignored: no longer active");
            return;
        }

        View row;
        synchronized (CLICKABLE_ROWS) {
            WeakReference<View> ref = CLICKABLE_ROWS.get(key);
            row = ref == null ? null : ref.get();
        }
        if (row != null && row.hasOnClickListeners() && row.isEnabled()) {
            try {
                if (row.performClick()) {
                    Log.i(TAG, "Native notification row clicked");
                    onDispatched.run();
                }
            } catch (Throwable error) {
                Log.e(TAG, "Native row click failed", error);
            }
            return;
        }

        PendingIntent intent = SystemUiNotificationObserver.getOpenAction(key);
        if (intent == null) {
            Log.i(TAG, "Notification has no native opening action");
            return;
        }
        Object starter = systemActivityStarter.get();
        if (starter == null) {
            Log.w(TAG, "Native ActivityStarter unavailable; not opening notification");
            return;
        }

        try {
            XposedHelpers.callMethod(starter, "startPendingIntentDismissingKeyguard",
                    intent, (Runnable) () -> {
                        Log.i(TAG, "Native ActivityStarter intent-sent callback");
                        onDispatched.run();
                    });
            // This calls the same SystemUI notifier as a stock notification
            // click. NMS decides whether a notification is auto-cancelable.
            reportNativeClick(key);
        } catch (Throwable error) {
            Log.e(TAG, "Native notification launch failed", error);
        }
    }

    private static void reportNativeClick(String key) {
        Object notifier = clickNotifier.get();
        Class<?> visibilityType = notificationVisibilityClass;
        if (notifier == null || visibilityType == null) {
            Log.e(TAG, "Stock click reporting unavailable: notifier=" + (notifier != null)
                    + " frameworkVisibility=" + (visibilityType != null));
            return;
        }
        try {
            // Exact framework API and missing-entry representation used by
            // LineageOS NotificationVisibilityProviderImpl.obtain(String,...).
            // Our replacement pane has no native rendered notification row.
            Object visibility = XposedHelpers.callStaticMethod(
                    visibilityType, "obtain", key, -1,
                    SystemUiNotificationObserver.itemsSnapshot().size(), false);
            XposedHelpers.callMethod(notifier, "onNotificationClick", key, visibility);
            // NMS applies FLAG_AUTO_CANCEL and all other platform click rules.
            Log.i(TAG, "Native notification click reported to NotificationManagerService");
        } catch (Throwable error) {
            Log.e(TAG, "Native notification click reporting failed", error);
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
