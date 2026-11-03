package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Locked Space = reveal Android's native credential entry.
 *
 * The LineageOS WindowRootView normally dispatches to its children BEFORE
 * WindowRootViewKeyEventHandler, so a focused QS tile can consume Space first.
 * Our hook calls that SAME native handler before any children see the key.
 *
 * This is deliberately not a click, swipe, second overlay, or custom unlock.
 */
public final class BB6KeyguardKeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-Keyguard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MANAGER_CLASS =
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String ROOT_CLASS =
            "com.android.systemui.scene.ui.view.WindowRootView";
    private static final String ENABLE_PROP = "debug.bb7keyguard.enabled";
    // LineageOS StatusBarState.SHADE_LOCKED.
    private static final int SHADE_LOCKED = 2;

    private static final KeyguardSpacePolicy SPACE = new KeyguardSpacePolicy();
    private static WeakReference<Object> keyguardManager = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM_UI.equals(lp.packageName) || !SYSTEM_UI.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP);
            return;
        }

        Class<?> manager = XposedHelpers.findClassIfExists(MANAGER_CLASS, lp.classLoader);
        if (manager != null) {
            try {
                XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        keyguardManager = new WeakReference<>(param.thisObject);
                        Log.i(TAG, "Native keyguard manager captured");
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Native keyguard manager hook failed", error);
            }
        } else {
            Log.w(TAG, "Native keyguard manager class absent");
        }

        Class<?> root = XposedHelpers.findClassIfExists(ROOT_CLASS, lp.classLoader);
        if (root == null) {
            Log.e(TAG, "Native SystemUI key dispatch root absent; stock dispatch retained");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(root, "dispatchKeyEvent", KeyEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)
                            || param.args.length != 1
                            || !(param.args[0] instanceof KeyEvent)) return;
                    if (handleSpace((View) param.thisObject, (KeyEvent) param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            Log.i(TAG, "Pre-focus Space routing installed on " + ROOT_CLASS);
        } catch (Throwable error) {
            Log.e(TAG, "Failed to install SystemUI root key hook", error);
        }
    }

    private static boolean handleSpace(View root, KeyEvent event) {
        if (!enabled() || event.getKeyCode() != KeyEvent.KEYCODE_SPACE) return false;

        KeyguardManager guard = (KeyguardManager)
                root.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (guard == null) {
            Log.e(TAG, "Keyguard state service unavailable");
            return false;
        }

        final boolean showing = guard.isKeyguardLocked();
        final Object manager = keyguardManager.get();
        final View focused = root.findFocus();
        final boolean editing = focused != null && focused.onCheckIsTextEditor();
        final boolean bouncerShowing = showing && isBouncerVisible(manager);

        final int decision = SPACE.route(event.getAction(), event.getDownTime(),
                showing, bouncerShowing, editing);
        if (decision == KeyguardSpacePolicy.PASS) return false;
        if (decision == KeyguardSpacePolicy.CONSUME) return true;

        // Space UP: invoke Android's own keyguard-confirm handling, which normally
        // runs *after* all focusable children in WindowRootView.dispatchKeyEvent.
        // We provide the real, unchanged KeyEvent; no synthetic input is created.
        int state = readStatusBarState(manager);
        boolean afterCollapse = state == SHADE_LOCKED && schedulePostCollapse(manager, root);

        boolean nativeHandled = dispatchToNativeKeyguard(root, event);
        Log.i(TAG, "Space routed to native keyguard; state=" + state
                + ", accepted=" + nativeHandled + ", postCollapse=" + afterCollapse);

        if (nativeHandled && (state != SHADE_LOCKED || afterCollapse)) return true;

        // If the SystemUI handler declined (or the expanded locked shade could
        // only collapse), ask the same native manager for credential entry.
        // Never allow this Space UP to activate the previously focused tile.
        if (!nativeHandled || !afterCollapse) {
            requestNativeBouncer(manager, "BB UI: physical Space");
        }
        return true;
    }

    private static boolean dispatchToNativeKeyguard(View root, KeyEvent event) {
        try {
            Object handler = XposedHelpers.getObjectField(root, "windowRootViewKeyEventHandler");
            if (handler == null) {
                Log.w(TAG, "Native WindowRootView key handler not initialized");
                return false;
            }
            Object result = XposedHelpers.callMethod(handler, "dispatchKeyEvent", event);
            return Boolean.TRUE.equals(result);
        } catch (Throwable error) {
            Log.e(TAG, "Could not route Space through native keyguard handler", error);
            return false;
        }
    }

    private static int readStatusBarState(Object manager) {
        try {
            if (manager == null) return -1;
            Object controller = XposedHelpers.getObjectField(
                    manager, "mStatusBarStateController");
            Object value = XposedHelpers.callMethod(controller, "getState");
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read lockscreen shade state", error);
            return -1;
        }
    }

    /** Android's locked shade consumes confirm keys by collapsing its shade.
     * Queue credential entry AFTER that collapse, so one Space press is enough.
     */
    private static boolean schedulePostCollapse(Object manager, View root) {
        try {
            if (manager == null) return false;
            Object lazy = XposedHelpers.getObjectField(manager, "mShadeController");
            Object shadeController = XposedHelpers.callMethod(lazy, "get");
            Runnable showAfterCollapse = () -> {
                KeyguardManager guard = (KeyguardManager) root.getContext()
                        .getSystemService(Context.KEYGUARD_SERVICE);
                if (guard == null || !guard.isKeyguardLocked()) return;
                if (isBouncerVisible(manager)) return;
                requestNativeBouncer(manager, "BB UI: locked shade collapsed");
            };
            XposedHelpers.callMethod(shadeController, "addPostCollapseAction",
                    showAfterCollapse);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to queue native bouncer after shade collapse", error);
            return false;
        }
    }

    private static boolean isBouncerVisible(Object manager) {
        if (manager == null) return false;
        for (String name : new String[] {
                "isBouncerShowing", "primaryBouncerIsOrWillBeShowing",
                "primaryBouncerIsShowing"
        }) {
            Boolean value = queryBoolean(manager, name);
            if (value != null) return value;
        }
        // Vendor framework may omit manager convenience methods.
        try {
            Object interactor = XposedHelpers.getObjectField(
                    manager, "mPrimaryBouncerInteractor");
            for (String name : new String[] {
                    "isBouncerShowing", "isFullyShowing", "isInTransit"
            }) {
                Boolean value = queryBoolean(interactor, name);
                if (Boolean.TRUE.equals(value)) return true;
            }
        } catch (Throwable ignored) {
            // The focused password editor remains a separate exemption.
        }
        return false;
    }

    private static Boolean queryBoolean(Object instance, String name) {
        if (instance == null) return null;
        for (Method method : instance.getClass().getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterTypes().length == 0) {
                try {
                    Object value = XposedHelpers.callMethod(instance, name);
                    return value instanceof Boolean ? (Boolean) value : null;
                } catch (Throwable error) {
                    Log.w(TAG, "Native bouncer visibility query failed: " + name, error);
                    return null;
                }
            }
        }
        return null;
    }

    private static void requestNativeBouncer(Object manager, String reason) {
        if (manager == null) {
            Log.e(TAG, "Native bouncer unavailable: keyguard manager not captured");
            return;
        }
        for (String name : new String[] {"showPrimaryBouncer", "showBouncer"}) {
            for (Method method : manager.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] p = method.getParameterTypes();
                try {
                    if (p.length == 2 && p[0] == boolean.class && p[1] == String.class) {
                        XposedHelpers.callMethod(manager, name, true, reason);
                        Log.i(TAG, "Requested native credential entry via " + name);
                        return;
                    }
                    if (p.length == 1 && p[0] == boolean.class) {
                        XposedHelpers.callMethod(manager, name, true);
                        Log.i(TAG, "Requested native credential entry via " + name);
                        return;
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "Native credential entry failed: " + name, error);
                }
            }
        }
        Log.e(TAG, "No available native bouncer entry method");
    }

    private static boolean enabled() {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    props, "get", ENABLE_PROP, "true"));
        } catch (Throwable error) {
            Log.w(TAG, "Unable to read keyguard enable property", error);
            return false;
        }
    }
}
