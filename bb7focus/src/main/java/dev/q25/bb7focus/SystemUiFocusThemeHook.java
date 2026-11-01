package dev.q25.bb7focus;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * BB7 blue highlight for native SystemUI notification rows only.
 *
 * Navigation-neutral: never sets focusability, requests focus, changes touch/key
 * events, changes Android notification state, or mutates the row's own drawable.
 * We draw a focused gradient inside NotificationBackgroundView after its
 * stock background, and invalidate it when focus moves.
 *
 * LineageOS 24 QS is Compose-based and deliberately not included.
 */
public final class SystemUiFocusThemeHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.systemui";
    private static final String TAG = "BB7-SystemUI";
    private static final String NOTIFICATION_ROW =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String NOTIFICATION_BACKGROUND =
            "com.android.systemui.statusbar.notification.row.NotificationBackgroundView";

    private static final int BLUE_TOP = 0xff459fd9;
    private static final int BLUE_MIDDLE = 0xff3285c5;
    private static final int BLUE_BOTTOM = 0xff2368aa;
    private static final int TITLE_COLOR = 0xffffffff;
    private static final int SUMMARY_COLOR = 0xffe7f2ff;

    private static final Map<ViewTreeObserver, Highlight> ACTIVE = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName) || !TARGET.equals(lp.processName)) return;
        // Handle the common framework dispatch rather than modifying any
        // SystemUI focus/navigation methods.
        try {
            XposedBridge.hookAllMethods(ViewTreeObserver.class,
                    "dispatchOnGlobalFocusChange", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View newFocus = (View) param.args[1];
                                onFocus((ViewTreeObserver) param.thisObject, newFocus);
                            } catch (Throwable error) {
                                Log.e(TAG, "Focus observation failed", error);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(ViewTreeObserver.class,
                    "dispatchOnWindowFocusChange", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!((Boolean) param.args[0])) {
                                clear((ViewTreeObserver) param.thisObject);
                            }
                        }
                    });
            Log.i(TAG, "SystemUI native notification focus observers registered");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": framework focus hook unavailable: " + error);
        }

        Class<?> background = XposedHelpers.findClassIfExists(NOTIFICATION_BACKGROUND,
                lp.classLoader);
        if (background == null) {
            Log.w(TAG, "NotificationBackgroundView unavailable: skipping native highlight");
            return;
        }

        try {
            XposedBridge.hookAllMethods(background, "onDraw", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    if (!isHighlighted(view)) return;
                    try {
                        drawBlueHighlight(view, (Canvas) param.args[0]);
                    } catch (Throwable error) {
                        Log.e(TAG, "Cannot draw notification focus highlight", error);
                    }
                }
            });
            Log.i(TAG, "Native notification background drawing hook registered");
        } catch (Throwable error) {
            XposedBridge.log(TAG + ": notification drawing hook unavailable: " + error);
        }
    }

    private static void onFocus(ViewTreeObserver observer, View focused) {
        Highlight existing = ACTIVE.get(observer);
        if (existing != null && existing.row == focused && focused != null
                && focused.isAttachedToWindow() && focused.isShown()) return;
        clear(observer);
        // We only style an actually focused row. We do not decorate an entire
        // notification when its child reply field or action button has focus.
        if (focused == null || !focused.isShown() || !focused.isEnabled()
                || !focused.isAttachedToWindow()
                || !isClassOrSubclass(focused.getClass(), NOTIFICATION_ROW)) return;
        View background;
        try {
            Object candidate = XposedHelpers.getObjectField(focused, "mBackgroundNormal");
            if (!(candidate instanceof View)
                    || !isClassOrSubclass(candidate.getClass(), NOTIFICATION_BACKGROUND)) {
                Log.w(TAG, "Focused row has no compatible native background");
                return;
            }
            background = (View) candidate;
            if (!background.isShown()) return;
        } catch (Throwable error) {
            Log.w(TAG, "Notification background lookup unavailable", error);
            return;
        }
        Highlight highlight = new Highlight(focused, background);
        ACTIVE.put(observer, highlight);
        highlight.apply();
        Log.i(TAG, "Native notification row blue focus applied");
    }

    private static boolean isClassOrSubclass(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (name.equals(c.getName())) return true;
        }
        return false;
    }

    private static void clear(ViewTreeObserver observer) {
        Highlight existing = ACTIVE.remove(observer);
        if (existing != null) existing.restore();
    }

    private static boolean isHighlighted(View background) {
        for (Highlight active : ACTIVE.values()) {
            if (active.background == background && active.row.isFocused()) return true;
        }
        return false;
    }

    private static void drawBlueHighlight(View view, Canvas canvas) {
        int width = view.getWidth();
        int height = view.getHeight();
        try {
            int actualHeight = XposedHelpers.getIntField(view, "mActualHeight");
            if (actualHeight > 0) height = Math.min(height, actualHeight);
        } catch (Throwable ignored) {
            // Use the actual view bounds if the internal field changes.
        }
        if (width <= 0 || height <= 0) return;
        float radius = view.getResources().getDisplayMetrics().density * 8f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, height,
                new int[] { BLUE_TOP, BLUE_MIDDLE, BLUE_BOTTOM },
                null, Shader.TileMode.CLAMP));
        canvas.save();
        try {
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
        } finally {
            canvas.restore();
        }
    }

    private static boolean isSecondaryText(TextView textView) {
        if (textView.getId() == View.NO_ID) return false;
        try {
            String name = textView.getResources().getResourceEntryName(textView.getId());
            return "text".equals(name) || "text2".equals(name)
                    || "big_text".equals(name) || "summary".equals(name)
                    || "info".equals(name) || "inbox_text0".equals(name);
        } catch (android.content.res.Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static void recolor(View view, Map<TextView, ColorStateList> original) {
        if (view instanceof TextView
                && !(view instanceof EditText)
                && !(view instanceof CompoundButton)) {
            TextView text = (TextView) view;
            original.put(text, text.getTextColors());
            text.setTextColor(isSecondaryText(text) ? SUMMARY_COLOR : TITLE_COLOR);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                recolor(group.getChildAt(i), original);
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
                Log.e(TAG, "Cannot apply notification highlight", error);
                restore();
            }
        }

        void restore() {
            for (Map.Entry<TextView, ColorStateList> item : originalText.entrySet()) {
                try {
                    item.getKey().setTextColor(item.getValue());
                } catch (Throwable ignored) {
                    // Notification content may be detached/rebound independently.
                }
            }
            originalText.clear();
            background.invalidate();
        }
    }
}
