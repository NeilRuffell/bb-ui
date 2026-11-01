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
    private static WeakReference<Object> activeShadeController = new WeakReference<>(null);
    private static volatile boolean callbackCaptured;
    private static volatile boolean sceneRedirectLogged;

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

    /**
     * Capture the controller actually dispatched by SystemUI's CommandQueue.
     * This works even if ShadeControllerSceneImpl was constructed before our
     * Vector hooks (or the device uses ShadeControllerImpl instead).
     */
    static void installActiveControllerHooks(ClassLoader loader) {
        Class<?> callbacks = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.CentralSurfacesCommandQueueCallbacks",
                loader);
        if (callbacks != null) {
            try {
                XposedBridge.hookAllMethods(callbacks, "animateExpandNotificationsPanel",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                captureShadeController(param.thisObject);
                            }
                        });
                XposedBridge.hookAllMethods(callbacks, "animateExpandSettingsPanel",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                Object controller = captureShadeController(param.thisObject);
                                if (!ShadeRailHost.canRouteToNotifications() || controller == null) {
                                    return;
                                }
                                try {
                                    // Experimental mode: show notifications by default.
                                    // QS is deliberately unavailable until the second
                                    // BB7 page is implemented.
                                    XposedHelpers.callMethod(controller, "animateExpandShade");
                                    param.setResult(null);
                                    Log.i(TAG, "Routed native QS expansion to Notifications");
                                } catch (Throwable error) {
                                    logFailure("Native QS dispatch redirection failed", error);
                                    // Preserve the stock dispatch on failure.
                                }
                            }
                        });
                Log.i(TAG, "Native CommandQueue shade-controller hooks registered");
            } catch (Throwable error) {
                Log.w(TAG, "Native CommandQueue hooks unavailable ("
                        + error.getClass().getSimpleName() + ")");
            }
        }

        // Covers direct Compose scene transitions that bypass CommandQueue.
        Class<?> interactor = XposedHelpers.findClassIfExists(
                "com.android.systemui.scene.domain.interactor.SceneInteractor", loader);
        Class<?> scenes = XposedHelpers.findClassIfExists(
                "com.android.systemui.scene.shared.model.Scenes", loader);
        if (interactor == null || scenes == null) return;
        try {
            final Object quickSettings = XposedHelpers.getStaticObjectField(
                    scenes, "QuickSettings");
            final Object notifications = XposedHelpers.getStaticObjectField(
                    scenes, "Shade");
            XposedBridge.hookAllMethods(interactor, "changeScene", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ShadeRailHost.canRouteToNotifications()
                            || param.args.length == 0
                            || param.args[0] != quickSettings) return;
                    param.args[0] = notifications;
                    if (!sceneRedirectLogged) {
                        sceneRedirectLogged = true;
                        Log.i(TAG, "Routed Compose QuickSettings scene to Notifications");
                    }
                }
            });
            Log.i(TAG, "Compose scene routing hook registered");
        } catch (Throwable error) {
            Log.w(TAG, "Compose scene routing hook unavailable ("
                    + error.getClass().getSimpleName() + ")");
        }
    }

    private static Object captureShadeController(Object callbacks) {
        try {
            Object controller = XposedHelpers.getObjectField(
                    callbacks, "mShadeController");
            if (controller != null) {
                activeShadeController = new WeakReference<>(controller);
                if (!callbackCaptured) {
                    callbackCaptured = true;
                    Log.i(TAG, "Native active shade controller captured: "
                            + controller.getClass().getName());
                }
            }
            return controller;
        } catch (Throwable error) {
            logFailure("Unable to read active shade controller", error);
            return null;
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
            Object controller = activeShadeController.get();
            if (controller != null) {
                try {
                    XposedHelpers.callMethod(controller, "animateExpandShade");
                    Log.i(TAG, "Requested Notifications through active native shade controller: "
                            + controller.getClass().getName());
                    return;
                } catch (Throwable error) {
                    logFailure("Active native shade controller request failed", error);
                }
            }
            final Object scene = sceneController.get();
            if (scene != null) {
                String previous = readScene(scene);
                try {
                    XposedHelpers.callMethod(scene, "expandToNotifications");
                    Log.i(TAG, "Requested Notifications from native scene controller"
                            + " previous=" + previous);
                    rail.postDelayed(() -> Log.i(TAG,
                            "Native scene after Notifications request: "
                                    + readScene(scene)), 250);
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
