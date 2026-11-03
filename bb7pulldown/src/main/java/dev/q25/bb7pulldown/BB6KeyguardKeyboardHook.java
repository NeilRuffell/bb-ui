package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Physical Space requests Android's existing primary credential bouncer.
 *
 * On LineageOS 24.0, NotificationShadeWindowView inherits dispatchKeyEvent
 * from WindowRootView. Hook the DECLARING class, before it dispatches to
 * focused QS tiles or notification controls.
 *
 * No custom credential UI, authentication bypass or synthetic swipe.
 * Disable: adb shell setprop debug.bb7keyguard.enabled false
 *          adb shell am crash com.android.systemui
 */
public final class BB6KeyguardKeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-Keyguard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String BOUNCER_MANAGER =
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String WINDOW_ROOT =
            "com.android.systemui.scene.ui.view.WindowRootView";
    private static final String ENABLE_PROP = "debug.bb7keyguard.enabled";
    private static final KeyguardSpacePolicy SPACE_POLICY = new KeyguardSpacePolicy();

    private static WeakReference<Object> keyguardViewManager = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM_UI.equals(lp.packageName) || !SYSTEM_UI.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP);
            return;
        }

        Class<?> managerClass = XposedHelpers.findClassIfExists(BOUNCER_MANAGER, lp.classLoader);
        if (managerClass == null) {
            Log.w(TAG, "Native keyguard manager absent; Space will be blocked while locked");
        } else {
            try {
                XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        keyguardViewManager = new WeakReference<>(param.thisObject);
                        Log.i(TAG, "Native keyguard manager captured");
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Unable to capture native keyguard manager", error);
            }
        }

        Class<?> root = XposedHelpers.findClassIfExists(WINDOW_ROOT, lp.classLoader);
        if (root == null) {
            Log.e(TAG, "SystemUI WindowRootView absent; stock dispatch retained");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(root, "dispatchKeyEvent", KeyEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View) || param.args.length != 1
                            || !(param.args[0] instanceof KeyEvent)) return;
                    if (handleKey((View) param.thisObject, (KeyEvent) param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            Log.i(TAG, "Installed pre-focus key dispatcher on " + WINDOW_ROOT);
        } catch (Throwable error) {
            Log.e(TAG, "Failed to hook native root key dispatcher", error);
        }
    }

    private static boolean handleKey(View window, KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_SPACE || !enabled()) return false;

        KeyguardManager guard = (KeyguardManager)
                window.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (guard == null) {
            Log.w(TAG, "Keyguard state unavailable; stock dispatch retained");
            return false;
        }
        boolean locked = guard.isKeyguardLocked();
        Object manager = keyguardViewManager.get();
        // Manager presence and visibility-method availability are independent.
        // The Q25 runtime has been observed without primaryBouncerIsShowing().
        // Do not let a missing optional query prevent authentication entry.
        boolean managerReady = manager != null;
        boolean bouncerShowing = false;
        boolean bouncerInTransit = false;
        if (locked && managerReady) {
            Boolean visible = queryBoolean(manager, "isBouncerShowing");
            if (visible == null) {
                visible = queryBoolean(manager, "primaryBouncerIsShowing");
            }
            bouncerShowing = Boolean.TRUE.equals(visible);
            bouncerInTransit = Boolean.TRUE.equals(
                    queryBoolean(manager, "isPrimaryBouncerInTransit"));
        }

        View focused = window.findFocus();
        boolean editorFocused = focused != null && focused.onCheckIsTextEditor();

        int decision = SPACE_POLICY.route(event.getAction(), event.getDownTime(),
                event.getRepeatCount(), locked, bouncerShowing,
                bouncerInTransit, managerReady, editorFocused);
        if (decision == KeyguardSpacePolicy.PASS) return false;

        if (decision == KeyguardSpacePolicy.REQUEST_BOUNCER) {
            try {
                // Do not select a focused control or simulate an unlock swipe.
                requestNativeBouncer(manager);
                Log.i(TAG, "Space consumed; native credential bouncer requested");
            } catch (Throwable error) {
                // Fail closed: never deliver this key to a focused QS tile.
                Log.e(TAG, "Bouncer request failed; lockscreen Space consumed", error);
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && !managerReady && locked) {
            Log.w(TAG, "Space consumed; native keyguard manager not captured");
        }
        return true;
    }

    /** Optional runtime method: device builds do not all expose identical
     * keyguard visibility helpers. Check the method before invoking it.
     */
    private static Boolean queryBoolean(Object manager, String methodName) {
        if (manager == null) return null;
        try {
            for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
                if (method.getName().equals(methodName)
                        && method.getParameterTypes().length == 0
                        && (method.getReturnType() == boolean.class
                            || method.getReturnType() == Boolean.class)) {
                    Object result = XposedHelpers.callMethod(manager, methodName);
                    return result instanceof Boolean ? (Boolean) result : null;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not query optional keyguard state: " + methodName, error);
        }
        return null;
    }

    /** Native SystemUI bouncer methods differ across vendor builds.
     * Prefer primary credentials; fall back to the native bouncer entry.
     */
    private static void requestNativeBouncer(Object manager) {
        if (manager == null) throw new IllegalStateException("No native keyguard manager");
        String reason = "BB UI: physical Space on lockscreen";
        for (String name : new String[] {"showPrimaryBouncer", "showBouncer"}) {
            for (java.lang.reflect.Method method : manager.getClass().getMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2 && params[0] == boolean.class
                        && params[1] == String.class) {
                    XposedHelpers.callMethod(manager, name, true, reason);
                    return;
                }
                if (params.length == 1 && params[0] == boolean.class) {
                    XposedHelpers.callMethod(manager, name, true);
                    return;
                }
            }
        }
        throw new NoSuchMethodError("No compatible native keyguard bouncer entry point");
    }

    private static boolean enabled() {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    properties, "get", ENABLE_PROP, "true"));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read enable property; stock handling retained", error);
            return false;
        }
    }
}
