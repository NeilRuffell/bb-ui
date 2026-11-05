package dev.q25.bb7pulldown;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Globally remove the stock SystemUI notification-stack horizontal inset.
 *
 * This is deliberately NOT gated on a pinned heads-up notification, a
 * collapsed shade, or a particular notification type. The BB UI design
 * replaces Android's stock spacing in the shared native notification stack,
 * so heads-up popups and any stock notification rows use the same full width.
 *
 * Verified in Q25 SystemUI classes2.dex:
 *   NotificationStackScrollLayout.initView(Context, NotificationSwipeHelper, NotificationStackSizeCalculator)
 *   NotificationStackScrollLayout.onMeasure(int, int)
 *   NotificationStackScrollLayout.mMinimumPaddings
 *   NotificationStackScrollLayout.mSidePaddings
 *   NotificationStackScrollLayout.mFullWidthNotificationsInLandscape
 *
 * Upstream LineageOS sets mMinimumPaddings from
 * notification_side_paddings_single and derives mSidePaddings from it.
 * Q25 R8 inlines updateSidePadding() into onMeasure; modifying the field
 * used by that native calculation removes the inset at its source.
 * No X translations, scaling, reparenting, or negative margins are used.
 */
final class NativeFullWidthNotifications {
    private static final String TAG = "BB7-NotifWidth";
    private static final String PROP = "debug.bb7pulldown.notification_full_width.enabled";
    private static final String STACK =
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";

    private static boolean installed;
    private static boolean measuredLogged;
    private static boolean unexpectedInsetLogged;

    private NativeFullWidthNotifications() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Native full-width notification layout disabled by " + PROP);
            return;
        }

        Class<?> stack = XposedHelpers.findClassIfExists(STACK, loader);
        if (stack == null) {
            Log.w(TAG, "Q25 native notification stack missing; stock spacing retained");
            return;
        }
        // The uploaded Q25 SystemUI classes2.dex has a THREE-argument initView.
        // Its previous zero-argument preflight was incorrect and disabled this
        // entire feature before any hooks were installed.
        Class<?> swipeHelper = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper",
                loader);
        Class<?> sizeCalculator = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator",
                loader);
        if (swipeHelper == null || sizeCalculator == null) {
            Log.w(TAG, "Q25 native stack initialization parameters unavailable");
            return;
        }
        try {
            stack.getDeclaredMethod("initView", Context.class, swipeHelper, sizeCalculator);
            stack.getDeclaredMethod("onMeasure", int.class, int.class);
            stack.getDeclaredField("mMinimumPaddings");
            stack.getDeclaredField("mSidePaddings");
            stack.getDeclaredField("mFullWidthNotificationsInLandscape");
        } catch (NoSuchMethodException | NoSuchFieldException error) {
            Log.w(TAG, "Q25 notification width API changed; stock spacing retained", error);
            return;
        }

        try {
            // Remove the underlying resource-derived value once native
            // stack initialization has finished, for every notification stack.
            XposedHelpers.findAndHookMethod(stack, "initView",
                    Context.class, swipeHelper, sizeCalculator, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            forceFullWidth(param.thisObject);
                        }
                    });

            // Also enforce at the actual measurement source so that any
            // resource/configuration reload cannot reintroduce the gutter.
            XposedHelpers.findAndHookMethod(stack, "onMeasure",
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            forceFullWidth(param.thisObject);
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()) return;
                            try {
                                int sidePadding = XposedHelpers.getIntField(
                                        param.thisObject, "mSidePaddings");
                                if (sidePadding == 0 && !measuredLogged) {
                                    measuredLogged = true;
                                    Log.i(TAG, "Native notification stack measured with zero side inset");
                                } else if (sidePadding != 0 && !unexpectedInsetLogged) {
                                    unexpectedInsetLogged = true;
                                    Log.w(TAG, "Native notification stack still reports side inset="
                                            + sidePadding + "px; inspect Q25 measurement path");
                                }
                            } catch (Throwable error) {
                                Log.w(TAG, "Unable to inspect native stack side inset", error);
                            }
                        }
                    });

            installed = true;
            Log.i(TAG, "Global native notification side inset removal registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install global native notification layout hook", error);
        }
    }

    private static void forceFullWidth(Object stack) {
        try {
            XposedHelpers.setIntField(stack, "mMinimumPaddings", 0);
            // The native landscape path otherwise adds QS-tile alignment
            // padding even when mMinimumPaddings is zero.
            XposedHelpers.setBooleanField(stack, "mFullWidthNotificationsInLandscape", true);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot override native notification side-padding source", error);
        }
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    props, "getBoolean", PROP, true));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read notification width rollback property; using stock spacing",
                    error);
            return false;
        }
    }
}
