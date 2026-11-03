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
 * Stage 1: hardware Space opens Android's existing credential bouncer.
 *
 * No custom unlock, credential handling, notification launch or overlay.
 * Only the SystemUI keyguard window is hooked. When a required native
 * component cannot be verified, original Android key handling is retained.
 *
 * Rollback: adb shell setprop debug.bb7keyguard.enabled false
 *           adb shell am crash com.android.systemui
 */
public final class BB6KeyguardKeyboardHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-Keyguard";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String BOUNCER_MANAGER =
            "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String[] WINDOW_CLASSES = {
            "com.android.systemui.shade.NotificationShadeWindowView",
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView"
    };
    private static final String ENABLE_PROP = "debug.bb7keyguard.enabled";

    private static WeakReference<Object> keyguardViewManager = new WeakReference<>(null);
    // Pair the consumed DOWN with its UP, even if showing the bouncer changes
    // the focus between the two events. SystemUI dispatch runs on the UI thread.
    private static long consumedSpaceDownTime = -1L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM_UI.equals(lp.packageName) || !SYSTEM_UI.equals(lp.processName)) return;
        if (!enabled()) {
            Log.i(TAG, "Disabled by " + ENABLE_PROP);
            return;
        }
        Class<?> managerClass = XposedHelpers.findClassIfExists(BOUNCER_MANAGER, lp.classLoader);
        if (managerClass == null) {
            Log.w(TAG, "Native keyguard view manager missing; stock input retained");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    keyguardViewManager = new WeakReference<>(param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "Unable to capture native keyguard manager", error);
            return;
        }

        boolean hooked = false;
        for (String name : WINDOW_CLASSES) {
            Class<?> window = XposedHelpers.findClassIfExists(name, lp.classLoader);
            if (window == null) continue;
            try {
                if (!XposedBridge.hookAllMethods(window, "dispatchKeyEvent",
                        new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View) || param.args.length != 1
                                || !(param.args[0] instanceof KeyEvent)) return;
                        if (handleKey((View) param.thisObject, (KeyEvent) param.args[0])) {
                            param.setResult(true);
                        }
                    }
                }).isEmpty()) {
                    Log.i(TAG, "Hardware Space keyguard entry hooked: " + name);
                    hooked = true;
                }
            } catch (Throwable error) {
                Log.e(TAG, "Keyguard window hook unavailable: " + name, error);
            }
        }
        if (!hooked) Log.w(TAG, "No supported keyguard window; stock input retained");
    }

    private static boolean handleKey(View window, KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_SPACE) return false;

        // Consume the matching UP even if the new credential view is already shown.
        if (event.getAction() == KeyEvent.ACTION_UP
                && consumedSpaceDownTime == event.getDownTime()) {
            consumedSpaceDownTime = -1L;
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || !enabled()) return false;
        if (consumedSpaceDownTime == event.getDownTime()) return true;
        if (event.getRepeatCount() != 0) return false;

        Context context = window.getContext();
        KeyguardManager guard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        if (guard == null || !guard.isKeyguardLocked()) return false;

        Object manager = keyguardViewManager.get();
        if (manager == null) {
            Log.w(TAG, "Keyguard manager not ready; stock Space handling retained");
            return false;
        }
        try {
            // Never steal Space from the PIN/password field. The bouncer owns
            // every key once displayed, including spaces within passwords.
            Object showing = XposedHelpers.callMethod(manager, "isBouncerShowing");
            if (!(showing instanceof Boolean) || (Boolean) showing) return false;

            // Call Android's own authentication UI, not dismissKeyguard(),
            // an injected swipe, or a selected notification's click handler.
            XposedHelpers.callMethod(manager, "showPrimaryBouncer", true);
            consumedSpaceDownTime = event.getDownTime();
            Log.i(TAG, "Space requested native primary bouncer");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Native bouncer unavailable; stock Space handling retained", error);
            return false;
        }
    }

    private static boolean enabled() {
        try {
            Class<?> properties = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    properties, "get", ENABLE_PROP, "true"));
        } catch (Throwable error) {
            // A property lookup failure must not disable the phone's original input.
            Log.w(TAG, "Unable to read enable property; stock input retained", error);
            return false;
        }
    }
}
