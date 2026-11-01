package dev.q25.bb7shade;

import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Passive LineageOS 24 SystemUI integration for the BB7 shade.
 *
 * Read the already filtered/ordered notifications directly at the render
 * boundary. Do not depend on NotifPipeline's constructor: on the Q25, that
 * constructor finished before our constructor observer was registered.
 *
 * Stage 2 remains headless until a complete two-page replacement can be
 * activated atomically. The existing shade and notification behavior are
 * not modified.
 */
public final class ShadeIntegrationHook implements IXposedHookLoadPackage {
    private static final String PACKAGE = "com.android.systemui";
    private static final String TAG = "BB7-Shade";
    private static final String LIST_BUILDER =
            "com.android.systemui.statusbar.notification.collection.ShadeListBuilder";
    private static final String WINDOW =
            "com.android.systemui.shade.NotificationShadeWindowView";
    private static final String NATIVE_STACK =
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";

    private static final Map<View, Boolean> MEASURED_STACKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        Log.i(TAG, "BB7 Shade stage 2 loaded; stock shade remains the only visible UI");
        observeFinalRenderList(lp);
        observeShadeHost(lp);
        observeNativeStack(lp);
    }

    /**
     * ShadeListBuilder.dispatchOnBeforeRenderList is invoked during every
     * completed shade-list build, after Android's grouping and filtering.
     * Hooking the method directly avoids missing an already-built pipeline.
     */
    private static void observeFinalRenderList(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> builder = XposedHelpers.findClassIfExists(LIST_BUILDER, lp.classLoader);
        if (builder == null) {
            Log.w(TAG, "ShadeListBuilder not available in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(builder, "dispatchOnBeforeRenderList",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 1
                                    || !(param.args[0] instanceof java.util.List)) return;
                            try {
                                ShadeNotificationIndex.update(
                                        (java.util.List<?>) param.args[0]);
                            } catch (Throwable error) {
                                // Indexing must never prevent native rendering.
                                Log.e(TAG, "Notification index update failed", error);
                            }
                        }
                    });
            Log.i(TAG, "Finalized notification render-list observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Finalized render-list observer unavailable", error);
        }
    }

    private static void observeShadeHost(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> shadeWindow = XposedHelpers.findClassIfExists(WINDOW, lp.classLoader);
        if (shadeWindow == null) {
            Log.w(TAG, "Shade window unavailable in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(shadeWindow, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "Native notification shade window attached");
                        }
                    });
            Log.i(TAG, "Shade host observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Shade window observer registration failed", error);
        }
    }

    /**
     * Probe the original notification container, which is managed by Android.
     * Future layout work requires its actual on-device position and parent,
     * particularly while Compose scene-container variants are in use.
     */
    private static void observeNativeStack(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> stack = XposedHelpers.findClassIfExists(NATIVE_STACK, lp.classLoader);
        if (stack == null) {
            Log.w(TAG, "Native notification stack unavailable in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(stack, "onLayout", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View view = (View) param.thisObject;
                    if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
                    if (view instanceof android.view.ViewGroup) {
                        try {
                            ShadeNotificationIndex.updateFromNativeRows(
                                    (android.view.ViewGroup) view);
                        } catch (Throwable error) {
                            Log.e(TAG, "Cannot bootstrap native notification index", error);
                        }
                    }
                    synchronized (MEASURED_STACKS) {
                        if (MEASURED_STACKS.containsKey(view)) return;
                        MEASURED_STACKS.put(view, Boolean.TRUE);
                    }
                    ViewParent parent = view.getParent();
                    int[] coordinates = new int[2];
                    view.getLocationOnScreen(coordinates);
                    Log.i(TAG, "Native stack geometry: width=" + view.getWidth()
                            + " height=" + view.getHeight()
                            + " screenX=" + coordinates[0]
                            + " screenY=" + coordinates[1]
                            + " parent=" + (parent == null ? "null"
                                    : parent.getClass().getName()));
                }
            });
            // A notification may arrive or disappear while the shade is
            // closed; view-child events keep the passive native index fresh.
            XC_MethodHook childObserver = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof android.view.ViewGroup)) return;
                    try {
                        ShadeNotificationIndex.updateFromNativeRows(
                                (android.view.ViewGroup) param.thisObject);
                    } catch (Throwable error) {
                        Log.e(TAG, "Native notification row observer failed", error);
                    }
                }
            };
            XposedBridge.hookAllMethods(stack, "onViewAdded", childObserver);
            XposedBridge.hookAllMethods(stack, "onViewRemoved", childObserver);
            Log.i(TAG, "Native stack geometry and child observers registered");
        } catch (Throwable error) {
            Log.e(TAG, "Native stack geometry hook unavailable", error);
        }
    }
}
