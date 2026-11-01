package dev.q25.bb7pulldown;

import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Observe SystemUI's already filtered and ranked notification shade list.
 *
 * We read package identities only, not message text, actions or private
 * notification data. No native notification view, collection or renderer is
 * modified. Works with NotificationEntry, GroupEntry and Android 17 bundles.
 */
final class SystemUiNotificationObserver {
    private static final String TAG = "BB7-PullDown";
    private static final String BUILDER =
            "com.android.systemui.statusbar.notification.collection.ShadeListBuilder";
    private static volatile List<String> activePackages = Collections.emptyList();
    private static volatile boolean readErrorLogged;

    static List<String> snapshot() {
        return activePackages;
    }

    static void install(ClassLoader loader) {
        Class<?> builder = XposedHelpers.findClassIfExists(BUILDER, loader);
        if (builder == null) {
            Log.i(TAG, "Notification shade-list builder absent in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(builder, "dispatchOnBeforeRenderList",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 1
                                    || !(param.args[0] instanceof List)) return;
                            try {
                                publish((List<?>) param.args[0]);
                            } catch (Throwable error) {
                                if (!readErrorLogged) {
                                    readErrorLogged = true;
                                    Log.w(TAG, "Cannot enumerate notification app packages", error);
                                }
                            }
                        }
                    });
            Log.i(TAG, "Native filtered notification-app observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Notification app observer registration failed", error);
        }
    }

    private static void publish(List<?> entries) {
        List<String> packages = new ArrayList<>();
        for (Object entry : entries) {
            try {
                collect(entry, packages, 0);
            } catch (Throwable error) {
                // One unsupported notification entry must not suppress icons
                // for unrelated applications.
                if (!readErrorLogged) {
                    readErrorLogged = true;
                    Log.w(TAG, "Notification app entry enumeration failed", error);
                }
            }
        }
        List<String> unique = NotificationAppIndex.uniquePackages(packages);
        if (unique.equals(activePackages)) return;
        activePackages = unique;
        Log.i(TAG, "Notification app index updated: apps=" + unique.size());
        PullDownHook.onNotificationPackagesUpdated(unique);
    }

    /** A bundle can contain groups; a group can contain notification entries. */
    private static void collect(Object item, List<String> packages, int depth) {
        if (item == null || depth > 3) return;

        if (item.getClass().getName().endsWith(".NotificationEntry")) {
            // Public API object returned by SystemUI's NotificationEntry.getSbn().
            StatusBarNotification sbn =
                    (StatusBarNotification) XposedHelpers.callMethod(item, "getSbn");
            if (sbn != null) packages.add(sbn.getPackageName());
            return;
        }
        if (item.getClass().getName().endsWith(".GroupEntry")
                || item.getClass().getName().endsWith(".BundleEntry")) {
            Object children = XposedHelpers.callMethod(item, "getChildren");
            if (children instanceof List) {
                for (Object child : (List<?>) children) {
                    collect(child, packages, depth + 1);
                }
            }
            // For a group with no currently displayed children, retain its
            // representative summary notification's application.
            if (item.getClass().getName().endsWith(".GroupEntry")) {
                Object summary = XposedHelpers.callMethod(item, "getRepresentativeEntry");
                if (summary != null) collect(summary, packages, depth + 1);
            }
        }
    }

    private SystemUiNotificationObserver() {}
}
