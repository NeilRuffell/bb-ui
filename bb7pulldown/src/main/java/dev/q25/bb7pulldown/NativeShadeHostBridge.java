package dev.q25.bb7pulldown;

import android.util.Log;
import android.graphics.Rect;
import android.view.MotionEvent;
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
 * Keeps Q25's native NotificationShadeWindowView, notification stack and QS
 * backend, but mounts the BB UI presentation at the WINDOW root so it covers
 * the native notification stack (a sibling of NotificationPanelView).
 * No independent WindowManager shade is created in this mode.
 *
 * Touches in an active BB session are routed to its existing trackpad/touch
 * controls before native shade touch handlers can start another expansion.
 * Phase 2 will migrate duplicate BB rows and QS layouts to native views.
 */
final class NativeShadeHostBridge {
    private static final String TAG = "BB7-NativeShade";
    private static final String PROPERTY = "debug.bb7pulldown.native_shade.enabled";
    private static final String CONTROLLER =
            "com.android.systemui.shade.NotificationPanelViewController";
    private static final String PANEL =
            "com.android.systemui.shade.NotificationPanelView";
    private static final String SHADE_WINDOW =
            "com.android.systemui.shade.NotificationShadeWindowView";

    private static boolean installed;
    private static volatile WeakReference<Object> controllerRef = new WeakReference<>(null);
    private static volatile WeakReference<FrameLayout> panelRef = new WeakReference<>(null);
    private static volatile WeakReference<FrameLayout> shadeWindowRef =
            new WeakReference<>(null);
    private static volatile WeakReference<UnifiedShadeHost> mountedRef =
            new WeakReference<>(null);
    private static final Map<View, Integer> accessibility = new IdentityHashMap<>();
    private static volatile Boolean observedExpanded;
    private static boolean firstTouchRoutedLogged;
    private static FrameLayout clippedWindow;
    private static Rect originalWindowClip;
    private static int reservedStatusBandHeight;
    private static final View.OnLayoutChangeListener WINDOW_CLIP_LAYOUT =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (view == clippedWindow) updateWindowClip();
            };
    // A gesture that opened SystemUI's shade does not have an ACTION_DOWN
    // delivered to the BB child. Swallow its tail instead of sending an
    // orphaned MOVE/UP event or continuing the native drag.
    private static boolean nativeGestureInProgress;
    private static boolean bbGestureInProgress;

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
        Class<?> window = XposedHelpers.findClassIfExists(SHADE_WINDOW, loader);
        if (type == null || panel == null || window == null) {
            Log.e(TAG, "Q25 native shade controller/view missing");
            return;
        }
        // Reuse the original SystemUI status-bar visibility pipeline rather
        // than drawing replacement clocks/icons or reparenting native views.
        NativeShadeStatusBarVisibility.install(loader);
        NativeShadeBlurPolicyHook.install(loader);
        try {
            type.getDeclaredField("mView");
            type.getDeclaredMethod("onFinishInflate");
            type.getDeclaredMethod("setExpandedFraction", float.class);
            type.getDeclaredMethod("setExpandedHeightInternal", float.class);
            type.getDeclaredMethod("expandToNotifications");
            type.getDeclaredMethod("instantCollapse");
            type.getDeclaredMethod("isExpandedWithoutHeadsUp");
            window.getDeclaredMethod("onAttachedToWindow");
            window.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
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
            // Ordinary finger gestures (including heads-up drag-down) can
            // update mExpandedFraction through this method without calling
            // setExpandedFraction(). Both paths must be observed.
            XposedHelpers.findAndHookMethod(type, "setExpandedHeightInternal",
                    float.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable()) notifyExpansion(param.thisObject);
                        }
                    });
            // The NotificationStackScrollLayout is a sibling of
            // NotificationPanelView in Q25's super_notification_shade.xml.
            // Observe and modify the actual existing window root instead.
            XposedHelpers.findAndHookMethod(window, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable() && param.thisObject instanceof FrameLayout) {
                                captureWindow((FrameLayout) param.thisObject);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(window, "dispatchTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 1
                                    || !(param.args[0] instanceof MotionEvent)
                                    || !(param.thisObject instanceof FrameLayout)) return;
                            if (routeWindowTouch((FrameLayout) param.thisObject,
                                    (MotionEvent) param.args[0])) {
                                // Prevent stock notification/QS expansion touch handling
                                // after the BB host becomes the visible surface.
                                param.setResult(true);
                            }
                        }
                    });
            installed = true;
            Log.i(TAG, "Native SystemUI shade-window ownership hooks registered");
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
            FrameLayout previous = panelRef.get();
            controllerRef = new WeakReference<>(controller);
            panelRef = new WeakReference<>(nativePanel);
            if (previous != nativePanel) {
                Log.i(TAG, "Captured native NotificationPanelView controller");
            }
            // If the native window is already attached, discover it through
            // the actual view hierarchy. Otherwise onAttachedToWindow will
            // capture it once SystemUI creates its window.
            ViewParent ancestor = nativePanel.getParent();
            while (ancestor instanceof View) {
                if (SHADE_WINDOW.equals(ancestor.getClass().getName())) {
                    captureWindow((FrameLayout) ancestor);
                    break;
                }
                ancestor = ancestor.getParent();
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not capture native shade view", error);
        }
    }

    private static void captureWindow(FrameLayout window) {
        if (!SHADE_WINDOW.equals(window.getClass().getName())) return;
        FrameLayout previous = shadeWindowRef.get();
        shadeWindowRef = new WeakReference<>(window);
        if (previous == window) return;
        Log.i(TAG, "Captured native NotificationShadeWindowView root");

        UnifiedShadeHost mounted = mountedRef.get();
        if (mounted != null && mounted.getParent() == previous && previous != null) {
            final int topInset = mounted.getPaddingTop();
            // Native root reinflation: preserve the BB session, then remount
            // in the new native window only once its view token is ready.
            window.post(() -> {
                if (shadeWindowRef.get() != window || mountedRef.get() != mounted) return;
                if (mounted.getParent() == previous) {
                    detach(mounted);
                    if (!attach(mounted, topInset)) {
                        Log.e(TAG, "Unable to migrate BB host to new native shade window");
                        PullDownHook.onNativeShadeHostUnavailable();
                    }
                }
            });
        }
    }

    static FrameLayout shadeWindow() {
        return shadeWindowRef.get();
    }

    static boolean ownsNativeShadeWindow() {
        UnifiedShadeHost bbRoot = mountedRef.get();
        FrameLayout nativeWindow = shadeWindowRef.get();
        return active() && bbRoot != null && nativeWindow != null
                && bbRoot.getParent() == nativeWindow;
    }

    static FrameLayout panel() {
        return panelRef.get();
    }

    static boolean ready() {
        FrameLayout window = shadeWindowRef.get();
        return active() && controllerRef.get() != null && panelRef.get() != null
                && window != null && window.isAttachedToWindow();
    }

    static boolean attach(UnifiedShadeHost root, int topInset) {
        FrameLayout window = shadeWindowRef.get();
        if (!ready() || window == null || root.getParent() != null) return false;
        try {
            accessibility.clear();
            for (int i = 0; i < window.getChildCount(); i++) {
                View child = window.getChildAt(i);
                accessibility.put(child, child.getImportantForAccessibility());
                child.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // The BB canvas covers the entire native window so its sibling
            // notification stack/scrims cannot show around it. Interior
            // content begins below the original status-bar height.
            root.setPadding(0, Math.max(0, topInset), 0, 0);
            // Keep only the reserved native status-bar band transparent.
            // BB notification and QS pages already own their opaque canvas
            // below this padding; a root-wide opaque background was hiding
            // the genuine SystemUI status-bar window underneath.
            root.setBackground(null);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setClickable(true);
            root.setTranslationY(0f);
            window.addView(root, params);
            root.bringToFront();
            mountedRef = new WeakReference<>(root);
            beginWindowClip(window, topInset);
            NativeShadeStatusBarVisibility.onBbShadeMountChanged(true);
            Log.i(TAG, "Native status-bar region left transparent; original icons retained");
            // Do not reset nativeGestureInProgress: a heads-up or native
            // downward swipe may still be holding the pointer at mount time.
            // Its MOVE/UP must be consumed, not sent without an ACTION_DOWN.
            Log.i(TAG, "BB UI mounted at native NotificationShadeWindowView root;"
                    + " stock notification stack covered; one shade window");
            return true;
        } catch (Throwable error) {
            ViewParent parent = root.getParent();
            if (parent instanceof ViewGroup) {
                try { ((ViewGroup) parent).removeView(root); }
                catch (Throwable ignored) { }
            }
            endWindowClip();
            if (mountedRef.get() == root) {
                mountedRef = new WeakReference<>(null);
                NativeShadeStatusBarVisibility.onBbShadeMountChanged(false);
            }
            restoreAccessibility();
            Log.e(TAG, "Could not mount BB UI at native shade-window root", error);
            return false;
        }
    }

    static boolean isMounted(UnifiedShadeHost root) {
        // Keep ownership when the native parent is unexpectedly detached;
        // cleanup must still release the BB session and its media observers.
        return root != null && mountedRef.get() == root;
    }

    static void detach(UnifiedShadeHost root) {
        if (root == null) return;
        if (mountedRef.get() == root) {
            mountedRef = new WeakReference<>(null);
            NativeShadeStatusBarVisibility.onBbShadeMountChanged(false);
            endWindowClip();
        }
        ViewParent parent = root.getParent();
        try {
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(root);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot detach BB child from native shade", error);
        } finally {
            restoreAccessibility();
        }
    }

    /**
     * Native shade scrims and QS/notification headers are siblings of the
     * BB presentation. Even with a transparent BB root, they can tint or
     * blur the reserved status-bar band behind it. Prevent the native
     * notification-shade WINDOW from drawing any pixels in that band.
     * The separately owned SystemUI status-bar window then remains visible.
     *
     * This does not remove, reparent or duplicate the status-bar clock/icons
     * or change the native shade's notification and QS services.
     */
    private static void beginWindowClip(FrameLayout window, int statusBandPx) {
        endWindowClip();
        clippedWindow = window;
        originalWindowClip = window.getClipBounds();
        reservedStatusBandHeight = Math.max(0, statusBandPx);
        window.addOnLayoutChangeListener(WINDOW_CLIP_LAYOUT);
        updateWindowClip();
        Log.i(TAG, "Native shade drawing clipped below status bar height="
                + reservedStatusBandHeight + "px");
    }

    private static void updateWindowClip() {
        FrameLayout window = clippedWindow;
        if (window == null) return;
        int width = window.getWidth();
        int height = window.getHeight();
        if (width <= 0 || height <= 0) return;
        int[] bounds = NativeShadeStatusBandPolicy.clipBounds(
                width, height, reservedStatusBandHeight);
        window.setClipBounds(new Rect(bounds[0], bounds[1], bounds[2], bounds[3]));
    }

    private static void endWindowClip() {
        FrameLayout window = clippedWindow;
        if (window == null) return;
        window.removeOnLayoutChangeListener(WINDOW_CLIP_LAYOUT);
        window.setClipBounds(originalWindowClip);
        clippedWindow = null;
        originalWindowClip = null;
        reservedStatusBandHeight = 0;
        Log.i(TAG, "Native shade window drawing bounds restored");
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
            // A native gesture can be at a very small expansion fraction.
            // The previous implementation skipped completion as soon as it
            // saw any nonzero expansion, leaving a partly expanded stock shade.
            if (!Boolean.TRUE.equals(
                    XposedHelpers.callMethod(controller, "isExpandedWithoutHeadsUp"))) {
                XposedHelpers.callMethod(controller, "expandToNotifications");
            }
            float fraction = XposedHelpers.getFloatField(controller, "mExpandedFraction");
            if (fraction < 0.999f) {
                XposedHelpers.callMethod(controller, "setExpandedFraction", 1f);
            }
            Log.i(TAG, "Native SystemUI notification shade expanded for BB UI"
                    + " (previous fraction=" + fraction + ")");
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


    /**
     * Route touches at the existing native shade window, before SystemUI's
     * InteractionEventHandler can deliver them to the stock stack/QS panel.
     * We do not modify or synthesize any native notification gestures when
     * the BB shade is closed.
     */
    private static boolean routeWindowTouch(FrameLayout window, MotionEvent source) {
        if (!active() || window != shadeWindowRef.get()) return false;
        UnifiedShadeHost bb = mountedRef.get();
        boolean mounted = bb != null && bb.getParent() == window;
        int action = source.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            nativeGestureInProgress = !mounted;
            bbGestureInProgress = mounted;
        }
        if (!mounted) {
            // If BB UI was just detached in the middle of a touch, do not
            // deliver the trailing MOVE/UP to the stock shade.
            boolean consume = bbGestureInProgress;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                bbGestureInProgress = false;
                nativeGestureInProgress = false;
            }
            return consume;
        }
        if (NativeShadeModePolicy.swallowOpeningGestureTail(
                mounted, nativeGestureInProgress, action == MotionEvent.ACTION_DOWN)) {
            // Initial native pull-down started before BB UI was mounted.
            // It has no matching DOWN in BB; swallow the gesture tail.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                nativeGestureInProgress = false;
            }
            return true;
        }
        MotionEvent translated = MotionEvent.obtain(source);
        try {
            translated.offsetLocation(window.getScrollX() - bb.getLeft(),
                    window.getScrollY() - bb.getTop());
            bb.dispatchTouchEvent(translated);
            if (!firstTouchRoutedLogged) {
                firstTouchRoutedLogged = true;
                Log.i(TAG, "Native shade-window touch now routed exclusively to BB UI");
            }
        } catch (Throwable error) {
            Log.e(TAG, "BB touch dispatch failed; blocking stock shade interaction", error);
        } finally {
            translated.recycle();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                bbGestureInProgress = false;
                nativeGestureInProgress = false;
            }
        }
        return true;
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
            Log.i(TAG, "Native panel expansion changed: expanded=" + expanded
                    + " fraction=" + fraction);
            view.post(() -> {
                // Ignore stale posted transitions when a gesture reverses or
                // opens while an earlier collapsed callback is still queued.
                if (controller == controllerRef.get()
                        && Boolean.valueOf(expanded).equals(observedExpanded)) {
                    PullDownHook.onNativeShadeExpansionChanged(expanded);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Cannot track native shade expansion", error);
        }
    }
}
