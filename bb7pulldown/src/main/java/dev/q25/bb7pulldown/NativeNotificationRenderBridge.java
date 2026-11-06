package dev.q25.bb7pulldown;

import android.graphics.Canvas;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Suppress redundant drawing of stock Android notification cards while the
 * BB presentation owns the SAME SystemUI notification-shade window.
 *
 * Crucially, do NOT remove native rows or stop their inflation. Android's
 * NotifPipeline, notification clickers, RemoteInput and QS dependencies may
 * still hold their views; breaking those requires source-level ROM changes.
 *
 * This hook replaces only a ViewGroup dispatchDraw pass. It does not affect
 * measurements, touch handlers, native row state or stock heads-up outside
 * BB sessions. When BB unmounts, the hook simply calls the original method.
 */
final class NativeNotificationRenderBridge {
    private static final String TAG = "BB7-NotifRender";
    private static final String STACK =
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout";
    private static final String PROPERTY =
            "debug.bb7pulldown.native_notif_draw.enabled";

    private static boolean installed;
    private static boolean firstSuppressionLogged;

    private NativeNotificationRenderBridge() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Native card-drawing suppression disabled by " + PROPERTY);
            return;
        }
        Class<?> stack = XposedHelpers.findClassIfExists(STACK, loader);
        if (stack == null) {
            Log.w(TAG, "Native notification stack class missing; stock drawing unchanged");
            return;
        }
        try {
            // The lineage-24.0 source declares this override. Check the
            // actual installed Q25 class before attempting to hook it.
            if (stack.getDeclaredMethod("dispatchDraw", Canvas.class).getReturnType()
                    != void.class) {
                Log.w(TAG, "Native notification draw signature changed; leaving stock drawing");
                return;
            }
            XposedHelpers.findAndHookMethod(stack, "dispatchDraw", Canvas.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View stack = (View) param.thisObject;
                    FrameLayout bbWindow = NativeShadeHostBridge.windowWithBbPresentation();
                    FrameLayout lockWindow = BB6LockScreenHook.interactiveWindow();
                    if (!NativeNotificationRenderPolicy.shouldSuppress(
                            bbWindow != null, bbWindow != null && isWithin(stack, bbWindow),
                            lockWindow != null,
                            lockWindow != null && isWithin(stack, lockWindow))) return;
                    // Native rows, controllers, and touch handlers stay intact:
                    // skip only the duplicate drawing pass.
                    param.setResult(null);
                    if (!firstSuppressionLogged) {
                        firstSuppressionLogged = true;
                        Log.i(TAG, "Stock notification cards skipped in BB-owned"
                                + " shade/lockscreen; native rows retained");
                    }
                }
            });
            installed = true;
            Log.i(TAG, "Q25 native notification dispatchDraw gate installed");
        } catch (Throwable error) {
            Log.w(TAG, "Unable to hook native card drawing; preserving stock SystemUI", error);
        }
    }

    private static boolean isWithin(View candidate, FrameLayout window) {
        ViewParent parent = candidate.getParent();
        while (parent instanceof View) {
            if (parent == window) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    props, "getBoolean", PROPERTY, true));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read rendering gate property; leaving stock drawing", error);
            return false;
        }
    }
}
