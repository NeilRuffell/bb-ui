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
    private static volatile WeakReference<Object> systemActivityStarter =
            new WeakReference<>(null);

    // Row lifecycle remains owned by SystemUI. No strong references to rows.
    private static final Map<String, WeakReference<View>> CLICKABLE_ROWS = new HashMap<>();

    static void install(ClassLoader loader) {
        installClickerObserver(loader);
        installActivityStarterObserver(loader);
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

    private static void captureActivityStarter(Object notificationActivityStarter, String source) {
        if (notificationActivityStarter == null) return;
        try {
            Object nativeStarter = XposedHelpers.getObjectField(
                    notificationActivityStarter, "mActivityStarter");
            captureDirectActivityStarter(nativeStarter, source);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to obtain SystemUI native launcher from " + source, error);
        }
    }

    private static void captureDirectActivityStarter(Object nativeStarter, String source) {
        if (nativeStarter == null) return;
        systemActivityStarter = new WeakReference<>(nativeStarter);
        Log.i(TAG, "Native SystemUI ActivityStarter captured via " + source);
    }

    /**
     * Prefer the genuine notification row click. If SystemUI has no native
     * row inflated, ask its already-initialized ActivityStarter to launch
     * the original notification PendingIntent with its standard BAL/options
     * handling. Our module never sends a PendingIntent directly.
     *
     * onDispatched is called when native click dispatches or ActivityStarter's
     * intent-sent callback fires, not as a guarantee the target app opened.
     */
    static void open(String notificationKey, Runnable onDispatched) {
        if (notificationKey == null
                || !SystemUiNotificationObserver.isActive(notificationKey)) {
            Log.i(TAG, "Notification click skipped: notification is no longer active");
            return;
        }
        View row;
        synchronized (CLICKABLE_ROWS) {
            WeakReference<View> reference = CLICKABLE_ROWS.get(notificationKey);
            row = reference == null ? null : reference.get();
        }
        if (row != null) {
            if (!row.hasOnClickListeners() || !row.isEnabled()) {
                Log.i(TAG, "Notification click unavailable: native row is not actionable");
                return;
            }
            try {
                if (row.performClick()) {
                    Log.i(TAG, "Notification click dispatched through native row");
                    onDispatched.run();
                } else {
                    Log.w(TAG, "Native notification row rejected click");
                }
            } catch (Throwable error) {
                Log.e(TAG, "Native notification row click failed", error);
            }
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
            Log.i(TAG, "Dispatching notification via native SystemUI ActivityStarter");
            XposedHelpers.callMethod(starter, "startPendingIntentDismissingKeyguard",
                    action, (Runnable) () -> {
                        Log.i(TAG, "Native SystemUI ActivityStarter intent-sent callback");
                        onDispatched.run();
                    });
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
