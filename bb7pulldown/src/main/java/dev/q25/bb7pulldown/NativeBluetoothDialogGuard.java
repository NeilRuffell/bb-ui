package dev.q25.bb7pulldown;

import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Narrow compatibility guard for the stock Bluetooth details dialog shipped
 * with the Q25 LineageOS 24 SystemUI.
 *
 * Device crash evidence (November 3, 2026): NestedScrollView.onSizeChanged()
 * calls isWithinDeltaOfScreen() with a focused View which is no longer a
 * descendant of that scroll view. Android ViewGroup then throws
 * IllegalArgumentException("parameter must be a descendant of this view"),
 * terminating SystemUI.
 *
 * The native Bluetooth dialog XML has a NestedScrollView named scroll_view,
 * whose direct ConstraintLayout child contains bluetooth_toggle.
 *
 * Suppress only the invalid focus-scroll calculation; do not replace
 * Bluetooth controls, catch arbitrary exceptions, or change valid scrolling.
 * When ported into a ROM, prefer fixing the underlying AndroidX view.
 */
final class NativeBluetoothDialogGuard {
    private static final String TAG = "BB6-NativeQS";
    private static boolean installed;

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> type = XposedHelpers.findClassIfExists(
                "androidx.core.widget.NestedScrollView", loader);
        if (type == null) {
            Log.w(TAG, "Bluetooth dialog focus guard: AndroidX scroll class unavailable");
            return;
        }
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    type, "isWithinDeltaOfScreen", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof ViewGroup)
                                    || param.args == null
                                    || param.args.length != 3
                                    || !(param.args[0] instanceof View)) return;

                            ViewGroup scroll = (ViewGroup) param.thisObject;
                            View candidate = (View) param.args[0];

                            // AndroidX must never calculate a descendant's
                            // coordinates unless the actual parent chain
                            // reaches the containing scroll view.
                            if (isDescendantOf(scroll, candidate)
                                    || !isBluetoothDialogScroll(scroll)) return;

                            Log.w(TAG, "Bluetooth dialog stale focus ignored: "
                                    + candidate.getClass().getSimpleName());
                            param.setResult(false);
                        }
                    });
            installed = !hooks.isEmpty();
            if (installed) {
                Log.i(TAG, "Native Bluetooth dialog invalid-focus guard installed");
            } else {
                Log.w(TAG, "Bluetooth dialog focus method unavailable in ROM");
            }
        } catch (Throwable error) {
            Log.w(TAG, "Unable to install native Bluetooth dialog focus guard", error);
        }
    }

    private static boolean isDescendantOf(ViewGroup ancestor, View child) {
        ViewParent parent = child.getParent();
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isBluetoothDialogScroll(ViewGroup scroll) {
        if (!"scroll_view".equals(resourceEntryName(scroll))) return false;
        if (scroll.getChildCount() != 1
                || !(scroll.getChildAt(0) instanceof ViewGroup)) return false;
        ViewGroup content = (ViewGroup) scroll.getChildAt(0);
        for (int i = 0; i < content.getChildCount(); i++) {
            if ("bluetooth_toggle".equals(resourceEntryName(content.getChildAt(i)))) {
                return true;
            }
        }
        return false;
    }

    private static String resourceEntryName(View view) {
        if (view.getId() == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException error) {
            return null;
        }
    }

    private NativeBluetoothDialogGuard() {}
}
