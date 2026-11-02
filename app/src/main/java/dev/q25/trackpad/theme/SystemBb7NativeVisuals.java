package dev.q25.trackpad.theme;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ListView;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Shared classic-View appearance for OS-owned application processes.
 *
 * Lineage Settings' RoundCornerPreferenceAdapter and its subclasses call
 * View.setBackgroundResource(settingslib_round_background_*). Replacing
 * those *resource backgrounds* at the View API catches every preference
 * screen that uses that shared styling, not just one Settings page.
 *
 * RecyclerView inset removal applies only to PreferenceGroupAdapter lists.
 * Other arbitrary RecyclerViews and app-specific custom views are untouched.
 */
final class SystemBb7NativeVisuals {
    private static final String TAG = "BB7-SystemSkin";
    private static final AtomicBoolean FLAT_ROW_LOG = new AtomicBoolean();
    private static final AtomicBoolean PADDING_LOG = new AtomicBoolean();
    private static final AtomicBoolean MATERIAL_LOG = new AtomicBoolean();
    private static final AtomicBoolean LIST_LOG = new AtomicBoolean();
    private static final AtomicBoolean DIALOG_LOG = new AtomicBoolean();
    private static final AtomicBoolean ERROR_LOG = new AtomicBoolean();

    private SystemBb7NativeVisuals() {}

    static void install(String packageName, ClassLoader loader) {
        installRoundedResourceReplacement();
        installPreferenceListInsets(loader);
        installNativeListSelection();
        installMaterialShapeDefaults(loader);
        if (!"com.android.settings".equals(packageName)) {
            installSystemAlertSurfaces();
        }
    }

    private static boolean isRoundPreferenceResource(String name) {
        // Literal resource names from AOSP/LineageOS SettingsLib and Settings.
        // Do not blindly change rounded icons, avatars, or other resources.
        return name.startsWith("settingslib_round_background")
                || name.startsWith("round_background_top")
                || name.startsWith("round_background_bottom")
                || name.startsWith("round_background_center")
                || "round_background".equals(name)
                || "round_background_selected".equals(name);
    }

    private static void installRoundedResourceReplacement() {
        try {
            XposedBridge.hookAllMethods(View.class, "setBackgroundResource",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1
                            || !(param.args[0] instanceof Integer)) return;
                    int resourceId = (Integer) param.args[0];
                    if (resourceId == 0) return;
                    View view = (View) param.thisObject;
                    try {
                        Resources resources = view.getResources();
                        String name = resources.getResourceEntryName(resourceId);
                        if (!isRoundPreferenceResource(name)) return;

                        BB7Theme.Palette palette = BB7Theme.palette(view.getContext());
                        boolean highlighted = name.endsWith("_selected");
                        Drawable flat = highlighted
                                ? BB7Theme.selected(0f, 0)
                                : new FlatRowDrawable(palette.card, palette.divider,
                                        Math.max(1, Math.round(resources
                                                .getDisplayMetrics().density)));
                        // One stable shared interception point: subclass adapters
                        // cannot restore rounded assets on rebind.
                        view.setBackground(flat);
                        param.setResult(null);
                        if (FLAT_ROW_LOG.compareAndSet(false, true))
                            Log.i(TAG, "Shared round preference backgrounds replaced: "
                                    + name + " in " + view.getContext().getPackageName());
                    } catch (Resources.NotFoundException ignored) {
                        // Non-resource backgrounds and invalid IDs pass through.
                    } catch (Throwable error) {
                        if (ERROR_LOG.compareAndSet(false, true))
                            Log.w(TAG, "Preference background retained", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "Shared preference background hook failed", error);
        }
    }

    private static void installPreferenceListInsets(ClassLoader loader) {
        final Class<?> preferenceAdapter = XposedHelpers.findClassIfExists(
                "androidx.preference.PreferenceGroupAdapter", loader);
        final Class<?> recyclerView = XposedHelpers.findClassIfExists(
                "androidx.recyclerview.widget.RecyclerView", loader);
        if (preferenceAdapter == null || recyclerView == null) {
            Log.i(TAG, "AndroidX preference list unavailable in this system process");
            return;
        }
        XC_MethodHook stripSideInsets = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!recyclerView.isInstance(param.thisObject)
                        || param.args.length != 4) return;
                View list = (View) param.thisObject;
                try {
                    Object adapter = XposedHelpers.callMethod(list, "getAdapter");
                    if (!preferenceAdapter.isInstance(adapter)) return;
                    // Both View.setPadding(l,t,r,b) and setPaddingRelative
                    // (start,t,end,b) use these indices.
                    int beforeStart = (Integer) param.args[0];
                    int beforeEnd = (Integer) param.args[2];
                    if (beforeStart == 0 && beforeEnd == 0) return;
                    param.args[0] = 0;
                    param.args[2] = 0;
                    if (PADDING_LOG.compareAndSet(false, true))
                        Log.i(TAG, "System preference list made flush to parent edges");
                } catch (Throwable error) {
                    if (ERROR_LOG.compareAndSet(false, true))
                        Log.w(TAG, "Preference list original padding retained", error);
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(View.class, "setPadding", stripSideInsets);
            XposedBridge.hookAllMethods(View.class, "setPaddingRelative", stripSideInsets);
            Log.i(TAG, "Shared system preference-list side-inset hook registered");
        } catch (Throwable error) {
            Log.w(TAG, "System preference side-inset hook unavailable", error);
        }
    }

    private static void installNativeListSelection() {
        try {
            XposedBridge.hookAllMethods(ListView.class, "setSelector",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || param.args[0] == null) return;
                    ListView list = (ListView) param.thisObject;
                    int line = Math.max(1, Math.round(
                            list.getResources().getDisplayMetrics().density));
                    param.args[0] = BB7Theme.selected(0f, line);
                    if (LIST_LOG.compareAndSet(false, true))
                        Log.i(TAG, "Native system lists use BB7 selection gradient");
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Native system list selector hook unavailable", error);
        }
    }

    /**
     * Material Components ShapeAppearanceModel is shared by MaterialCardView,
     * MaterialButton and other widgets. Square only the instantiated drawable
     * shape in OS-owned processes (never a framework/global resource overlay).
     */
    private static void installMaterialShapeDefaults(ClassLoader loader) {
        Class<?> shapeDrawable = XposedHelpers.findClassIfExists(
                "com.google.android.material.shape.MaterialShapeDrawable", loader);
        Class<?> shapeModel = XposedHelpers.findClassIfExists(
                "com.google.android.material.shape.ShapeAppearanceModel", loader);
        if (shapeDrawable == null || shapeModel == null) return;
        try {
            XposedBridge.hookAllMethods(shapeDrawable,
                    "setShapeAppearanceModel", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1
                            || !shapeModel.isInstance(param.args[0])) return;
                    try {
                        param.args[0] = squaredMaterialShape(param.args[0]);
                        if (MATERIAL_LOG.compareAndSet(false, true))
                            Log.i(TAG, "System Material Components shapes squared");
                    } catch (Throwable error) {
                        if (ERROR_LOG.compareAndSet(false, true))
                            Log.w(TAG, "Material shape unchanged", error);
                    }
                }
            });
            XposedBridge.hookAllConstructors(shapeDrawable, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object model = XposedHelpers.callMethod(
                                param.thisObject, "getShapeAppearanceModel");
                        if (!shapeModel.isInstance(model)) return;
                        XposedHelpers.callMethod(param.thisObject,
                                "setShapeAppearanceModel", squaredMaterialShape(model));
                    } catch (Throwable ignored) {
                        // A failed optional visual tweak must not disrupt app launch.
                    }
                }
            });
            Log.i(TAG, "Shared Material Components square-shape hooks registered");
        } catch (Throwable error) {
            Log.w(TAG, "Shared Material shape hooks unavailable", error);
        }
    }

    private static Object squaredMaterialShape(Object shape) {
        Object builder = XposedHelpers.callMethod(shape, "toBuilder");
        XposedHelpers.callMethod(builder, "setAllCornerSizes", 0f);
        return XposedHelpers.callMethod(builder, "build");
    }

    private static void installSystemAlertSurfaces() {
        try {
            XposedBridge.hookAllMethods(Dialog.class, "show",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Dialog dialog = (Dialog) param.thisObject;
                    if (!isAlertDialog(dialog.getClass())) return;
                    Window window = dialog.getWindow();
                    if (window == null) return;
                    try {
                        window.setBackgroundDrawable(dialogSurface(dialog.getContext()));
                        if (DIALOG_LOG.compareAndSet(false, true))
                            Log.i(TAG, "Shared system alert-dialog surface squared");
                    } catch (Throwable error) {
                        if (ERROR_LOG.compareAndSet(false, true))
                            Log.w(TAG, "System alert surface unchanged", error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "System alert-dialog surface hook unavailable", error);
        }
    }

    private static boolean isAlertDialog(Class<?> type) {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            String name = cls.getName();
            if ("android.app.AlertDialog".equals(name)
                    || "androidx.appcompat.app.AlertDialog".equals(name)) return true;
        }
        return false;
    }

    private static Drawable dialogSurface(Context context) {
        BB7Theme.Palette p = BB7Theme.palette(context);
        boolean dark = p == BB7Theme.Palette.DARK;
        GradientDrawable surface = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                dark ? new int[]{0xff374657, 0xff273544, 0xff192633}
                        : new int[]{0xfff9fafc, 0xffedf1f5, 0xffe5eaf0});
        surface.setCornerRadius(0f);
        surface.setStroke(Math.max(1, Math.round(
                context.getResources().getDisplayMetrics().density)), p.divider);
        return surface;
    }

    private static final class FlatRowDrawable extends Drawable {
        private final Paint fill = new Paint();
        private final Paint divider = new Paint();
        private final int dividerSize;

        FlatRowDrawable(int fillColor, int dividerColor, int dividerSize) {
            fill.setColor(fillColor);
            divider.setColor(dividerColor);
            this.dividerSize = dividerSize;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRect(bounds, fill);
            canvas.drawRect(bounds.left, bounds.bottom - dividerSize,
                    bounds.right, bounds.bottom, divider);
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            divider.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            divider.setColorFilter(filter);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
