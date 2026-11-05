package dev.q25.bb7pulldown;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Preserve the REAL native status-bar contents while BB UI owns SystemUI's
 * notification shade window. No cloned status-bar views or coroutine StateFlow
 * mutations are used.
 *
 * Verified on the supplied Q25 SystemUI APK (classes2.dex):
 *   StatusBarVisibilityInteractor$isHomeScreenStatusBarAllowedLegacy$1
 *       .invokeSuspend(Object): Object
 *   StatusBarVisibilityInteractor$isHomeStatusBarAllowedByScene$1
 *       .invokeSuspend(Object): Object
 *
 * LineageOS 24 derives home status-bar availability from these two decisions,
 * selecting one by SceneContainerFlag. A fully expanded native shade ordinarily
 * makes either decision false. Only while BB UI is actually mounted inside the
 * native shade-window root, preserve the home bar by returning true. This
 * leaves Android's real clock, status icons and battery views under their
 * existing lifecycle, configuration, colors and user visibility settings.
 *
 * Unlike the earlier attempt, this does not require capturing
 * StatusBarVisibilityInteractor.isShadeVisibleOnAnyDisplay or locating the
 * ReadonlyStateFlow's backing instance. The Q25 logs demonstrated that capture
 * never succeeded, so the previous hook had no effect on native visibility.
 */
final class NativeShadeStatusBarVisibility {
    private static final String TAG = "BB7-StatusBar";
    private static final String PREFIX =
            "com.android.systemui.statusbar.pipeline.shared.domain.interactor."
                    + "StatusBarVisibilityInteractor$";
    private static final String LEGACY =
            PREFIX + "isHomeScreenStatusBarAllowedLegacy$1";
    private static final String SCENE =
            PREFIX + "isHomeStatusBarAllowedByScene$1";

    private static boolean installed;
    private static boolean legacyLogged;
    private static boolean sceneLogged;
    private static boolean unexpectedResultLogged;

    private NativeShadeStatusBarVisibility() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;

        final Class<?> legacy = XposedHelpers.findClassIfExists(LEGACY, loader);
        final Class<?> scene = XposedHelpers.findClassIfExists(SCENE, loader);
        if (legacy == null || scene == null) {
            Log.e(TAG, "Q25 native home status-bar decision classes unavailable; "
                    + "legacy=" + (legacy != null) + " scene=" + (scene != null));
            return;
        }

        try {
            legacy.getDeclaredMethod("invokeSuspend", Object.class);
            scene.getDeclaredMethod("invokeSuspend", Object.class);

            XposedHelpers.findAndHookMethod(legacy, "invokeSuspend", Object.class,
                    decisionHook(false));
            XposedHelpers.findAndHookMethod(scene, "invokeSuspend", Object.class,
                    decisionHook(true));
            installed = true;
            Log.i(TAG, "Q25 native status-bar content decisions hooked"
                    + " (legacy and scene; no StateFlow capture)");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot hook Q25 native home status-bar decisions", error);
        }
    }

    private static XC_MethodHook decisionHook(boolean scene) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !NativeShadeHostBridge.ownsNativeShadeWindow()) {
                    return;
                }

                Object original = param.getResult();
                if (!(original instanceof Boolean)) {
                    if (!unexpectedResultLogged) {
                        unexpectedResultLogged = true;
                        Log.w(TAG, "Home status-bar decision result not Boolean; type="
                                + (original == null ? "null"
                                        : original.getClass().getName()));
                    }
                    return;
                }

                boolean nativeAllowed = (Boolean) original;
                if (!nativeAllowed) {
                    param.setResult(NativeStatusBarVisibilityPolicy
                            .keepHomeStatusBarAllowed(nativeAllowed, true));
                }
                if (scene && !sceneLogged) {
                    sceneLogged = true;
                    Log.i(TAG, "Retained genuine home status-bar content in scene shade");
                } else if (!scene && !legacyLogged) {
                    legacyLogged = true;
                    Log.i(TAG, "Retained genuine home status-bar content in legacy shade");
                }
            }
        };
    }

    /** Native ownership controls the policy; no state capture or restoration. */
    static void onBbShadeMountChanged(boolean mounted) {
        if (installed) {
            Log.i(TAG, mounted
                    ? "BB native shade mounted; native status-bar decisions preserved"
                    : "BB native shade unmounted; stock status-bar decisions restored");
        }
    }
}
