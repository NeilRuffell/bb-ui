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
    private static final String VISIBILITY_PROVIDER =
            "com.android.systemui.statusbar.notification.collection.provider"
                    + ".NotificationVisibilityProviderImpl";

    private static volatile WeakReference<Object> systemActivityStarter =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> clickNotifier =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> visibilityProvider =
            new WeakReference<>(null);

    private static final Map<String, WeakReference<View>> CLICKABLE_ROWS = new HashMap<>();

    static void install(ClassLoader loader) {
        observeConstructor(loader, ACTIVITY_STARTER, "ActivityStarter",
                instance -> systemActivityStarter = new WeakReference<>(instance));
        observeConstructor(loader, CLICK_NOTIFIER, "NotificationClickNotifier",
                instance -> clickNotifier = new WeakReference<>(instance));
        observeConstructor(loader, VISIBILITY_PROVIDER, "NotificationVisibilityProvider",
                instance -> visibilityProvider = new WeakReference<>(instance));

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
        Object provider = visibilityProvider.get();
        if (notifier == null || provider == null) {
            Log.e(TAG, "Native click reporting unavailable: notifier=" + (notifier != null)
                    + " visibilityProvider=" + (provider != null));
            return;
        }
        try {
            // LineageOS's visibility provider obtains the actual notification
            // metadata, or its stock missing-row representation.
            Object visibility = XposedHelpers.callMethod(provider, "obtain", key, true);
            XposedHelpers.callMethod(notifier, "onNotificationClick", key, visibility);
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
