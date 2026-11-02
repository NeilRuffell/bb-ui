package dev.q25.bb7focus;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.q25.trackpad.theme.BB7Theme;

/**
 * Native Compose focus appearance for Settings SPA's Material3 dropdowns.
 *
 * SettingsLib MoreOptionsScope.MenuItem uses Material3 DropdownMenuItem.
 * That implementation delegates its rows to MenuKt.DropdownMenuItemContent,
 * which receives a Modifier before applying its own clickable/focus target.
 *
 * Compose is responsible for focus and keyboard navigation. The extra
 * onFocusChanged + drawBehind modifiers add VISUALS ONLY and read/write a
 * Compose MutableState to invalidate drawing when the native focus moves.
 *
 * The drawing is confined to the focused item's own DrawScope, behind its
 * content: no global overlays, view replacements or synthetic key events.
 * Reflection is required because this Java-only module deliberately does
 * not compile against an arbitrary Compose runtime version.
 */
final class SettingsComposeMenuFocusHook {
    private static final String TAG = "BB7-PopupMenu";
    private static final AtomicBoolean ITEM_SEEN = new AtomicBoolean();
    private static final AtomicBoolean FOCUS_SEEN = new AtomicBoolean();
    private static final AtomicBoolean DRAW_SEEN = new AtomicBoolean();
    private static final AtomicBoolean ERROR_SEEN = new AtomicBoolean();

    private SettingsComposeMenuFocusHook() {}

    static void install(ClassLoader loader) {
        Class<?> menuKt = XposedHelpers.findClassIfExists(
                "androidx.compose.material3.MenuKt", loader);
        Class<?> focusKt = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.focus.FocusChangedModifierKt", loader);
        Class<?> drawKt = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.draw.DrawModifierKt", loader);
        Class<?> stateKt = XposedHelpers.findClassIfExists(
                "androidx.compose.runtime.SnapshotStateKt", loader);
        // Both mutableStateOf() and structuralEqualityPolicy() are exported
        // from SnapshotStateKt. AndroidX marks SnapshotMutationPolicy.kt
        // @file:JvmName("SnapshotStateKt") / @file:JvmMultifileClass;
        // there is no SnapshotMutationPolicyKt runtime facade.
        Class<?> function1 = XposedHelpers.findClassIfExists(
                "kotlin.jvm.functions.Function1", loader);
        Class<?> modifier = XposedHelpers.findClassIfExists(
                "androidx.compose.ui.Modifier", loader);
        Class<?> unitType = XposedHelpers.findClassIfExists("kotlin.Unit", loader);

        if (menuKt == null || focusKt == null || drawKt == null
                || stateKt == null || function1 == null
                || modifier == null || unitType == null) {
            Log.w(TAG, "Compose focus dependencies unavailable: "
                    + "menuKt=" + (menuKt != null)
                    + " focusKt=" + (focusKt != null)
                    + " drawKt=" + (drawKt != null)
                    + " stateKt=" + (stateKt != null)
                    + " function1=" + (function1 != null)
                    + " modifier=" + (modifier != null)
                    + " unitType=" + (unitType != null));
            return;
        }

        try {
            final Object policy = XposedHelpers.callStaticMethod(
                    stateKt, "structuralEqualityPolicy");
            final Object unit = unitType.getField("INSTANCE").get(null);
            int hooks = 0;
            for (Method method : menuKt.getDeclaredMethods()) {
                if (!method.getName().startsWith("DropdownMenuItemContent")) continue;
                int modifierIndex = -1;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == modifier) {
                        modifierIndex = i;
                        break;
                    }
                }
                if (modifierIndex < 0) continue;
                final int index = modifierIndex;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object original = param.args[index];
                        if (original == null || !modifier.isInstance(original)) return;
                        try {
                            // Per-row snapshot state is also observed by its draw modifier.
                            // This invalidates Compose's draw node as focus changes.
                            final Object focusState = XposedHelpers.callStaticMethod(
                                    stateKt, "mutableStateOf", Boolean.FALSE, policy);
                            final Object onFocus = callback(loader, function1,
                                    unit, arguments -> {
                                        if (arguments.length > 0 && arguments[0] != null) {
                                            boolean focused = Boolean.TRUE.equals(
                                                    XposedHelpers.callMethod(
                                                            arguments[0], "isFocused"));
                                            XposedHelpers.callMethod(
                                                    focusState, "setValue", focused);
                                            if (focused && FOCUS_SEEN.compareAndSet(false, true)) {
                                                Log.i(TAG, "Compose menu item received native focus");
                                            }
                                        }
                                    });
                            final Object onDraw = callback(loader, function1,
                                    unit, arguments -> {
                                        if (arguments.length == 0 || arguments[0] == null) return;
                                        if (!Boolean.TRUE.equals(
                                                XposedHelpers.callMethod(
                                                        focusState, "getValue"))) return;
                                        drawGradientBehind(arguments[0]);
                                    });
                            Object decorated = XposedHelpers.callStaticMethod(
                                    focusKt, "onFocusChanged", original, onFocus);
                            decorated = XposedHelpers.callStaticMethod(
                                    drawKt, "drawBehind", decorated, onDraw);
                            if (!modifier.isInstance(decorated)) return;
                            param.args[index] = decorated;
                            if (ITEM_SEEN.compareAndSet(false, true)) {
                                Log.i(TAG, "Compose dropdown item visual modifier injected");
                            }
                        } catch (Throwable error) {
                            if (ERROR_SEEN.compareAndSet(false, true))
                                Log.e(TAG, "Compose menu modifier injection failed", error);
                        }
                    }
                });
                hooks++;
                Log.i(TAG, "Compose DropdownMenuItemContent hooked: "
                        + method.getName() + " modifierIndex=" + index);
            }
            if (hooks == 0) {
                Log.w(TAG, "No Material3 DropdownMenuItemContent method matched; "
                        + "no menu-row styling installed");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register Compose dropdown item styling", error);
        }
    }

    private interface CallbackBody {
        void invoke(Object[] arguments) throws Exception;
    }

    private static Object callback(ClassLoader loader, Class<?> function1,
            Object unit, CallbackBody callback) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("invoke".equals(method.getName())) {
                try {
                    callback.invoke(arguments == null ? new Object[0] : arguments);
                } catch (Throwable error) {
                    if (ERROR_SEEN.compareAndSet(false, true))
                        Log.e(TAG, "Compose focus/drawing callback failed", error);
                }
                return unit;
            }
            if ("toString".equals(method.getName())) return "BB7MenuFocusVisual";
            if ("hashCode".equals(method.getName()))
                return System.identityHashCode(proxy);
            if ("equals".equals(method.getName()))
                return arguments != null && arguments.length == 1
                        && proxy == arguments[0];
            return null;
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{function1}, handler);
    }

    private static void drawGradientBehind(Object scope) throws Exception {
        Object drawContext = XposedHelpers.callMethod(scope, "getDrawContext");
        Object composeCanvas = XposedHelpers.callMethod(drawContext, "getCanvas");
        // AndroidCanvas has a native android.graphics.Canvas backing field.
        // Drawing within the DrawScope prevents painting outside this menu row.
        Canvas canvas = (Canvas) XposedHelpers.getObjectField(
                composeCanvas, "internalCanvas");

        // Compose Size is packed as float width/height in a long. The Kotlin
        // inline-class accessor has a compiler-mangled name; discover it.
        Method sizeGetter = null;
        for (Method candidate : drawContext.getClass().getMethods()) {
            if (candidate.getName().startsWith("getSize")
                    && candidate.getParameterTypes().length == 0
                    && candidate.getReturnType() == long.class) {
                sizeGetter = candidate;
                break;
            }
        }
        if (sizeGetter == null) return;
        long packed = ((Number) sizeGetter.invoke(drawContext)).longValue();
        float width = Float.intBitsToFloat((int) (packed >>> 32));
        float height = Float.intBitsToFloat((int) packed);
        if (!Float.isFinite(width) || !Float.isFinite(height)
                || width <= 0f || height <= 0f) return;

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0f, 0f, 0f, height,
                BB7Theme.gradientColors(), null, Shader.TileMode.CLAMP));
        // The BBOS7 selection is a flush rectangular row, not a floating
        // rounded chip. Paint its entire native Compose DrawScope.
        canvas.drawRect(0f, 0f, width, height, fill);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(BB7Theme.BLUE_BORDER);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1f);
        canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, border);
        if (DRAW_SEEN.compareAndSet(false, true))
            Log.i(TAG, "BB7 gradient drawn behind native-focused Compose item");
    }
}
