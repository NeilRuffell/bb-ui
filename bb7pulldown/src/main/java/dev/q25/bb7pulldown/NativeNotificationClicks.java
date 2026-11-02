package dev.q25.bb7pulldown;

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
 * Forward custom notification-card activation to the native SystemUI row.
 *
 * NotificationClicker.register(...) attaches SystemUI's genuine click
 * listener to each native ExpandableNotificationRow. Reusing View.performClick()
 * executes that listener unchanged, including EntryAdapter's notification
 * activity starter, bubble behavior, NMS click reporting and auto-cancel
 * handling. It does NOT send PendingIntents or launch activities itself.
 */
final class NativeNotificationClicks {
    private static final String TAG = "BB7-PullDown";
    private static final String CLICKER_CLASS =
            "com.android.systemui.statusbar.notification.NotificationClicker";

    // Row lifecycle remains owned by SystemUI. No strong references to rows.
    private static final Map<String, WeakReference<View>> CLICKABLE_ROWS = new HashMap<>();

    static void install(ClassLoader loader) {
        Class<?> clicker = XposedHelpers.findClassIfExists(CLICKER_CLASS, loader);
        if (clicker == null) {
            Log.i(TAG, "Native NotificationClicker absent in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(clicker, "register", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
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
            Log.i(TAG, "Native notification click forwarding registered");
        } catch (Throwable e) {
            Log.e(TAG, "Unable to observe native notification click registration", e);
        }
    }

    static boolean open(String notificationKey) {
        if (notificationKey == null || !SystemUiNotificationObserver.isActive(notificationKey)) {
            Log.i(TAG, "Notification click skipped: notification is no longer active");
            return false;
        }
        View row;
        synchronized (CLICKABLE_ROWS) {
            WeakReference<View> reference = CLICKABLE_ROWS.get(notificationKey);
            row = reference == null ? null : reference.get();
        }
        if (row == null) {
            Log.i(TAG, "Notification click unavailable: no native row registered");
            return false;
        }
        if (!row.hasOnClickListeners() || !row.isEnabled()) {
            Log.i(TAG, "Notification click unavailable: native row not actionable");
            return false;
        }
        try {
            boolean dispatched = row.performClick();
            Log.i(TAG, "Native notification click performed=" + dispatched);
            return dispatched;
        } catch (Throwable e) {
            Log.e(TAG, "Native notification click failed", e);
            return false;
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
