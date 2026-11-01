package dev.q25.bb7shade;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Navigate SystemUI's real scene container, never its legacy QS layout.
 *
 * LineageOS 24 deliberately positions the notification panel offscreen in
 * Compose QuickSettingsScene. The previous StatusBarManager request reached
 * the service but did not confirm an actual scene transition. When active,
 * use the system-owned ShadeControllerSceneImpl.expandToNotifications()
 * directly, and log the scene before/after the request.
 *
 * The platform validates transitions. The bridge never changes a row,
 * stack alpha, privacy controls or visibility.
 */
final class ShadeSceneBridge {
    private static final String TAG = "BB7-Shade";
    private static final long MIN_REQUEST_INTERVAL_MS = 850L;
    private static long lastRequestMs;
    private static boolean warned;
    private static WeakReference<Object> sceneController = new WeakReference<>(null);

    static void installHooks(ClassLoader loader) {
        Class<?> scene = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.ShadeControllerSceneImpl", loader);
        if (scene == null) {
            Log.i(TAG, "Native scene controller not found; using platform status bar");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(scene, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sceneController = new WeakReference<>(param.thisObject);
                    Log.i(TAG, "Native scene controller captured");
                }
            });
            XposedBridge.hookAllMethods(scene, "expandToNotifications", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sceneController = new WeakReference<>(param.thisObject);
                    Log.i(TAG, "SystemUI expandToNotifications invoked; scene="
                            + readScene(param.thisObject));
                }
            });
            Log.i(TAG, "Native scene-controller transition hooks registered");
        } catch (Throwable error) {
            Log.w(TAG, "Native scene-controller hooks unavailable ("
                    + error.getClass().getSimpleName() + ")");
        }
    }

    static void requestNotifications(View rail) {
        if (!ShadeRailHost.isRailActive()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRequestMs < MIN_REQUEST_INTERVAL_MS) return;
        lastRequestMs = now;

        // Post outside focus dispatch. SystemUI owns all scene transitions.
        rail.post(() -> {
            if (!ShadeRailHost.isRailActive()) return;
            Object controller = sceneController.get();
            if (controller != null) {
                String previous = readScene(controller);
                try {
                    XposedHelpers.callMethod(controller, "expandToNotifications");
                    Log.i(TAG, "Requested Notifications from native scene controller"
                            + " previous=" + previous);
                    rail.postDelayed(() -> Log.i(TAG,
                            "Native scene after Notifications request: "
                                    + readScene(controller)), 250);
                    return;
                } catch (Throwable error) {
                    logFailure("Direct native Notifications request failed", error);
                }
            }

            Object statusbar = rail.getContext().getSystemService(Context.STATUS_BAR_SERVICE);
            if (statusbar == null) {
                logFailure("StatusBarManager unavailable", null);
                return;
            }
            try {
                XposedHelpers.callMethod(statusbar, "expandNotificationsPanel");
                Log.i(TAG, "Requested Notifications through platform status bar");
            } catch (Throwable error) {
                logFailure("Platform Notifications request failed", error);
            }
        });
    }

    private static String readScene(Object controller) {
        try {
            Object interactor = XposedHelpers.getObjectField(controller, "sceneInteractor");
            if (interactor == null) return "interactor-null";
            // Prefer backing fields. Q25's optimized SystemUI has already
            // removed some Kotlin property accessors from the runtime.
            Object flow;
            try {
                flow = XposedHelpers.getObjectField(interactor, "currentScene");
            } catch (NoSuchFieldError unavailableField) {
                flow = XposedHelpers.callMethod(interactor, "getCurrentScene");
            }
            if (flow == null) return "scene-flow-null";
            Object key = XposedHelpers.callMethod(flow, "getValue");
            if (key == null) return "scene-null";
            // SceneKey.toString() includes the underlying debugName and does
            // not depend on an optimized-away Kotlin property accessor.
            return String.valueOf(key);
        } catch (Throwable error) {
            return "unknown(" + error.getClass().getSimpleName() + ")";
        }
    }

    private static void logFailure(String message, Throwable error) {
        if (warned) return;
        warned = true;
        Log.w(TAG, message + (error == null ? ""
                : " (" + error.getClass().getSimpleName() + ")"));
    }

    private ShadeSceneBridge() {}
}
