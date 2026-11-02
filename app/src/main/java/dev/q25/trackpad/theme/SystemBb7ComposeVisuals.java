package dev.q25.trackpad.theme;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Shared Material3 appearance across OS-owned Compose processes.
 *
 * AndroidX Material3 card, navigation, menu, dialog and button composables
 * resolve styles through ColorScheme and Shapes / ShapeTokens. Hook those
 * theme primitives, not individual screens or Compose popup instances.
 *
 * No Compose dependency is compiled into BB UI; these are runtime hooks
 * against the target OS process's own packaged Compose classes.
 */
final class SystemBb7ComposeVisuals {
    private static final String TAG = "BB7-SystemSkin";
    private static final AtomicBoolean COLOR_LOG = new AtomicBoolean();
    private static final AtomicBoolean SHAPE_LOG = new AtomicBoolean();
    private static final AtomicBoolean ERROR_LOG = new AtomicBoolean();
    private static volatile Context application;

    private SystemBb7ComposeVisuals() {}

    static void install(String packageName, ClassLoader loader) {
        installMaterialColors(loader,
                "androidx.compose.material3.ColorScheme", "Material3");
        installMaterialColors(loader,
                "androidx.compose.material.Colors", "Material2");
        installMaterialShapes(loader);
        installSharedCornerShapeRenderer(loader);
    }

    private static void installMaterialColors(ClassLoader loader,
            String schemeClass, String family) {
        Class<?> scheme = XposedHelpers.findClassIfExists(schemeClass, loader);
        if (scheme == null) {
            Log.i(TAG, family + " theme absent in this OS process");
            return;
        }
        int count = 0;
        for (Method method : scheme.getDeclaredMethods()) {
            if (!method.getName().startsWith("get")
                    || method.getReturnType() != long.class
                    || method.getParameterTypes().length != 0) continue;
            final String token = colorTokenName(method.getName());
            if (token == null) continue;
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = currentApplication(loader);
                        if (context == null) return;
                        BB7Theme.Palette p = BB7Theme.palette(context);
                        int rgb = mappedColor(token, p);
                        param.setResult((((long) rgb) & 0xffffffffL) << 32);
                        if (COLOR_LOG.compareAndSet(false, true))
                            Log.i(TAG, "Shared " + family + " system palette active: "
                                    + token + " dark="
                                    + (p == BB7Theme.Palette.DARK));
                    }
                });
                count++;
            } catch (Throwable error) {
                if (ERROR_LOG.compareAndSet(false, true))
                    Log.w(TAG, family + " palette getter unchanged", error);
            }
        }
        Log.i(TAG, "Shared " + family + " palette getters=" + count);
    }

    /** Kotlin Color inline-class getter names may have a mangled suffix. */
    private static String colorTokenName(String method) {
        // Check longest first so getSurfaceContainerHigh is not matched as
        // getSurfaceContainer. Only map known Material3 ColorScheme properties.
        final String[] names = {
                "OnPrimaryContainer", "OnSecondaryContainer", "OnSurfaceVariant",
                "PrimaryVariant", "SecondaryVariant",
                "SurfaceContainerHighest", "SurfaceContainerLowest",
                "SurfaceContainerHigh", "SurfaceContainerLow", "SurfaceContainer",
                "PrimaryContainer", "SecondaryContainer", "InverseOnSurface",
                "OnBackground", "OnPrimary", "OnSecondary", "OnSurface",
                "OutlineVariant", "InverseSurface", "SurfaceVariant",
                "SurfaceBright", "SurfaceDim", "SurfaceTint",
                "Background", "Surface", "Primary", "Secondary", "Outline"
        };
        for (String name : names) {
            String prefix = "get" + name;
            if (method.equals(prefix) || method.startsWith(prefix + "-"))
                return name;
        }
        return null;
    }

    private static int mappedColor(String token, BB7Theme.Palette p) {
        switch (token) {
            case "Background":
            case "SurfaceContainerLowest":
            case "SurfaceDim":
                return p.canvas;
            case "OnBackground":
            case "OnSurface":
            case "OnPrimaryContainer":
            case "OnSecondaryContainer":
                return p.title;
            case "OnSurfaceVariant":
                return p.body;
            case "SurfaceContainerLow":
                return p.left;
            case "Surface":
            case "SurfaceVariant":
            case "SurfaceContainer":
            case "PrimaryContainer":
                return p.card;
            case "SurfaceContainerHigh":
            case "SurfaceContainerHighest":
            case "SurfaceBright":
            case "SecondaryContainer":
                return p.action;
            case "Primary":
            case "SurfaceTint":
                return BB7Theme.BLUE_MIDDLE;
            case "PrimaryVariant":
                return BB7Theme.BLUE_BOTTOM;
            case "SecondaryVariant":
                return p.divider;
            case "Secondary":
                return p.actionText;
            case "OnPrimary":
            case "OnSecondary":
                return BB7Theme.TITLE;
            case "InverseSurface":
                return p.title;
            case "InverseOnSurface":
                return p.canvas;
            case "Outline":
            case "OutlineVariant":
                return p.divider;
            default:
                return p.canvas;
        }
    }

    /**
     * Actual Compose shape-rendering boundary, below Material2/Material3
     * and below individual composables.
     *
     * Both Material theme shapes and explicit RoundedCornerShape/CircleShape
     * ultimately call a CornerBasedShape subclass createOutline(size,
     * topStart, topEnd, bottomEnd, bottomStart, layoutDirection).
     * Setting only those four *resolved corner sizes* to zero makes the
     * original Compose implementation produce its native rectangular
     * Outline. This never replaces clipping or drawing engines and never
     * needs an individual Settings screen's class name.
     *
     * The overload with four floats is the subclass method, not the final
     * packed-Size/LayoutDirection/Density method on CornerBasedShape.
     */
    private static void installSharedCornerShapeRenderer(ClassLoader loader) {
        String[] classes = {
                "androidx.compose.foundation.shape.RoundedCornerShape",
                "androidx.compose.foundation.shape.AbsoluteRoundedCornerShape",
                "androidx.compose.foundation.shape.CutCornerShape",
                "androidx.compose.foundation.shape.AbsoluteCutCornerShape"
        };
        int hooks = 0;
        for (String name : classes) {
            Class<?> cls = XposedHelpers.findClassIfExists(name, loader);
            if (cls == null) continue;
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().startsWith("createOutline")) continue;
                Class<?>[] parameters = method.getParameterTypes();
                int firstCorner = -1;
                for (int i = 0; i + 3 < parameters.length; i++) {
                    if (parameters[i] == float.class
                            && parameters[i + 1] == float.class
                            && parameters[i + 2] == float.class
                            && parameters[i + 3] == float.class) {
                        firstCorner = i;
                        break;
                    }
                }
                if (firstCorner < 0) continue;
                final int start = firstCorner;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            // The original method still owns the resulting
                            // Outline and the AndroidX clip/draw lifecycle.
                            for (int i = start; i < start + 4; i++)
                                param.args[i] = 0f;
                            if (SHAPE_LOG.compareAndSet(false, true))
                                Log.i(TAG, "Global OS Compose corners squared at "
                                        + method.getDeclaringClass().getName()
                                        + "." + method.getName());
                        }
                    });
                    hooks++;
                } catch (Throwable error) {
                    Log.w(TAG, "Compose shape-renderer hook skipped: "
                            + cls.getName() + "." + method.getName(), error);
                }
            }
        }
        Log.i(TAG, "Global OS Compose shape-renderer hooks=" + hooks);
    }

    private static void installMaterialShapes(ClassLoader loader) {
        final Class<?> shape = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.graphics.Shape", loader);
        final Class<?> cornerBased = XposedHelpers.findClassIfExists(
                "androidx.compose.foundation.shape.CornerBasedShape", loader);
        final Class<?> rectangleKt = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.graphics.RectangleShapeKt", loader);
        final Class<?> roundedKt = XposedHelpers.findClassIfExists(
                "androidx.compose.foundation.shape.RoundedCornerShapeKt", loader);
        Object rectangle = null;
        Object squareCorner = null;
        try {
            if (rectangleKt != null)
                rectangle = XposedHelpers.callStaticMethod(
                        rectangleKt, "getRectangleShape");
            if (cornerBased != null && roundedKt != null) {
                for (Method method : roundedKt.getDeclaredMethods()) {
                    if (!method.getName().startsWith("RoundedCornerShape")
                            || !Modifier.isStatic(method.getModifiers())
                            || method.getParameterTypes().length != 1
                            || method.getParameterTypes()[0] != float.class
                            || !cornerBased.isAssignableFrom(method.getReturnType())) continue;
                    method.setAccessible(true);
                    Object candidate = method.invoke(null, 0f);
                    if (cornerBased.isInstance(candidate)) {
                        squareCorner = candidate;
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Compose rectangle/corner factories unavailable", error);
        }
        if (shape == null || rectangle == null) {
            Log.i(TAG, "Compose shape interface unavailable");
            return;
        }
        int count = 0;
        // Both the theme's CornerBasedShape slots and component-level
        // Shape-token getters must be squared. Theme-only overrides miss
        // components whose defaults use Material3's own token shapes.
        String[] types = {
                "androidx.compose.material.Shapes",
                "androidx.compose.material.CardDefaults",
                "androidx.compose.material.ButtonDefaults",
                "androidx.compose.material.AlertDialogDefaults",
                "androidx.compose.material3.Shapes",
                "androidx.compose.material3.ShapeDefaults",
                "androidx.compose.material3.tokens.ShapeTokens",
                "androidx.compose.material3.MenuDefaults",
                "androidx.compose.material3.CardDefaults",
                "androidx.compose.material3.ButtonDefaults",
                "androidx.compose.material3.AlertDialogDefaults",
                "androidx.compose.material3.FloatingActionButtonDefaults",
                "androidx.compose.material3.NavigationDrawerDefaults",
                "androidx.compose.material3.SheetDefaults"
        };
        for (String className : types) {
            Class<?> cls = XposedHelpers.findClassIfExists(className, loader);
            if (cls == null) continue;
            for (Method method : cls.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("get") || !name.contains("Shape")
                        && !className.endsWith(".Shapes")
                        && !className.endsWith(".ShapeDefaults")
                        && !className.endsWith(".ShapeTokens")) continue;
                if (!shape.isAssignableFrom(method.getReturnType())) continue;
                final Object replacement = cornerBased != null
                        && cornerBased.isAssignableFrom(method.getReturnType())
                        ? squareCorner : rectangle;
                if (replacement == null
                        || !method.getReturnType().isInstance(replacement)) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(replacement);
                            if (SHAPE_LOG.compareAndSet(false, true))
                                Log.i(TAG, "Shared Compose system shapes squared: "
                                        + className + "." + name);
                        }
                    });
                    count++;
                } catch (Throwable error) {
                    if (ERROR_LOG.compareAndSet(false, true))
                        Log.w(TAG, "Material3 shape token unchanged", error);
                }
            }
        }
        Log.i(TAG, "Shared Compose squared shape getters=" + count);
    }

    private static Context currentApplication(ClassLoader loader) {
        if (application != null) return application;
        try {
            Object running = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            if (running instanceof Context) {
                application = (Context) running;
                return application;
            }
        } catch (Throwable ignored) {
            // Application not yet initialized. Keep original theme.
        }
        return null;
    }
}
