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
    // Q25 DEX: HomeStatusBarViewModelImpl receives the visibility
    // interactor as a constructor dependency. Capturing that parameter
    // also covers instances created before our interactor-constructor hook.
    private static final String HOME_VIEW_MODEL =
            "com.android.systemui.statusbar.pipeline.shared.ui.viewmodel"
                    + ".HomeStatusBarViewModelImpl";

    private static volatile WeakReference<Object> targetState = new WeakReference<>(null);
    private static volatile boolean bbMounted;
    private static volatile Boolean lastNativeVisible;
    private static volatile boolean loggedOverride;
    // Distinguish a direct BB UI policy write from a real native Flow
    // emission, so rollback can restore the original native decision.
    private static final ThreadLocal<Boolean> ownWrite = new ThreadLocal<>();
    private static boolean installed;

    private NativeShadeStatusBarVisibility() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> interactor = XposedHelpers.findClassIfExists(INTERACTOR, loader);
        Class<?> stateClass = XposedHelpers.findClassIfExists(FLOW_IMPL, loader);
        Class<?> viewModel = XposedHelpers.findClassIfExists(HOME_VIEW_MODEL, loader);
        if (interactor == null || stateClass == null || viewModel == null) {
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
                    if (!Boolean.TRUE.equals(ownWrite.get())) {
                        lastNativeVisible = nativeVisible;
                    }
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
                    capture(param.thisObject, stateClass, "interactor constructor");
                }
            });
            // Q25 HomeStatusBarViewModelImpl's compiled constructor has
            // StatusBarVisibilityInteractor among its injected parameters.
            // It is not stored as a ViewModel field, so inspect its args
            // *before* invoking the constructor rather than guessing fields.
            XposedBridge.hookAllConstructors(viewModel, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (targetState.get() != null) return;
                    for (Object arg : param.args) {
                        if (interactor.isInstance(arg)) {
                            capture(arg, stateClass, "native status-bar ViewModel dependency");
                            break;
                        }
                    }
                }
            });
            installed = true;
            Log.i(TAG, "Native Q25 status-bar visibility policy hooks registered"
                    + " (interactor and ViewModel capture)");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to hook native status-bar visibility policy", error);
        }
    }

    private static void capture(Object interactor, Class<?> stateClass, String source) {
        try {
            // Do not alter visibility on a secondary display or on a
            // status-bar object that is still under construction.
            if (XposedHelpers.getIntField(interactor, "thisDisplayId") != 0) return;
            Object readOnly = XposedHelpers.getObjectField(
                    interactor, "isShadeVisibleOnAnyDisplay");
            if (readOnly == null) {
                Log.w(TAG, "Native visibility flow not initialized at " + source);
                return;
            }
            Object state = unwrap(readOnly, stateClass);
            if (state == null) {
                Log.w(TAG, "Cannot unwrap Q25 native visibility flow "
                        + readOnly.getClass().getName() + " from " + source);
                return;
            }
            targetState = new WeakReference<>(state);
            Object current = XposedHelpers.callMethod(state, "getValue");
            if (current instanceof Boolean) lastNativeVisible = (Boolean) current;
            Log.i(TAG, "Captured actual native status-bar visibility StateFlow via "
                    + source);
            if (bbMounted) writeValue(state, Boolean.FALSE);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to capture native visibility state", error);
        }
    }

    private static Object unwrap(Object candidate, Class<?> stateClass) {
        Object current = candidate;
        for (int depth = 0; current != null && depth < 3; depth++) {
            if (stateClass.isInstance(current)) return current;
            Object next = null;
            // Q25 classes3.dex declares ReadonlyStateFlow.$delegate_0
            // as StateFlow. Inspect this concrete field before fallback.
            try {
                next = XposedHelpers.getObjectField(current, "$delegate_0");
            } catch (Throwable ignored) { }
            // Fallback for an alternative Kotlin wrapper on ROM changes.
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

    private static void writeValue(Object state, Boolean value) {
        ownWrite.set(Boolean.TRUE);
        try {
            XposedHelpers.callMethod(state, "setValue", value);
        } finally {
            ownWrite.remove();
        }
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
                writeValue(state, Boolean.FALSE);
            } else {
                // Do not leave SystemUI's statusbar visibility altered after
                // closing BB UI, screen-off, or a failed native attach.
                Boolean original = lastNativeVisible;
                if (original != null) {
                    writeValue(state, original);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Native status-bar visibility transition failed", error);
        }
    }
}
