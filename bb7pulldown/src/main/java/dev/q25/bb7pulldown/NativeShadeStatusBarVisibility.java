package dev.q25.bb7pulldown;

import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Keep the genuine SystemUI status bar available while BB UI owns the native
 * notification shade. No cloned clock, battery, or notification icon widgets.
 *
 * Q25 classes2.dex declares
 * StatusBarVisibilityInteractor.isShadeVisibleOnAnyDisplay: ReadonlyStateFlow.
 * In LineageOS legacy shade mode, this state drives hiding the normal status
 * bar above the 40% native expansion threshold. We retain native FULL
 * expansion, but override only that visibility policy while BB UI is mounted.
 *
 * The backing StateFlowImpl is identified by INSTANCE, not by type alone.
 * Other flows are untouched. On BB shade close, the latest original value
 * is restored; stock status bar/keyguard behavior resumes.
 */
final class NativeShadeStatusBarVisibility {
    private static final String TAG = "BB7-StatusBar";
    private static final String INTERACTOR =
            "com.android.systemui.statusbar.pipeline.shared.domain.interactor"
                    + ".StatusBarVisibilityInteractor";
    private static final String FLOW_IMPL = "kotlinx.coroutines.flow.StateFlowImpl";

    private static volatile WeakReference<Object> targetState = new WeakReference<>(null);
    private static volatile boolean bbMounted;
    private static volatile Boolean lastNativeVisible;
    private static volatile boolean loggedOverride;
    private static boolean installed;

    private NativeShadeStatusBarVisibility() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> interactor = XposedHelpers.findClassIfExists(INTERACTOR, loader);
        Class<?> stateClass = XposedHelpers.findClassIfExists(FLOW_IMPL, loader);
        if (interactor == null || stateClass == null) {
            Log.w(TAG, "Q25 native status-bar visibility classes unavailable");
            return;
        }
        try {
            interactor.getDeclaredField("isShadeVisibleOnAnyDisplay");
            XposedBridge.hookAllMethods(stateClass, "setValue", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Boolean)
                            || param.thisObject != targetState.get()) return;
                    boolean nativeVisible = (Boolean) param.args[0];
                    lastNativeVisible = nativeVisible;
                    if (bbMounted) {
                        param.args[0] = NativeStatusBarVisibilityPolicy.isStockShadeVisible(
                                nativeVisible, true);
                        if (nativeVisible && !loggedOverride) {
                            loggedOverride = true;
                            Log.i(TAG, "Native status-bar visibility retained during BB shade");
                        }
                    }
                }
            });
            XposedBridge.hookAllConstructors(interactor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    capture(param.thisObject, stateClass);
                }
            });
            installed = true;
            Log.i(TAG, "Native Q25 status-bar visibility policy hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to hook native status-bar visibility policy", error);
        }
    }

    private static void capture(Object interactor, Class<?> stateClass) {
        try {
            Object readOnly = XposedHelpers.getObjectField(
                    interactor, "isShadeVisibleOnAnyDisplay");
            Object state = unwrap(readOnly, stateClass);
            if (state == null) {
                Log.w(TAG, "Q25 status-bar visibility StateFlow backing not identified");
                return;
            }
            targetState = new WeakReference<>(state);
            Object current = XposedHelpers.callMethod(state, "getValue");
            if (current instanceof Boolean) lastNativeVisible = (Boolean) current;
            Log.i(TAG, "Captured actual native status-bar visibility StateFlow");
            if (bbMounted) XposedHelpers.callMethod(state, "setValue", Boolean.FALSE);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to capture native visibility state", error);
        }
    }

    private static Object unwrap(Object candidate, Class<?> stateClass) {
        Object current = candidate;
        for (int depth = 0; current != null && depth < 3; depth++) {
            if (stateClass.isInstance(current)) return current;
            Object next = null;
            // Kotlin ReadonlyStateFlow delegates to StateFlowImpl via a
            // private $$delegate_0 field. Reflect that only on this one
            // verified Q25 interactor field; never modify unrelated flows.
            for (Class<?> cls = current.getClass(); cls != null
                    && next == null; cls = cls.getSuperclass()) {
                for (Field field : cls.getDeclaredFields()) {
                    if (!field.getName().contains("delegate")) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(current);
                        if (value != null && value != current) {
                            next = value;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            current = next;
        }
        return null;
    }

    static void onBbShadeMountChanged(boolean mounted) {
        bbMounted = mounted;
        Object state = targetState.get();
        if (state == null) {
            Log.w(TAG, "Native status-bar visibility state not captured");
            return;
        }
        try {
            if (mounted) {
                // Preserve the original pipeline's latest decision, then
                // enable the native status bar above BB UI.
                Object value = XposedHelpers.callMethod(state, "getValue");
                if (value instanceof Boolean) lastNativeVisible = (Boolean) value;
                XposedHelpers.callMethod(state, "setValue", Boolean.FALSE);
            } else {
                // Do not leave SystemUI's statusbar visibility altered after
                // closing BB UI, screen-off, or a failed native attach.
                Boolean original = lastNativeVisible;
                if (original != null) {
                    XposedHelpers.callMethod(state, "setValue", original);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Native status-bar visibility transition failed", error);
        }
    }
}
