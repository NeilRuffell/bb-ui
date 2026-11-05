package dev.q25.bb7pulldown;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Bridge the actual native Q25 Back dispatcher to BB UI's EXISTING
 * navigation state while the BB shade owns NotificationShadeWindowView.
 *
 * Verified in Q25 SystemUI.apk/classes.dex:
 * com.android.systemui.back.domain.interactor.BackActionInteractor
 *     onBackRequested(): boolean
 *
 * LineageOS 24 normally calls animateCollapseShade() from that method,
 * even though the BB view uses immediate native shade teardown. The
 * resulting stock animation can make Back visibly slower than Home.
 *
 * Scope is deliberately exact: do not intercept Back without a mounted
 * BB native shade, on keyguard, or outside SystemUI's main thread.
 */
final class NativeShadeBackBridge {
    private static final String TAG = "BB7-NativeBack";
    private static final String INTERACTOR =
            "com.android.systemui.back.domain.interactor.BackActionInteractor";
    private static boolean installed;

    private NativeShadeBackBridge() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> back = XposedHelpers.findClassIfExists(INTERACTOR, loader);
        if (back == null) {
            Log.w(TAG, "Native Q25 BackActionInteractor not found");
            return;
        }
        try {
            if (back.getDeclaredMethod("onBackRequested").getReturnType()
                    != boolean.class) {
                Log.w(TAG, "Q25 BackActionInteractor result changed; leaving native Back");
                return;
            }
            XposedHelpers.findAndHookMethod(back, "onBackRequested",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!NativeShadeHostBridge.ownsNativeShadeWindow()) return;
                            if (Looper.myLooper() != Looper.getMainLooper()) {
                                Log.w(TAG, "Native Back callback not on main thread;"
                                        + " preserving SystemUI dispatch");
                                return;
                            }
                            long start = SystemClock.uptimeMillis();
                            try {
                                if (!PullDownHook.onNativeSystemBackRequested()) {
                                    Log.w(TAG, "BB shade unavailable during native Back;"
                                            + " preserving stock dispatch");
                                    return;
                                }
                                // Set the exact Boolean result so the native
                                // handler does not launch its shade animation
                                // or consume a second navigation level.
                                param.setResult(Boolean.TRUE);
                                Log.i(TAG, "BB native Back handled directly"
                                        + " elapsedMs="
                                        + (SystemClock.uptimeMillis() - start));
                            } catch (Throwable error) {
                                Log.e(TAG, "BB native Back routing failed;"
                                        + " preserving native handling", error);
                            }
                        }
                    });
            installed = true;
            Log.i(TAG, "Q25 BackActionInteractor.onBackRequested() reroute registered");
        } catch (Throwable error) {
            Log.e(TAG, "Failed to install Q25 native Back hook", error);
        }
    }
}
