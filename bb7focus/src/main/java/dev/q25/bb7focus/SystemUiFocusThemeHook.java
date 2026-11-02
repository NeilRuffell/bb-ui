package dev.q25.bb7focus;

import dev.q25.trackpad.theme.BB7Theme;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Appearance-only BB7 highlight for native SystemUI notification rows.
 *
 * The focus state is read from the existing ExpandableNotificationRow. Its
 * NotificationBackgroundView has duplicateParentState=true in the LineageOS
 * 24 notification row layout, so its drawableStateChanged and onDraw are
 * called with the row's current state.
 *
 * This module does not set focusability, request focus or intercept input.
 */
public final class SystemUiFocusThemeHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.systemui";
    private static final String TAG = "BB7-SystemUI";
    private static final String NOTIFICATION_ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String NOTIFICATION_BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";

    private static final int BLUE_TOP = BB7Theme.BLUE_TOP;
    private static final int BLUE_MIDDLE = BB7Theme.BLUE_MIDDLE;
    private static final int BLUE_BOTTOM = BB7Theme.BLUE_BOTTOM;
    private static final int TITLE_COLOR = BB7Theme.TITLE;
    private static final int SUMMARY_COLOR = BB7Theme.SUMMARY;

    // SystemUI View rendering is on its UI thread. Only one native View has
    // keyboard focus at a time. The active highlight is restored on focus
    // loss, replacement, and background detachment.
    private static Highlight active;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName) || !TARGET.equals(lp.processName)) return;
        Class<?> background = XposedHelpers.findClassIfExists(
                NOTIFICATION_BACKGROUND, lp.classLoader);
        if (background == null) {
            Log.w(TAG, "NotificationBackgroundView unavailable in classloader");
            return;
        }

        try {
            // The native background's state follows the focused parent row.
            XposedBridge.hookAllMethods(background, "drawableStateChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                syncFocus((View) param.thisObject);
                            } catch (Throwable error) {
                                Log.e(TAG, "Cannot synchronize notification focus state", error);
                            }
                        }
                    });

            // Draw after the stock notification background and before its
            // content, without replacing its native drawable or layout.
            XposedBridge.hookAllMethods(background, "onDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View backgroundView = (View) param.thisObject;
                    try {
                        View row = notificationRowFor(backgroundView);
                        if (row == null) return;
                        syncFocus(backgroundView);
                        if (active != null && active.row == row && row.isFocused()) {
                            drawBlueHighlight(backgroundView, (Canvas) param.args[0]);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "Cannot draw native notification highlight", error);
                    }
                }
            });

            // A notification may disappear without first losing focus.
            XposedBridge.hookAllMethods(background, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            View backgroundView = (View) param.thisObject;
                            if (active != null && active.background == backgroundView) {
                                clearHighlight();
                            }
                        }
                    });
            Log.i(TAG, "Native notification state/draw appearance hooks registered");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": native notification hook unavailable: " + error);
        }
    }

    private static View notificationRowFor(View background) {
        ViewParent parent = background.getParent();
        if (!(parent instanceof View)) return null;
        View row = (View) parent;
        if (!isClassOrSubclass(row.getClass(), NOTIFICATION_ROW)) return null;

        // Do not style dimmed/background siblings or unrelated custom views.
        try {
            if (XposedHelpers.getObjectField(row, "mBackgroundNormal") != background) {
                return null;
            }
        } catch (Throwable error) {
            return null;
        }
        return row;
    }

    private static boolean isClassOrSubclass(Class<?> type, String name) {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            if (name.equals(cls.getName())) return true;
        }
        return false;
    }

    private static void syncFocus(View background) {
        View row = notificationRowFor(background);
        if (row == null) return;
        boolean focused = row.isFocused() && row.isShown() && row.isEnabled()
                && row.isAttachedToWindow() && background.isShown();

        if (focused) {
            if (active != null && active.row == row) return;
            clearHighlight();
            Highlight highlight = new Highlight(row, background);
            active = highlight;
            highlight.apply();
            Log.i(TAG, "Native notification row blue focus applied: "
                    + row.getClass().getName());
        } else if (active != null && active.row == row) {
            clearHighlight();
            Log.i(TAG, "Native notification row blue focus cleared");
        }
    }

    private static void clearHighlight() {
        Highlight prior = active;
        active = null;
        if (prior != null) prior.restore();
    }

    private static void drawBlueHighlight(View background, Canvas canvas) {
        int width = background.getWidth();
        int height = background.getHeight();
        try {
            int actualHeight = XposedHelpers.getIntField(background, "mActualHeight");
            if (actualHeight > 0) height = Math.min(height, actualHeight);
            int actualWidth = XposedHelpers.getIntField(background, "mActualWidth");
            if (actualWidth > 0) width = Math.min(width, actualWidth);
        } catch (Throwable ignored) {
            // Retain the View's measured bounds if internal geometry changes.
        }
        if (width <= 0 || height <= 0) return;
        float radius = background.getResources().getDisplayMetrics().density * 8f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, height,
                new int[] {BLUE_TOP, BLUE_MIDDLE, BLUE_BOTTOM},
                null, Shader.TileMode.CLAMP));

        canvas.save();
        try {
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
        } finally {
            canvas.restore();
        }
    }

    private static boolean isSecondaryText(TextView view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(view.getId());
            return "text".equals(name) || "text2".equals(name)
                    || "big_text".equals(name) || "summary".equals(name)
                    || "info".equals(name) || "inbox_text0".equals(name);
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static void recolor(View view, Map<TextView, ColorStateList> originals) {
        if (view instanceof TextView && !(view instanceof EditText)
                && !(view instanceof CompoundButton)) {
            TextView text = (TextView) view;
            originals.put(text, text.getTextColors());
            text.setTextColor(isSecondaryText(text) ? SUMMARY_COLOR : TITLE_COLOR);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                recolor(group.getChildAt(i), originals);
            }
        }
    }

    private static final class Highlight {
        final View row;
        final View background;
        final Map<TextView, ColorStateList> originalText = new IdentityHashMap<>();

        Highlight(View row, View background) {
            this.row = row;
            this.background = background;
        }

        void apply() {
            try {
                recolor(row, originalText);
                background.invalidate();
            } catch (Throwable error) {
                Log.e(TAG, "Cannot recolor focused notification", error);
                restore();
            }
        }

        void restore() {
            try {
                for (Map.Entry<TextView, ColorStateList> entry : originalText.entrySet()) {
                    try {
                        entry.getKey().setTextColor(entry.getValue());
                    } catch (Throwable ignored) {
                        // Notification children can be rebound independently.
                    }
                }
                originalText.clear();
                background.invalidate();
            } catch (Throwable error) {
                Log.w(TAG, "Cannot fully restore notification text", error);
            }
        }
    }
}
