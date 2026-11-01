package dev.q25.trackpad;

import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Two native Quickstep panels, side by side.
 *
 * Does not move, scale, reparent, clip, replace, or draw TaskView objects.
 * No gesture, animation, screenshot, or task-handling methods are hooked.
 *
 * Enable explicitly:
 *   adb shell settings put global bb_ui_recents_two_up 1
 * Disable:
 *   adb shell settings put global bb_ui_recents_two_up 0
 * Restart the Quickstep process after changing the setting.
 */
public final class RecentsTwoColumnHook implements IXposedHookLoadPackage {
    private static final String TOGGLE = "bb_ui_recents_two_up";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String PAGED = "com.android.launcher3.PagedView";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName) || "com.android.systemui".equals(lp.packageName)
                || "dev.q25.trackpad".equals(lp.packageName)) {
            return;
        }

        Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        Class<?> paged = XposedHelpers.findClassIfExists(PAGED, lp.classLoader);
        if (recents == null || paged == null) return;
        Log.w("BB-UI-Recents", "Quickstep detected in " + lp.packageName
                + "; initializing two-panel and vertical hooks");

        try {
            // PagedView natively supports multi-panel paging, sizing, and focus.
            // By default getPanelCount() returns 1.
            XposedHelpers.findAndHookMethod(paged, "getPanelCount", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (recents.isInstance(param.thisObject)
                            && enabled((View) param.thisObject)) {
                        param.setResult(2);
                    }
                }
            });

            // Let Quickstep's own updateTaskSize() resize real thumbnails.
            // No direct TaskView transforms, preview overrides, or clipping changes.
            XposedHelpers.findAndHookMethod(recents, "getTaskSize", Rect.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View view = (View) param.thisObject;
                            if (!enabled(view)) return;

                            Rect rect = (Rect) param.args[0];
                            if (rect == null || rect.isEmpty()) return;

                            int screenWidth = view.getWidth() > 0
                                    ? view.getWidth()
                                    : view.getResources().getDisplayMetrics().widthPixels;
                            int margin = Math.round(8f
                                    * view.getResources().getDisplayMetrics().density);
                            int gap = margin;
                            int availableWidth = screenWidth - 2 * margin - gap;
                            if (availableWidth <= 0) return;

                            int originalWidth = rect.width();
                            int originalHeight = rect.height();
                            int newWidth = availableWidth / 2;
                            if (originalWidth <= 0 || originalHeight <= 0
                                    || newWidth <= 0) return;

                            int newHeight = Math.max(1, Math.round(
                                    originalHeight * (newWidth / (float) originalWidth)));
                            int top = rect.centerY() - newHeight / 2;
                            rect.set(margin, top, margin + newWidth, top + newHeight);
                        }
                    });

            // Install vertical mode from this confirmed working entry point.
            // The vertical feature stays disabled unless its separate flag is set.
            RecentsVerticalListHook.install(lp, recents, paged);

            XposedBridge.log("BB UI: native two-panel Recents hook installed in "
                    + lp.packageName);
        } catch (Throwable e) {
            XposedBridge.log("BB UI: native two-panel Recents hook failed: " + e);
        }
    }

    private static boolean enabled(View view) {
        try {
            return Settings.Global.getInt(view.getContext().getContentResolver(),
                    TOGGLE, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
