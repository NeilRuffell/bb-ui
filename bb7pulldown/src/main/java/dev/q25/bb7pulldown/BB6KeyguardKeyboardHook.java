package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

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
    private static WeakReference<Object> nativeKeyguardInteractor = new WeakReference<>(null);
    private static WeakReference<Object> nativeDeviceEntry = new WeakReference<>(null);
    private static WeakReference<Object> nativePrimaryBouncer = new WeakReference<>(null);
    private static final String KEYGUARD_INTERACTOR =
            "com.android.systemui.keyguard.domain.interactor.KeyguardKeyEventInteractor";
    private static final String DEVICE_ENTRY =
            "com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor";
    private static final String PRIMARY_BOUNCER =
            "com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor";
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

        capturePresenter(KEYGUARD_INTERACTOR, lp.classLoader, 0);
        capturePresenter(DEVICE_ENTRY, lp.classLoader, 1);
        capturePresenter(PRIMARY_BOUNCER, lp.classLoader, 2);

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

    // Constructors are registered before SystemUI creates its Dagger singletons.
    // Capturing the actual presenters avoids depending on a vendor manager's
    // public convenience methods, which are absent on the tested Q25 ROM.
    private static void capturePresenter(String className, ClassLoader cl, int kind) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            Log.w(TAG, "Native presenter class absent: " + className);
            return;
        }
        try {
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (kind == 0) {
                        nativeKeyguardInteractor = new WeakReference<>(param.thisObject);
                    } else if (kind == 1) {
                        nativeDeviceEntry = new WeakReference<>(param.thisObject);
                    } else {
                        nativePrimaryBouncer = new WeakReference<>(param.thisObject);
                    }
                    Log.i(TAG, "Captured " + c.getSimpleName());
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Native presenter capture failed: "
                    + c.getSimpleName() + " " + brief(e));
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
        boolean visible = locked && isBouncerShowing(targets);
        int decision = SPACE_POLICY.route(event.getAction(), event.getDownTime(),
                event.getRepeatCount(), locked, visible, editing);

        if (decision == KeyguardSpacePolicy.PASS) return false;
        if (decision == KeyguardSpacePolicy.CONSUME) return true;

        // LineageOS handles confirm-key actions on UP. Invoke native entry
        // only after the physical key is released so that its release cannot
        // become typed text in the newly focused password editor.
        String focusName = focused == null ? "none" : focused.getClass().getSimpleName();
        Log.i(TAG, "Space UP: locked=" + locked + " focus=" + focusName
                + " manager=" + (targets.manager != null)
                + " keyguardInteractor=" + (targets.keyguardInteractor != null)
                + " deviceEntry=" + (targets.deviceEntry != null)
                + " primaryBouncer=" + (targets.primaryBouncer != null));
        requestDeviceEntry(window, targets, keyguard);
        return true;
    }

    private static final class NativeTargets {
        Object manager;
        Object keyguardInteractor;
        Object deviceEntry;
        Object primaryBouncer;
    }

    /** Recover native objects from the same dependency graph as SystemUI's
     * keyboard dispatcher; the constructor capture is a fallback.
     */
    private static NativeTargets getNativeTargets(View root) {
        NativeTargets targets = new NativeTargets();
        targets.manager = nativeManager.get();
        targets.keyguardInteractor = nativeKeyguardInteractor.get();
        targets.deviceEntry = nativeDeviceEntry.get();
        targets.primaryBouncer = nativePrimaryBouncer.get();
        try {
            Object handler = XposedHelpers.getObjectField(root,
                    "windowRootViewKeyEventHandler");
            Object lazy = XposedHelpers.getObjectField(handler,
                    "sysUIKeyEventHandlerLazy");
            Object dispatcher = XposedHelpers.callMethod(lazy, "get");
            if (targets.keyguardInteractor == null) {
                targets.keyguardInteractor = XposedHelpers.getObjectField(
                        dispatcher, "keyguardKeyEventInteractor");
            }
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
        if (targets.keyguardInteractor != null) {
            if (targets.deviceEntry == null) {
                targets.deviceEntry = optionalField(
                        targets.keyguardInteractor, "deviceEntryInteractor");
            }
            if (targets.manager == null) {
                targets.manager = optionalField(
                        targets.keyguardInteractor, "statusBarKeyguardViewManager");
            }
        }
        if (targets.manager != null) {
            if (targets.deviceEntry == null) {
                targets.deviceEntry = resolveLazyField(
                        targets.manager, "mDeviceEntryInteractorLazy");
            }
            if (targets.primaryBouncer == null) {
                targets.primaryBouncer = optionalField(
                        targets.manager, "mPrimaryBouncerInteractor");
            }
        }
        return targets;
    }

    private static Object optionalField(Object owner, String field) {
        if (owner == null) return null;
        try { return XposedHelpers.getObjectField(owner, field); }
        catch (Throwable ignored) { return null; }
    }

    private static Object resolveLazyField(Object owner, String field) {
        Object lazy = optionalField(owner, field);
        if (lazy == null) return null;
        try { return XposedHelpers.callMethod(lazy, "get"); }
        catch (Throwable ignored) { return null; }
    }

    private static void requestDeviceEntry(View root, NativeTargets t,
            KeyguardManager keyguard) {
        Boolean scene = isSceneEnabled();
        Log.i(TAG, "Native route scene=" + scene
                + " deviceEntry=" + (t.deviceEntry != null)
                + " primaryBouncer=" + (t.primaryBouncer != null)
                + " manager=" + (t.manager != null));
        String reason = "BB UI physical Space";

        // DeviceEntryInteractor is the real Scene-mode entry point. If the
        // vendor has inlined the scene flag, try this when mode is unknown too.
        if (!Boolean.FALSE.equals(scene) && invokeDeviceEntry(t.deviceEntry, reason)) {
            return;
        }

        int state = readNativeStatusBarState(t);
        if (state == SHADE_LOCKED && Boolean.FALSE.equals(scene)) {
            Object shade = t.keyguardInteractor != null
                    ? optionalField(t.keyguardInteractor, "shadeController")
                    : resolveLazyField(t.manager, "mShadeController");
            if (shade != null) {
                Runnable displayAfterCollapse = () -> {
                    if (keyguard.isKeyguardLocked()) requestPrimaryBouncer(t, reason);
                };
                if (invokeNative(shade, "addPostCollapseAction", displayAfterCollapse)
                        && invokeNative(shade, "collapseShade", true)) {
                    Log.i(TAG, "Native locked shade collapse and bouncer queued");
                    return;
                }
            }
        }

        if (requestPrimaryBouncer(t, reason)) return;
        if (Boolean.FALSE.equals(scene) && invokeDeviceEntry(t.deviceEntry, reason)) {
            return;
        }
        Log.e(TAG, "Native credential API unavailable. deviceEntry="
                + methods(t.deviceEntry) + " primary=" + methods(t.primaryBouncer)
                + " manager=" + methods(t.manager));
    }

    private static boolean invokeDeviceEntry(Object target, String reason) {
        if (target == null) return false;
        if (invokeNative(target, "attemptDeviceEntry", reason, null, true)) {
            Log.i(TAG, "Native DeviceEntryInteractor.attemptDeviceEntry invoked");
            return true;
        }
        if (invokeNative(target, "attemptDeviceEntry", reason)) {
            Log.i(TAG, "Native DeviceEntryInteractor one-argument entry invoked");
            return true;
        }
        return false;
    }

    private static boolean requestPrimaryBouncer(NativeTargets t, String reason) {
        // Verified against the Q25 SystemUI APK (classes2.dex, classes.dex):
        //   StatusBarKeyguardViewManager.showPrimaryBouncer(String, boolean)
        //   PrimaryBouncerInteractor.show(String, boolean)
        // R8 reordered these parameters compared with LineageOS 24 source.
        // Prefer the native manager so it performs its normal keyguard checks
        // and state updates before delegating to the primary credential UI.
        if (invokeNative(t.manager, "showPrimaryBouncer", reason, true)) {
            Log.i(TAG, "Q25 native primary credential entry invoked via manager");
            return true;
        }
        if (invokeNative(t.primaryBouncer, "show", reason, true)) {
            Log.i(TAG, "Q25 native PrimaryBouncerInteractor.show invoked");
            return true;
        }
        // Keep source-compatible order for other LineageOS variants.
        if (invokeNative(t.manager, "showPrimaryBouncer", true, reason)) {
            Log.i(TAG, "Source-order native primary credential entry invoked");
            return true;
        }
        if (invokeNative(t.primaryBouncer, "show", true, reason)) {
            Log.i(TAG, "Source-order PrimaryBouncerInteractor.show invoked");
            return true;
        }
        if (invokeNative(t.manager, "showBouncer", reason, true)
                || invokeNative(t.manager, "showBouncer", true, reason)) {
            Log.i(TAG, "Native manager bouncer fallback invoked");
            return true;
        }
        return false;
    }

    /** Reflect the exact runtime method, not an assumed XposedHelpers match.
     * Q25's SystemUI exposes a different method set from upstream LineageOS.
     */
    private static boolean invokeNative(Object instance, String name, Object... args) {
        if (instance == null) return false;
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || !signatureMatches(method.getParameterTypes(), args)) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(instance, args);
                    if (result instanceof Boolean && !((Boolean) result)) {
                        Log.w(TAG, name + " returned false on "
                                + instance.getClass().getSimpleName());
                        return false;
                    }
                    return true;
                } catch (Throwable error) {
                    Log.e(TAG, name + " failed on " + instance.getClass().getSimpleName()
                            + ": " + brief(error));
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean signatureMatches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
            } else if (types[i] == boolean.class) {
                if (!(args[i] instanceof Boolean)) return false;
            } else if (types[i] == int.class) {
                if (!(args[i] instanceof Integer)) return false;
            } else if (!types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** A compact one-line method inventory for real-device failures. */
    private static String methods(Object instance) {
        if (instance == null) return "not captured";
        StringBuilder summary = new StringBuilder(
                instance.getClass().getSimpleName()).append('[');
        int count = 0;
        for (Class<?> c = instance.getClass(); c != null && count < 12;
                c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("show") || name.contains("bouncer")
                        || name.contains("entry")) {
                    if (count++ > 0) summary.append(',');
                    summary.append(method.getName()).append('(')
                            .append(method.getParameterTypes().length).append(')');
                    if (count >= 12) break;
                }
            }
        }
        return summary.append(']').toString();
    }

    private static String brief(Throwable error) {
        if (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
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
                Log.w(TAG, "Scene flag unavailable: " + brief(error));
                break;
            }
        }
        // Source of SceneContainerFlag.isEnabled is
        // Flags.sceneContainer() && SceneContainerFlag.isEnabledOnVariant.
        // Read that directly when a vendor build changes the Kotlin getter.
        try {
            Class<?> flags = XposedHelpers.findClass(
                    "com.android.systemui.Flags", systemUiLoader);
            Object configured = XposedHelpers.callStaticMethod(
                    flags, "sceneContainer");
            boolean variant = XposedHelpers.getStaticBooleanField(
                    scene, "isEnabledOnVariant");
            if (configured instanceof Boolean) {
                return ((Boolean) configured) && variant;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Scene framework mode unavailable: " + brief(error));
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
            Object state = null;
            if (controller != null) {
                for (Method m : controller.getClass().getMethods()) {
                    if (m.getName().equals("getState")
                            && m.getParameterTypes().length == 0) {
                        state = m.invoke(controller);
                        break;
                    }
                }
            }
            return state instanceof Integer ? (Integer) state : -1;
        } catch (Throwable error) {
            Log.w(TAG, "Could not read native status bar state", error);
            return -1;
        }
    }

    private static boolean isBouncerShowing(NativeTargets t) {
        if (t.primaryBouncer != null) {
            Boolean primary = queryBoolean(t.primaryBouncer, "isFullyShowing");
            if (Boolean.TRUE.equals(primary)) return true;
            primary = queryBoolean(t.primaryBouncer, "isInTransit");
            if (Boolean.TRUE.equals(primary)) return true;
        }
        Object manager = t.manager;
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
                method.setAccessible(true);
                Object value = method.invoke(instance);
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (Throwable error) {
                Log.w(TAG, "Native state query failed: " + name
                        + " " + brief(error));
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
