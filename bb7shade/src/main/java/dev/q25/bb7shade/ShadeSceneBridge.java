package dev.q25.bb7shade;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import de.robv.android.xposed.XposedHelpers;

/**
 * Scene-aware navigation through Android's existing StatusBarManager.
 *
 * LineageOS 24 renders notification cards below the viewport when its
 * Compose QuickSettingsScene is current. Closing the legacy QS controller
 * cannot switch that scene. Use the native 'expand notifications panel'
 * request from an explicit BB7 app-rail interaction instead.
 *
 * No notification/card hierarchy is moved, recreated or made transparent.
 * This class never opens a new window.
 */
final class ShadeSceneBridge {
    private static final String TAG = "BB7-Shade";
    private static final long MIN_REQUEST_INTERVAL_MS = 850L;
    private static long lastRequestMs;
    private static boolean warned;

    static void requestNotifications(View rail) {
        if (!ShadeRailHost.isRailActive()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return;
        lastRequestMs = now;

        Object statusbar = rail.getContext().getSystemService(Context.STATUS_BAR_SERVICE);
        if (statusbar == null) {
            logFailure("StatusBarManager unavailable", null);
            return;
        }
        // Post outside the focus-change callback so a scene replacement does
        // not occur during the framework's focus-dispatch traversal.
        rail.post(() -> {
            if (!ShadeRailHost.isRailActive()) return;
            try {
                XposedHelpers.callMethod(statusbar, "expandNotificationsPanel");
                Log.i(TAG, "Requested native notifications shade from app rail");
            } catch (Throwable error) {
                logFailure("Native notifications scene request failed", error);
            }
        });
    }

    private static void logFailure(String message, Throwable error) {
        if (warned) return;
        warned = true;
        Log.w(TAG, message + (error == null ? ""
                : " (" + error.getClass().getSimpleName() + ")"));
    }

    private ShadeSceneBridge() {}
}
