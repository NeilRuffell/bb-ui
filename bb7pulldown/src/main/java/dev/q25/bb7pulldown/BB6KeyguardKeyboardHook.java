package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
                XposedHelpers.hookAllConstructors(managerClass, new XC_MethodHook() {
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
        boolean managerReady = false;
        boolean primaryBouncerShowing = false;
        boolean bouncerInTransit = false;

        if (locked && manager != null) {
            try {
                Object shown = XposedHelpers.callMethod(manager, "primaryBouncerIsShowing");
                Object transit = XposedHelpers.callMethod(manager, "isPrimaryBouncerInTransit");
                if (shown instanceof Boolean && transit instanceof Boolean) {
                    primaryBouncerShowing = (Boolean) shown;
                    bouncerInTransit = (Boolean) transit;
                    managerReady = true;
                }
            } catch (Throwable error) {
                Log.e(TAG, "Could not determine native credential visibility", error);
            }
        }

        View focused = window.findFocus();
        boolean editorFocused = focused != null && focused.onCheckIsTextEditor();

        int decision = SPACE_POLICY.route(event.getAction(), event.getDownTime(),
                event.getRepeatCount(), locked, primaryBouncerShowing,
                bouncerInTransit, managerReady, editorFocused);
        if (decision == KeyguardSpacePolicy.PASS) return false;

        if (decision == KeyguardSpacePolicy.REQUEST_BOUNCER) {
            try {
                // LineageOS 24.0 signature: (boolean scrimmed, String reason).
                XposedHelpers.callMethod(manager, "showPrimaryBouncer",
                        true, "BB UI: physical Space on lockscreen");
                Log.i(TAG, "Space consumed; native primary bouncer requested");
            } catch (Throwable error) {
                // This key must NEVER fall through to a focused QS tile.
                Log.e(TAG, "Bouncer request failed; lockscreen Space still consumed", error);
            }
        } else if (event.getAction() == KeyEvent.ACTION_DOWN && !managerReady && locked) {
            Log.w(TAG, "Space consumed on lockscreen; keyguard manager unavailable");
        }
        return true;
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
