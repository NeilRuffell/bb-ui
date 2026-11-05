package dev.q25.bb7pulldown;

import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Eliminate stock SystemUI shade backdrop blur/zoom while BB UI owns the
 * native notification shade. Keeps the original status-bar window and the BB
 * trackpad selection above an unblurred native shade chrome.
 *
 * Q25 classes2.dex verifies NotificationShadeDepthController:
 *  - computeBlurAndZoomOut(): kotlin.Pair
 *  - root: NotificationShadeWindowView
 *
 * This affects the native SHADE controller only when its root is the actual
 * BB-owned window. No persistent blur flags or keyguard state are changed.
 */
final class NativeShadeBlurPolicyHook {
    private static final String TAG = "BB7-StatusBar";
    private static final String DEPTH =
            "com.android.systemui.statusbar.NotificationShadeDepthController";
    private static final String PAIR = "kotlin.Pair";
    private static boolean installed;
    private static boolean firstBlurSuppressedLogged;

    private NativeShadeBlurPolicyHook() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> depth = XposedHelpers.findClassIfExists(DEPTH, loader);
        Class<?> pair = XposedHelpers.findClassIfExists(PAIR, loader);
        if (depth == null || pair == null) {
            Log.w(TAG, "Q25 native shade blur policy class unavailable");
            return;
        }
        try {
            depth.getDeclaredMethod("computeBlurAndZoomOut");
            depth.getDeclaredField("root");
            XposedHelpers.findAndHookMethod(depth, "computeBlurAndZoomOut",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable()
                                    || !NativeShadeHostBridge.ownsNativeShadeWindow()) return;
                            try {
                                Object depthWindow =
                                        XposedHelpers.getObjectField(param.thisObject, "root");
                                if (depthWindow == null
                                        || depthWindow != NativeShadeHostBridge.shadeWindow()) {
                                    return;
                                }
                                // Source returns Pair<Int blurRadius, Float zoomOut>.
                                param.setResult(XposedHelpers.newInstance(
                                        pair, Integer.valueOf(0), Float.valueOf(0f)));
                                if (!firstBlurSuppressedLogged) {
                                    firstBlurSuppressedLogged = true;
                                    Log.i(TAG, "Native shade backdrop blur/zoom suppressed"
                                            + " while BB UI is mounted");
                                }
                            } catch (Throwable error) {
                                Log.w(TAG, "Cannot override Q25 native shade blur", error);
                            }
                        }
                    });
            installed = true;
            Log.i(TAG, "Q25 native shade blur policy hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Could not hook Q25 native shade blur policy", error);
        }
    }
}
