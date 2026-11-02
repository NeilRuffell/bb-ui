package dev.q25.bb7focus;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.widget.PopupWindow;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Squared, edge-to-edge Settings menu geometry at shared native and Compose
 * presenters. Only loaded by SettingsFocusThemeHook in com.android.settings.
 *
 * Compose: Material3 MenuDefaults, MenuKt.DropdownMenuContent and the
 * MenuKt menu sizing/padding tokens; native: MenuPopupWindow's inherited
 * ListPopupWindow.show(). No menu models or input handling are replaced.
 */
final class SettingsMenuGeometryHook {
    private static final String TAG = "BB7-MenuGeometry";
    private static final String SETTINGS = "com.android.settings";
    private static final AtomicBoolean COMPOSE_SHOWN = new AtomicBoolean();
    private static final AtomicBoolean COMPOSE_WIDTH = new AtomicBoolean();
    private static final AtomicBoolean NATIVE_SHOWN = new AtomicBoolean();

    private SettingsMenuGeometryHook() {}

    static void install(ClassLoader loader) {
        Class<?> shapeType = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.graphics.Shape", loader);
        Class<?> shapeKt = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.graphics.RectangleShapeKt", loader);
        Object rectangleShape = null;
        if (shapeType != null && shapeKt != null) {
            try {
                rectangleShape = XposedHelpers.callStaticMethod(
                        shapeKt, "getRectangleShape");
            } catch (Throwable error) {
                Log.w(TAG, "Compose rectangle shape unavailable", error);
            }
        }

        if (rectangleShape != null) {
            installComposeShapes(loader, shapeType, rectangleShape);
        } else {
            Log.w(TAG, "No Compose rectangular shape; original shape retained");
        }
        installComposeDimensions(loader);
        installComposeContainer(loader, shapeType, rectangleShape);
        installNativeWidth(loader);
    }

    /**
     * Default Material3 menu shape and menu-item/group shapes.
     * MenuItemShapes and MenuGroupShapes are menu-specific classes, so this
     * does not change buttons, dialogs, or regular Settings preference rows.
     */
    private static void installComposeShapes(ClassLoader loader, Class<?> shapeType,
            Object rectangleShape) {
        Class<?> defaults = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuDefaults", loader);
        if (defaults != null) {
            int count = 0;
            for (Method method : defaults.getDeclaredMethods()) {
                if (!method.getName().startsWith("get")
                        || !method.getName().contains("Shape")
                        || !shapeType.isAssignableFrom(method.getReturnType())) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(rectangleShape);
                        }
                    });
                    count++;
                } catch (Throwable error) {
                    Log.w(TAG, "MenuDefaults shape getter unchanged: "
                            + method.getName(), error);
                }
            }
            Log.i(TAG, "Material3 rectangular shape getters hooked=" + count);
        }

        for (String name : new String[]{
                "androidx.compose.material3.MenuItemShapes",
                "androidx.compose.material3.MenuGroupShapes"}) {
            Class<?> type = XposedHelpers.findClassIfExists(name, loader);
            if (type == null) continue;
            try {
                XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        // Only replace actual shape arguments; preserve synthetic
                        // mask/default arguments and all other constructor behaviour.
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] != null
                                    && shapeType.isInstance(param.args[i])) {
                                param.args[i] = rectangleShape;
                            }
                        }
                    }
                });
                Log.i(TAG, "Rectangular Material3 menu shapes: " + name);
            } catch (Throwable error) {
                Log.w(TAG, "Menu shape constructor unchanged: " + name, error);
            }
        }
    }

    /**
     * Material3 uses 112–280dp intrinsic item width and 8dp top/bottom menu
     * padding. These are shared MenuKt tokens, not a specific Apps menu.
     */
    private static void installComposeDimensions(ClassLoader loader) {
        Class<?> menuKt = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuKt", loader);
        if (menuKt == null) return;

        int widthCount = 0;
        int paddingCount = 0;
        for (Method method : menuKt.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0
                    || method.getReturnType() != float.class) continue;
            String methodName = method.getName();
            if (methodName.contains("DropdownMenuItemDefaultMinWidth")
                    || methodName.contains("DropdownMenuItemDefaultMaxWidth")) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Context app = settingsApplication(loader);
                            if (app == null) return;
                            float density = app.getResources()
                                    .getDisplayMetrics().density;
                            if (density <= 0f) return;
                            // Match the app-window width, then allow Compose's
                            // popup measurement to apply system/window insets.
                            float widthDp = app.getResources()
                                    .getDisplayMetrics().widthPixels / density;
                            if (widthDp <= 0f) return;
                            param.setResult(widthDp);
                            if (COMPOSE_WIDTH.compareAndSet(false, true))
                                Log.i(TAG, "Compose menu full-width item constraint: "
                                        + widthDp + "dp");
                        }
                    });
                    widthCount++;
                } catch (Throwable error) {
                    Log.w(TAG, "Compose menu width getter unchanged", error);
                }
            } else if (methodName.contains("DropdownMenuVerticalPadding")) {
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(0f);
                        }
                    });
                    paddingCount++;
                } catch (Throwable error) {
                    Log.w(TAG, "Compose menu vertical padding unchanged", error);
                }
            }
        }
        Log.i(TAG, "Compose width getters=" + widthCount
                + ", vertical padding getters=" + paddingCount);
    }

    /**
     * The shared Material3 surface compositor owns the final shape, shadow
     * elevation and root Modifier, regardless of callers' default values.
     * Force only menu geometry: flat rectangle, no shadow, full-width layout.
     */
    private static void installComposeContainer(ClassLoader loader,
            Class<?> shapeType, Object rectangleShape) {
        Class<?> menuKt = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuKt", loader);
        Class<?> modifierType = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.Modifier", loader);
        Class<?> sizeKt = XposedHelpers.findClassIfExists(
                "androidx.compose.foundation.layout.SizeKt", loader);
        if (menuKt == null || modifierType == null) {
            Log.w(TAG, "Compose menu container classes unavailable");
            return;
        }
        int count = 0;
        for (Method method : menuKt.getDeclaredMethods()) {
            if (!"DropdownMenuContent".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            int modifierIndex = -1;
            int shapeIndex = -1;
            for (int i = 0; i < types.length; i++) {
                if (modifierIndex < 0 && types[i] == modifierType) modifierIndex = i;
                if (shapeType != null && shapeIndex < 0 && types[i] == shapeType)
                    shapeIndex = i;
            }
            if (modifierIndex < 0 || shapeIndex < 0) continue;
            final int mi = modifierIndex;
            final int si = shapeIndex;
            final int shadowIndex = si + 3 < types.length
                    && types[si + 1] == long.class
                    && types[si + 2] == float.class
                    && types[si + 3] == float.class
                    ? si + 3 : -1;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (settingsApplication(loader) == null) return;
                        try {
                            if (rectangleShape != null)
                                param.args[si] = rectangleShape;
                            if (shadowIndex >= 0)
                                param.args[shadowIndex] = 0f;
                            boolean fullWidthApplied = false;
                            if (sizeKt != null
                                    && modifierType.isInstance(param.args[mi])) {
                                try {
                                    Object fullWidth = XposedHelpers.callStaticMethod(
                                            sizeKt, "fillMaxWidth", param.args[mi], 1f);
                                    if (modifierType.isInstance(fullWidth)) {
                                        param.args[mi] = fullWidth;
                                        fullWidthApplied = true;
                                    }
                                } catch (Throwable widthError) {
                                    Log.w(TAG, "Material3 full-width modifier unavailable",
                                            widthError);
                                }
                            }
                            if (COMPOSE_SHOWN.compareAndSet(false, true))
                                Log.i(TAG, "Material3 container: squared="
                                        + (rectangleShape != null)
                                        + ", shadow suppressed=" + (shadowIndex >= 0)
                                        + ", full-width=" + fullWidthApplied);
                        } catch (Throwable error) {
                            Log.w(TAG, "Compose menu geometry retained", error);
                        }
                    }
                });
                count++;
            } catch (Throwable error) {
                Log.w(TAG, "Compose menu container hook registration failed", error);
            }
        }
        Log.i(TAG, "Material3 dropdown container hooks=" + count);
    }

    /**
     * Both framework and AppCompat MenuPopupWindow inherit ListPopupWindow.show.
     * Set width at that final point, after native presenters measure text.
     */
    private static void installNativeWidth(ClassLoader loader) {
        installNativePresenter(loader, "android.widget.ListPopupWindow",
                "android.widget.MenuPopupWindow", "framework");
        installNativePresenter(loader, "androidx.appcompat.widget.ListPopupWindow",
                "androidx.appcompat.widget.MenuPopupWindow", "AppCompat");
    }

    private static void installNativePresenter(ClassLoader loader, String listName,
            String menuName, String label) {
        Class<?> list = XposedHelpers.findClassIfExists(listName, loader);
        Class<?> menu = XposedHelpers.findClassIfExists(menuName, loader);
        if (list == null || menu == null) return;
        try {
            XposedBridge.hookAllMethods(list, "show", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!menu.isInstance(param.thisObject)) return;
                    try {
                        Object anchorObj = XposedHelpers.callMethod(
                                param.thisObject, "getAnchorView");
                        if (!(anchorObj instanceof View)) return;
                        View anchor = (View) anchorObj;
                        if (!SETTINGS.equals(anchor.getContext().getPackageName())) return;
                        Rect visible = new Rect();
                        anchor.getWindowVisibleDisplayFrame(visible);
                        int width = visible.width();
                        if (width <= 0) return;
                        XposedHelpers.callMethod(param.thisObject, "setWidth", width);
                        Object innerPopup = XposedHelpers.getObjectField(
                                param.thisObject, "mPopup");
                        if (innerPopup instanceof PopupWindow)
                            ((PopupWindow) innerPopup).setElevation(0f);
                        if (NATIVE_SHOWN.compareAndSet(false, true))
                            Log.i(TAG, label + " native menu full-width=" + width
                                    + "px, shadow suppressed");
                    } catch (Throwable error) {
                        Log.w(TAG, label + " native menu size retained", error);
                    }
                }
            });
            Log.i(TAG, label + " native menu width hook registered");
        } catch (Throwable error) {
            Log.e(TAG, label + " native width hook registration failed", error);
        }
    }

    private static Context settingsApplication(ClassLoader loader) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            if (!(app instanceof Context)) return null;
            Context context = (Context) app;
            return SETTINGS.equals(context.getPackageName()) ? context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
