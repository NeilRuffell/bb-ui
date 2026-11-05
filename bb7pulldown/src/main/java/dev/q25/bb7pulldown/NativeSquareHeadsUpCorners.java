package dev.q25.bb7pulldown;

import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewParent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Square only the stock heads-up popup's native notification outline and
 * background. Does not change notification row resources, shade content,
 * BB UI notifications, or global SystemUI roundness configuration.
 *
 * Q25 APK-verified methods:
 * ActivatableNotificationView.getClipPath(boolean) : Path
 * NotificationBackgroundView.setRadius(float,float) : void
 * ExpandableNotificationRow.applyRoundnessAndInvalidate() : void
 * ExpandableNotificationRow.isPinned() : boolean
 */
final class NativeSquareHeadsUpCorners {
    private static final String TAG = "BB7-HeadsUp";
    private static final String ENABLE_PROP = "debug.bb7pulldown.headsup_square.enabled";
    private static final String ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String OUTLINE =
            "com.android.systemui.statusbar.notification.row.ActivatableNotificationView";
    private static final String BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";

    private static boolean installed;
    private static boolean firstSquareLogged;

    private NativeSquareHeadsUpCorners() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        if (!enabled()) {
            Log.i(TAG, "Square heads-up corners disabled by " + ENABLE_PROP);
            return;
        }

        Class<?> row = XposedHelpers.findClassIfExists(ROW, loader);
        Class<?> outline = XposedHelpers.findClassIfExists(OUTLINE, loader);
        Class<?> background = XposedHelpers.findClassIfExists(BACKGROUND, loader);
        if (row == null || outline == null || background == null) {
            Log.w(TAG, "Q25 heads-up corner classes missing; native corners preserved");
            return;
        }
        try {
            // This Q25 ROM merges upstream ExpandableOutlineView into
            // ActivatableNotificationView. Hook its actual compiled methods.
            outline.getDeclaredMethod("getClipPath", boolean.class);
            background.getDeclaredMethod("setRadius", float.class, float.class);
            row.getDeclaredMethod("isPinned");
            row.getDeclaredMethod("applyRoundnessAndInvalidate");
        } catch (NoSuchMethodException error) {
            Log.w(TAG, "Q25 corner API changed; native corners preserved", error);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(outline, "getClipPath", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !isPopupRow(param.thisObject, row)
                                    || !(param.getResult() instanceof Path)) return;
                            Path nativePath = (Path) param.getResult();
                            if (nativePath.isEmpty()) return;
                            // Preserve SystemUI's own clipping bounds, translation
                            // and height; only remove its round-rect curvature.
                            RectF bounds = new RectF();
                            nativePath.computeBounds(bounds, true);
                            if (bounds.width() <= 0 || bounds.height() <= 0) return;
                            Path squared = new Path();
                            squared.addRect(bounds, Path.Direction.CW);
                            param.setResult(squared);
                            logFirstSquare();
                        }
                    });

            // Native background drawables (including blur) have their own
            // corner radii separate from the row's outline path.
            XposedHelpers.findAndHookMethod(background, "setRadius",
                    float.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)
                                    || !isPopupBackground((View) param.thisObject, row)) return;
                            param.args[0] = 0f;
                            param.args[1] = 0f;
                        }
                    });

            // If rounding changes after a popup is pinned, apply square
            // background radii immediately using SystemUI's own setter.
            XposedHelpers.findAndHookMethod(row, "applyRoundnessAndInvalidate",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !isPopupRow(param.thisObject, row)) {
                                return;
                            }
                            try {
                                Object bg = XposedHelpers.getObjectField(
                                        param.thisObject, "mBackgroundNormal");
                                if (background.isInstance(bg)) {
                                    XposedHelpers.callMethod(bg, "setRadius", 0f, 0f);
                                }
                                if (param.thisObject instanceof View) {
                                    ((View) param.thisObject).invalidateOutline();
                                }
                                logFirstSquare();
                            } catch (Throwable error) {
                                Log.w(TAG, "Could not refresh square heads-up background", error);
                            }
                        }
                    });

            installed = true;
            Log.i(TAG, "Native popup-only square-corner hooks registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to install native square heads-up corners", error);
        }
    }

    private static boolean isPopupRow(Object candidate, Class<?> rowClass) {
        if (!rowClass.isInstance(candidate)) return false;
        try {
            // Pinned is native SystemUI's heads-up popup state, not the
            // expanded/contracted row in the ordinary notification shade.
            return Boolean.TRUE.equals(XposedHelpers.callMethod(candidate, "isPinned"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPopupBackground(View backgroundView, Class<?> rowClass) {
        ViewParent parent = backgroundView.getParent();
        for (int depth = 0; parent instanceof View && depth < 8; depth++) {
            if (rowClass.isInstance(parent)) {
                if (!isPopupRow(parent, rowClass)) return false;
                try {
                    return XposedHelpers.getObjectField(parent, "mBackgroundNormal")
                            == backgroundView;
                } catch (Throwable ignored) {
                    return false;
                }
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static void logFirstSquare() {
        if (firstSquareLogged) return;
        firstSquareLogged = true;
        Log.i(TAG, "Applied square corners to first pinned heads-up popup");
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    props, "getBoolean", ENABLE_PROP, true));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read square-corner rollback property; retaining stock corners",
                    error);
            return false;
        }
    }
}
