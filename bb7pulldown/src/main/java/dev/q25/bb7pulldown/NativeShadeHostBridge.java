package dev.q25.bb7pulldown;

import dev.q25.trackpad.theme.BB7Theme;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Native SystemUI window owner for the first BB-UI shade migration stage.
 * Uses compiled Q25 NotificationPanelViewController and its FrameLayout view.
 * No independent WindowManager shade exists while this integration is active.
 *
 * Phase 1 retains BB UI notification/QS renderers as children of the native
 * panel. Phase 2 will migrate those widgets to native rows and QS containers.
 */
final class NativeShadeHostBridge {
    private static final String TAG = "BB7-NativeShade";
    private static final String PROPERTY = "debug.bb7pulldown.native_shade.enabled";
    private static final String CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String PANEL =
            "com.android.systemui.shade.NotificationPanelView";

    private static boolean installed;
    private static volatile WeakReference<Object> controllerRef = new WeakReference<>(null);
    private static volatile WeakReference<FrameLayout> panelRef = new WeakReference<>(null);
    private static volatile WeakReference<UnifiedShadeHost> mountedRef =
            new WeakReference<>(null);
    private static final Map<View, Integer> accessibility = new IdentityHashMap<>();
    private static Boolean observedExpanded;

    private NativeShadeHostBridge() {}

    /** Default off until Q25 hardware testing is complete. */
    static boolean requested() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    props, "getBoolean", PROPERTY, false));
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read native shade mode; retaining working shade", error);
            return false;
        }
    }

    static boolean active() {
        return installed && requested();
    }

    static synchronized void install(ClassLoader loader) {
        if (installed || !requested()) return;
        Class<?> type = XposedHelpers.findClassIfExists(CONTROLLER, loader);
        Class<?> panel = XposedHelpers.findClassIfExists(PANEL, loader);
        if (type == null || panel == null) {
            Log.e(TAG, "Q25 native shade controller/view missing");
            return;
        }
        try {
            type.getDeclaredField("mView");
            type.getDeclaredMethod("onFinishInflate");
            type.getDeclaredMethod("setExpandedFraction", float.class);
            type.getDeclaredMethod("expandToNotifications");
            type.getDeclaredMethod("instantCollapse");
            type.getDeclaredMethod("isExpandedWithoutHeadsUp");
        } catch (NoSuchFieldException | NoSuchMethodException error) {
            Log.e(TAG, "Q25 native shade signature mismatch; no replacement started", error);
            return;
        }
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) capture(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(type, "onFinishInflate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) capture(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(type, "setExpandedFraction",
                    float.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable()) notifyExpansion(param.thisObject);
                        }
                    });
            installed = true;
            Log.i(TAG, "Native SystemUI shade ownership hooks registered");
        } catch (Throwable error) {
            Log.e(TAG, "Failed to install native shade hooks", error);
        }
    }

    private static void capture(Object controller) {
        try {
            Object candidate = XposedHelpers.getObjectField(controller, "mView");
            if (!(candidate instanceof FrameLayout)
                    || !PANEL.equals(candidate.getClass().getName())) return;
            FrameLayout nativePanel = (FrameLayout) candidate;
            FrameLayout prior = panelRef.get();
            controllerRef = new WeakReference<>(controller);
            panelRef = new WeakReference<>(nativePanel);
            if (prior != nativePanel) {
                Log.i(TAG, "Captured native NotificationPanelView");
                UnifiedShadeHost root = mountedRef.get();
                if (root != null && root.getParent() == prior) {
                    int topMargin = root.getLayoutParams() instanceof FrameLayout.LayoutParams
                            ? ((FrameLayout.LayoutParams) root.getLayoutParams()).topMargin : 0;
                    detach(root);
                    if (!attach(root, topMargin)) {
                        Log.w(TAG, "Could not reattach BB child after native view recreation");
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not capture native shade view", error);
        }
    }

    static FrameLayout panel() {
        return panelRef.get();
    }

    static boolean ready() {
        FrameLayout panel = panelRef.get();
        return active() && controllerRef.get() != null && panel != null
                && panel.isAttachedToWindow();
    }

    static boolean attach(UnifiedShadeHost root, int topInset) {
        FrameLayout panel = panelRef.get();
        if (!ready() || root.getParent() != null) return false;
        try {
            accessibility.clear();
            for (int i = 0; i < panel.getChildCount(); i++) {
                View child = panel.getChildAt(i);
                accessibility.put(child, child.getImportantForAccessibility());
                child.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            params.topMargin = Math.max(0, topInset);
            root.setBackgroundColor(BB7Theme.palette(panel.getContext()).canvas);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            root.setTranslationY(0f);
            panel.addView(root, params);
            root.bringToFront();
            mountedRef = new WeakReference<>(root);
            Log.i(TAG, "BB UI mounted inside native NotificationPanelView; one shade window");
            return true;
        } catch (Throwable error) {
            restoreAccessibility();
            Log.e(TAG, "Could not mount BB UI in native shade", error);
            return false;
        }
    }

    static boolean isMounted(UnifiedShadeHost root) {
        return root != null && mountedRef.get() == root && root.getParent() != null;
    }

    static void detach(UnifiedShadeHost root) {
        if (root == null) return;
        if (mountedRef.get() == root) mountedRef = new WeakReference<>(null);
        ViewParent parent = root.getParent();
        try {
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(root);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot detach BB child from native shade", error);
        } finally {
            restoreAccessibility();
        }
    }

    private static void restoreAccessibility() {
        for (Map.Entry<View, Integer> item : accessibility.entrySet()) {
            item.getKey().setImportantForAccessibility(item.getValue());
        }
        accessibility.clear();
    }

    static boolean requestNativeOpen() {
        Object controller = controllerRef.get();
        if (!ready()) return false;
        try {
            // The native gesture may already have started opening this panel.
            if (!Boolean.TRUE.equals(
                    XposedHelpers.callMethod(controller, "isExpandedWithoutHeadsUp"))) {
                XposedHelpers.callMethod(controller, "expandToNotifications");
            }
            Log.i(TAG, "Native SystemUI notification shade expansion requested");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Native shade expansion failed", error);
            return false;
        }
    }

    static void collapseNative() {
        Object controller = controllerRef.get();
        if (controller == null) return;
        try {
            XposedHelpers.callMethod(controller, "instantCollapse");
            Log.i(TAG, "Native shade collapse dispatched");
        } catch (Throwable error) {
            Log.e(TAG, "Native shade collapse failed", error);
        }
    }

    /** Includes stock shade gestures and heads-up downward expansion. */
    private static void notifyExpansion(Object controller) {
        FrameLayout view = panelRef.get();
        if (view == null || controller != controllerRef.get()) return;
        try {
            float fraction = XposedHelpers.getFloatField(controller, "mExpandedFraction");
            boolean expanded = fraction > 0.001f && Boolean.TRUE.equals(
                    XposedHelpers.callMethod(controller, "isExpandedWithoutHeadsUp"));
            if (observedExpanded != null && observedExpanded == expanded) return;
            observedExpanded = expanded;
            view.post(() -> {
                if (controller == controllerRef.get()) {
                    PullDownHook.onNativeShadeExpansionChanged(expanded);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Cannot track native shade expansion", error);
        }
    }
}
