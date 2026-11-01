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

    private static volatile boolean sPanelKeyguardReadWarningLogged;

    private static final Map<View, Boolean> MEASURED_STACKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;
        Log.i(TAG, "BB7 Shade stage 3.6 loaded; direct native scene controller integration");
        ShadeSceneBridge.installHooks(lp.classLoader);
        observeFinalRenderList(lp);
        observeShadeHost(lp);
        observeNativeStack(lp);
        observeKeyguardLifecycle(lp);
        observeNotificationStackAlpha(lp);
        ShadeRailHost.installHooks(lp.classLoader);
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
     * The Q25 confirms that onFinishInflate mounts our rail, but its first
     * keyguard query runs while SystemUI is starting and returns locked.
     * The notification stack may never lay out again after unlock.
     * Do not reflectively call isShowing() on the Q25; Android 17's
     * optimized SystemUI has removed that method.
     *
     * Observe the *same* KeyguardStateControllerImpl that SystemUI uses,
     * plus the actual shade expansion lifecycle to handle an already
     * unlocked device. Neither hook changes platform state or input.
     */
    private static void observeKeyguardLifecycle(
            XC_LoadPackage.LoadPackageParam lp) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
                lp.classLoader);
        if (controller != null) {
            try {
                XposedBridge.hookAllMethods(controller, "notifyKeyguardState",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    // The Q25's optimized SystemUI does not
                                    // expose isShowing(). The original method
                                    // already receives 'showing' as arg 0.
                                    if (param.args.length < 1
                                            || !(param.args[0] instanceof Boolean)) return;
                                    boolean showing = (Boolean) param.args[0];
                                    ShadeRailHost.onSystemUiKeyguardState(
                                            showing, "keyguard-state-change");
                                } catch (Throwable error) {
                                    Log.e(TAG, "Unable to read native keyguard transition",
                                            error);
                                }
                            }
                        });
                Log.i(TAG, "Keyguard state-change observer registered");
            } catch (Throwable error) {
                Log.e(TAG, "Keyguard state-change hook unavailable", error);
            }
        }

        Class<?> shadePanel = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.NotificationPanelViewController",
                lp.classLoader);
        if (shadePanel != null) {
            try {
                XposedBridge.hookAllMethods(
                        shadePanel, "updateExpansionAndVisibility", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object keyguard = XposedHelpers.getObjectField(
                                            param.thisObject, "mKeyguardStateController");
                                    // Source: KeyguardStateControllerImpl
                                    // stores current state in mShowing.
                                    // Directly read it; the accessor was
                                    // removed from this Q25 system image.
                                    if (keyguard == null) return;
                                    boolean showing = XposedHelpers.getBooleanField(
                                            keyguard, "mShowing");
                                    ShadeRailHost.onSystemUiKeyguardState(
                                            showing, "shade-expansion");
                                    ShadeGeometryProbe.onPanelExpansion(
                                            param.thisObject, showing);
                                } catch (Throwable error) {
                                    // Do not interrupt stock shade expansion.
                                    if (!sPanelKeyguardReadWarningLogged) {
                                        sPanelKeyguardReadWarningLogged = true;
                                        Log.e(TAG, "Unable to read shade keyguard state",
                                                error);
                                    }
                                }
                            }
                        });
                Log.i(TAG, "Shade expansion keyguard observer registered");
            } catch (Throwable error) {
                Log.e(TAG, "Shade expansion observation unavailable", error);
            }
        }
    }

    /**
     * SystemUI computes the native notification stack alpha as the minimum
     * of multiple controller-owned factors. Read and log those factors
     * at the exact point they are applied; never override privacy,
     * keyguard, or scene visibility by manually setting view alpha.
     */
    private static void observeNotificationStackAlpha(
            XC_LoadPackage.LoadPackageParam lp) {
        Class<?> controller = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.notification.stack."
                        + "NotificationStackScrollLayoutController", lp.classLoader);
        if (controller == null) return;
        try {
            XposedBridge.hookAllMethods(controller, "updateAlpha",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                ShadeGeometryProbe.onControllerAlpha(
                                        param.thisObject);
                            } catch (Throwable error) {
                                // Preserve normal SystemUI alpha calculation.
                                Log.w(TAG, "Notification alpha diagnostics unavailable ("
                                        + error.getClass().getSimpleName() + ")");
                            }
                        }
                    });
            Log.i(TAG, "Native notification alpha-source observer registered");
        } catch (Throwable error) {
            Log.w(TAG, "Native alpha-source observer unavailable ("
                    + error.getClass().getSimpleName() + ")");
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
                    ShadeGeometryProbe.onStackLayout(view);
                    // Only the actual native stack is touched. Keyguard
                    // transitions restore the platform's original layout.
                    try {
                        ShadeRailHost.onNativeStackLayout(view);
                    } catch (Throwable error) {
                        Log.e(TAG, "Cannot update BB7 rail keyguard state", error);
                    }
                    if (view instanceof android.view.ViewGroup) {
                        try {
                            ShadeNotificationIndex.updateFromNativeRows(
                                    (android.view.ViewGroup) view, false);
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
                                (android.view.ViewGroup) param.thisObject, true);
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
