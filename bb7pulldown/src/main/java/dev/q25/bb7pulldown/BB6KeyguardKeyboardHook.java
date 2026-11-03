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
 * Hardware Space on lockscreen: invoke Android's normal credential-entry UI.
 *
 * In LineageOS 24, WindowRootView.dispatchKeyEvent calls
 * super.dispatchKeyEvent first, allowing focused notifications/QS to consume
 * Space before the normal KeyguardKeyEventInteractor sees it. Intercept at
 * the declaring WindowRootView before its children instead.
 *
 * Scene mode uses DeviceEntryInteractor.attemptDeviceEntry, the same native
 * method used by keyguard. Legacy mode uses StatusBarKeyguardViewManager.
 * Credentials are never collected or verified by this module.
 */
public final class BB6KeyguardKeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-Keyguard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String WINDOW_ROOT =
            "com.android.systemui.scene.ui.view.WindowRootView";
    private static final String NATIVE_MANAGER =
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String SCENE_FLAG =
            "com.android.systemui.scene.shared.flag.SceneContainerFlag";
    private static final String ENABLE_PROP = "debug.bb7keyguard.enabled";
    private static final int SHADE_LOCKED = 2;

    private static final KeyguardSpacePolicy SPACE_POLICY = new KeyguardSpacePolicy();
    private static WeakReference<Object> nativeManager = new WeakReference<>(null);
    private static ClassLoader systemUiLoader;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM_UI.equals(lp.packageName) || !SYSTEM_UI.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Feature disabled by " + ENABLE_PROP);
            return;
        }
        systemUiLoader = lp.classLoader;

        Class<?> manager = XposedHelpers.findClassIfExists(NATIVE_MANAGER, lp.classLoader);
        if (manager != null) {
            try {
                XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        nativeManager = new WeakReference<>(param.thisObject);
                        Log.i(TAG, "Native keyguard manager captured");
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Could not register manager constructor hook", error);
            }
        }

        Class<?> root = XposedHelpers.findClassIfExists(WINDOW_ROOT, lp.classLoader);
        if (root == null) {
            Log.e(TAG, "SystemUI window root absent, cannot intercept Space");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(root, "dispatchKeyEvent", KeyEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)
                            || !(param.args[0] instanceof KeyEvent)) return;
                    if (interceptSpace((View) param.thisObject, (KeyEvent) param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            Log.i(TAG, "Native-first locked Space interception installed");
        } catch (Throwable error) {
            Log.e(TAG, "Could not hook WindowRootView.dispatchKeyEvent", error);
        }
    }

    private static boolean interceptSpace(View window, KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_SPACE || !enabled()) return false;

        KeyguardManager keyguard = (KeyguardManager)
                window.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) {
            Log.e(TAG, "Keyguard service unavailable; stock handling retained");
            return false;
        }
        boolean locked = keyguard.isKeyguardLocked();
        View focused = window.findFocus();
        boolean editing = focused != null && focused.onCheckIsTextEditor();
        NativeTargets targets = getNativeTargets(window);
        boolean visible = locked && isBouncerShowing(targets.manager);
        int decision = SPACE_POLICY.route(event.getAction(), event.getDownTime(),
                event.getRepeatCount(), locked, visible, editing);

        if (decision == KeyguardSpacePolicy.PASS) return false;
        if (decision == KeyguardSpacePolicy.CONSUME) return true;

        // Request on DOWN, not UP: the requested bouncer can transfer focus and
        // input ownership before the physical key is released.
        String focusName = focused == null ? "none" : focused.getClass().getSimpleName();
        Log.i(TAG, "Space DOWN: locked=" + locked + " focus=" + focusName
                + " manager=" + (targets.manager != null)
                + " keyguardInteractor=" + (targets.keyguardInteractor != null));
        requestDeviceEntry(window, targets, keyguard);
        return true;
    }

    private static final class NativeTargets {
        Object manager;
        Object keyguardInteractor;
    }

    /** Recover native objects from the same dependency graph as SystemUI's
     * keyboard dispatcher; the constructor capture is a fallback.
     */
    private static NativeTargets getNativeTargets(View root) {
        NativeTargets targets = new NativeTargets();
        targets.manager = nativeManager.get();
        try {
            Object handler = XposedHelpers.getObjectField(root,
                    "windowRootViewKeyEventHandler");
            Object lazy = XposedHelpers.getObjectField(handler,
                    "sysUIKeyEventHandlerLazy");
            Object dispatcher = XposedHelpers.callMethod(lazy, "get");
            targets.keyguardInteractor = XposedHelpers.getObjectField(dispatcher,
                    "keyguardKeyEventInteractor");
            if (targets.manager == null) {
                targets.manager = XposedHelpers.getObjectField(
                        targets.keyguardInteractor, "statusBarKeyguardViewManager");
                nativeManager = new WeakReference<>(targets.manager);
            }
        } catch (Throwable error) {
            // A vendor build may have different internal fields. We can still
            // use the captured native manager, if available.
            if (targets.manager == null) {
                Log.e(TAG, "Cannot locate native keyguard dependencies", error);
            }
        }
        return targets;
    }

    private static void requestDeviceEntry(View root, NativeTargets targets,
            KeyguardManager keyguard) {
        Boolean scene = isSceneEnabled();
        Log.i(TAG, "Entry route: scene=" + scene
                + " manager=" + (targets.manager != null));
        String reason = "BB UI physical Space";

        if (Boolean.TRUE.equals(scene)) {
            Object deviceEntry = null;
            try {
                if (targets.keyguardInteractor != null) {
                    deviceEntry = XposedHelpers.getObjectField(
                            targets.keyguardInteractor, "deviceEntryInteractor");
                } else if (targets.manager != null) {
                    Object lazy = XposedHelpers.getObjectField(
                            targets.manager, "mDeviceEntryInteractorLazy");
                    deviceEntry = XposedHelpers.callMethod(lazy, "get");
                }
                if (deviceEntry != null) {
                    // Kotlin @JvmOverloads in LineageOS 24 exposes the
                    // (String, IKeyguardDismissCallback, boolean) overload.
                    // true forces the PRIMARY credential challenge, not
                    // alternate fingerprint bouncer.
                    try {
                        XposedHelpers.callMethod(deviceEntry,
                                "attemptDeviceEntry", reason, null, true);
                    } catch (NoSuchMethodError missingOverload) {
                        XposedHelpers.callMethod(deviceEntry,
                                "attemptDeviceEntry", reason);
                    }
                    Log.i(TAG, "Native scene credential entry requested");
                    return;
                }
                Log.e(TAG, "Scene enabled but DeviceEntryInteractor unavailable");
            } catch (Throwable error) {
                Log.e(TAG, "Native scene credential entry failed", error);
            }
            // The native manager is the compatibility fallback.
        }

        if (targets.manager == null) {
            Log.e(TAG, "No native credential entry component available");
            return;
        }

        int state = readNativeStatusBarState(targets);
        // Legacy locked shade needs to finish collapsing before showing
        // the bouncer. ShadeController.collapseShade(true) runs pending
        // actions even if no collapse is necessary.
        if (state == SHADE_LOCKED && !Boolean.TRUE.equals(scene)) {
            try {
                Object shadeController = targets.keyguardInteractor != null
                        ? XposedHelpers.getObjectField(
                            targets.keyguardInteractor, "shadeController")
                        : XposedHelpers.callMethod(XposedHelpers.getObjectField(
                            targets.manager, "mShadeController"), "get");
                Object manager = targets.manager;
                Runnable displayCredentials = () -> {
                    if (keyguard.isKeyguardLocked()
                            && !isBouncerShowing(manager)) {
                        showLegacyPrimaryBouncer(manager, "BB UI Space after shade collapse");
                    }
                };
                XposedHelpers.callMethod(shadeController, "addPostCollapseAction",
                        displayCredentials);
                XposedHelpers.callMethod(shadeController, "collapseShade", true);
                Log.i(TAG, "Legacy locked shade collapse + native bouncer queued");
                return;
            } catch (Throwable error) {
                Log.e(TAG, "Legacy locked shade collapse failed", error);
            }
        }

        showLegacyPrimaryBouncer(targets.manager, reason);
    }

    private static void showLegacyPrimaryBouncer(Object manager, String reason) {
        try {
            XposedHelpers.callMethod(manager, "showPrimaryBouncer", true, reason);
            Log.i(TAG, "Native primary credential bouncer requested");
        } catch (Throwable primaryError) {
            Log.w(TAG, "Primary bouncer entry unavailable; trying native showBouncer",
                    primaryError);
            try {
                XposedHelpers.callMethod(manager, "showBouncer", true, reason);
                Log.i(TAG, "Native bouncer requested");
            } catch (Throwable error) {
                Log.e(TAG, "Unable to request native bouncer", error);
            }
        }
    }

    private static Boolean isSceneEnabled() {
        Class<?> scene = XposedHelpers.findClassIfExists(SCENE_FLAG, systemUiLoader);
        if (scene == null) return null;
        for (String method : new String[] {"isEnabled", "getIsEnabled"}) {
            try {
                Object value = XposedHelpers.callStaticMethod(scene, method);
                if (value instanceof Boolean) return (Boolean) value;
            } catch (NoSuchMethodError missing) {
                // The Kotlin getter JVM name is version-dependent.
            } catch (Throwable error) {
                Log.w(TAG, "Could not read scene framework flag", error);
                break;
            }
        }
        return null;
    }

    private static int readNativeStatusBarState(NativeTargets targets) {
        try {
            Object controller = targets.keyguardInteractor != null
                    ? XposedHelpers.getObjectField(targets.keyguardInteractor,
                            "statusBarStateController")
                    : XposedHelpers.getObjectField(targets.manager,
                            "mStatusBarStateController");
            Object state = XposedHelpers.callMethod(controller, "getState");
            return state instanceof Integer ? (Integer) state : -1;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read native status bar state", error);
            return -1;
        }
    }

    private static boolean isBouncerShowing(Object manager) {
        if (manager == null) return false;
        for (String method : new String[] {
                "isBouncerShowing", "primaryBouncerIsOrWillBeShowing",
                "primaryBouncerIsShowing"
        }) {
            Boolean shown = queryBoolean(manager, method);
            if (shown != null) return shown;
        }
        return false;
    }

    private static Boolean queryBoolean(Object instance, String name) {
        for (Method method : instance.getClass().getMethods()) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != 0) {
                continue;
            }
            try {
                Object value = XposedHelpers.callMethod(instance, name);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable error) {
                Log.w(TAG, "Native state query failed: " + name, error);
                return null;
            }
        }
        return null;
    }

    private static boolean enabled() {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    props, "get", ENABLE_PROP, "true"));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read enable property", error);
            return false;
        }
    }
}
