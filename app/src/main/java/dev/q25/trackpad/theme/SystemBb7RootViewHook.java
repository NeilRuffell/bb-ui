package dev.q25.trackpad.theme;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Root-level classic Android UI background presentation.
 *
 * View.setBackgroundDrawable(Drawable) is the common endpoint for backgrounds
 * supplied by XML inflation, View.setBackground(), programmatic setters,
 * and most theme-driven native widgets. It is broader than any named Settings
 * adapter, resource ID, layout file, or one particular submenu.
 *
 * Only OS-owned contexts are changed. UI geometry, draw order, platform input,
 * accessibility, drawable state transitions and click handlers remain owned
 * by Android. Pure icon drawables, progress thumbs and check/radio indicators
 * are left alone rather than globally replacing all rounded geometry.
 */
final class SystemBb7RootViewHook {
    private static final String TAG = "BB7-SystemRoot";
    private static final AtomicBoolean HOOK_LOG = new AtomicBoolean();
    private static final AtomicBoolean APPLIED_LOG = new AtomicBoolean();
    private static final AtomicBoolean ERROR_LOG = new AtomicBoolean();

    // Unlike global Drawable/Canvas hooks, this guard is tied to the View
    // Context. It excludes third-party app content even if other packages
    // happen to share an instrumented process.
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private SystemBb7RootViewHook() {}

    static void install() {
        try {
            XposedBridge.hookAllMethods(View.class, "setBackgroundDrawable",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1
                            || !(param.args[0] instanceof Drawable)) return;
                    View view = (View) param.thisObject;
                    if (!eligible(view)) return;
                    try {
                        Drawable drawable = (Drawable) param.args[0];
                        Drawable squared = squareBackground(drawable, 0);
                        if (squared != null) param.args[0] = squared;
                        if (APPLIED_LOG.compareAndSet(false, true))
                            Log.i(TAG, "Root View background hook applied in "
                                    + view.getContext().getPackageName()
                                    + " drawable=" + drawable.getClass().getName());
                    } catch (Throwable error) {
                        if (ERROR_LOG.compareAndSet(false, true))
                            Log.w(TAG, "Root background left unchanged", error);
                    }
                }
            });
            if (HOOK_LOG.compareAndSet(false, true))
                Log.i(TAG, "Root native View background endpoint hooked");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot register View background endpoint", error);
        }
    }

    private static boolean eligible(View view) {
        Context context = view.getContext();
        if (context == null
                || !SystemBb7VisualHook.isOsOwnedContext(context)) return false;
        // These controls draw meaningful symbols or geometry (avatar masks,
        // toggle thumbs, progress indicators). Flatten their *container*
        // backgrounds through their ViewGroups instead.
        if (view instanceof ImageView || view instanceof ProgressBar
                || view instanceof CompoundButton
                || view instanceof EditText) return false;
        return true;
    }

    private static Drawable squareBackground(Drawable input, int depth) {
        if (input == null || depth > 6) return input;
        Drawable drawable = input.mutate();
        if (drawable instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) drawable;
            gradient.setCornerRadius(0f);
            gradient.setCornerRadii(null);
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                squareBackground(layers.getDrawable(index), depth + 1);
            }
        } else if (drawable instanceof InsetDrawable) {
            squareBackground(((InsetDrawable) drawable).getDrawable(), depth + 1);
        } else if (drawable.getClass().getName().equals(
                "com.google.android.material.shape.MaterialShapeDrawable")) {
            // Material Components is shipped by the system APK, not the
            // framework boot class loader. Reflect on the live drawable.
            try {
                Object shape = XposedHelpers.callMethod(
                        drawable, "getShapeAppearanceModel");
                Object builder = XposedHelpers.callMethod(shape, "toBuilder");
                XposedHelpers.callMethod(builder, "setAllCornerSizes", 0f);
                Object flat = XposedHelpers.callMethod(builder, "build");
                XposedHelpers.callMethod(drawable, "setShapeAppearanceModel", flat);
            } catch (Throwable ignored) {
                // A different Material version can retain its stock shape.
            }
        }
        return drawable;
    }
}
