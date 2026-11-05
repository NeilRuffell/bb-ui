package dev.q25.bb7pulldown;

import android.util.Log;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Preserve the real collapsed status-bar contents while BB UI is displayed
 * in SystemUI's native shade window. No duplicated icon or clock views.
 *
 * Verified Q25 SystemUI APK declarations:
 * - StatusBarVisibilityInteractor.isShadeVisibleOnAnyDisplay : ReadonlyStateFlow
 * - StatusBarVisibilityInteractor.thisDisplayId : int
 * - ReadonlyStateFlow.$$delegate_0 : StateFlow
 * - StateFlowImpl.setValue(Object) : void
 * - HomeStatusBarViewModelImpl constructor has the interactor as a parameter.
 *
 * In legacy shade mode, isShadeVisibleOnAnyDisplay normally becomes true
 * when shade expansion exceeds 40%, hiding the home status bar. While the
 * BB presentation is mounted, retain false for THIS flow only. All other
 * native state, including lockscreen visibility, remains SystemUI-owned.
 */
final class NativeShadeStatusBarVisibility {
    private static final String TAG = "BB7-StatusBar";
    private static final String INTERACTOR =
            "com.android.systemui.statusbar.pipeline.shared.domain.interactor"
                    + ".StatusBarVisibilityInteractor";
    private static final String VIEW_MODEL =
            "com.android.systemui.statusbar.pipeline.shared.ui.viewmodel"
                    + ".HomeStatusBarViewModelImpl";
    private static final String READONLY = "kotlinx.coroutines.flow.ReadonlyStateFlow";
    private static final String STATE_IMPL = "kotlinx.coroutines.flow.StateFlowImpl";
    private static final String DELEGATE = "$$delegate_0";

    private static volatile WeakReference<Object> nativeVisibility =
            new WeakReference<>(null);
    private static volatile Boolean lastNativeValue;
    private static volatile boolean bbMounted;
    private static boolean installed;
    private static boolean overrideLogged;

    // Do not mistake our own temporary state writes for native emissions.
    private static final ThreadLocal<Boolean> ownWrite = new ThreadLocal<>();

    private NativeShadeStatusBarVisibility() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;

        Class<?> interactor = XposedHelpers.findClassIfExists(INTERACTOR, loader);
        Class<?> viewModel = XposedHelpers.findClassIfExists(VIEW_MODEL, loader);
        Class<?> wrapper = XposedHelpers.findClassIfExists(READONLY, loader);
        Class<?> state = XposedHelpers.findClassIfExists(STATE_IMPL, loader);
        if (interactor == null || wrapper == null || state == null) {
            Log.e(TAG, "Native Q25 visibility classes missing (interactor="
                    + (interactor != null) + " wrapper=" + (wrapper != null)
                    + " state=" + (state != null) + ")");
            return;
        }

        try {
            interactor.getDeclaredField("isShadeVisibleOnAnyDisplay");
            interactor.getDeclaredField("thisDisplayId");
            wrapper.getDeclaredField(DELEGATE);
            state.getDeclaredMethod("setValue", Object.class);
        } catch (NoSuchFieldException | NoSuchMethodException error) {
            Log.e(TAG, "Native Q25 visibility signatures changed; no override installed", error);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(state, "setValue", Object.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != nativeVisibility.get()
                                    || param.args.length == 0
                                    || !(param.args[0] instanceof Boolean)
                                    || Boolean.TRUE.equals(ownWrite.get())) return;

                            boolean nativeVisible = (Boolean) param.args[0];
                            lastNativeValue = nativeVisible;
                            if (bbMounted && nativeVisible) {
                                param.args[0] = NativeStatusBarVisibilityPolicy
                                        .isStockShadeVisible(nativeVisible, true);
                                if (!overrideLogged) {
                                    overrideLogged = true;
                                    Log.i(TAG, "Suppressed native shade-only status bar hide;"
                                            + " original clock and icons remain eligible");
                                }
                            }
                        }
                    });

            // Capture the finished interactor, including the field assigned
            // by its Kotlin constructor. Q25's primary display ID is zero.
            XposedBridge.hookAllConstructors(interactor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) {
                        capture(param.thisObject, interactor, state, "interactor constructed");
                    }
                }
            });

            // Alternative acquisition when Dagger instantiates a ViewModel
            // with an interactor that was created before this hook registered.
            if (viewModel != null) {
                XposedBridge.hookAllConstructors(viewModel, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (nativeVisibility.get() != null) return;
                        for (Object argument : param.args) {
                            if (interactor.isInstance(argument)) {
                                capture(argument, interactor, state, "status bar ViewModel");
                                break;
                            }
                        }
                    }
                });
            }

            installed = true;
            Log.i(TAG, "Q25 native status-bar content visibility hook registered;"
                    + " exact ReadonlyStateFlow delegate=" + DELEGATE
                    + " ViewModel=" + (viewModel != null));
        } catch (Throwable error) {
            Log.e(TAG, "Native status-bar visibility hook installation failed", error);
        }
    }

    private static void capture(Object candidate, Class<?> expectedInteractor,
            Class<?> expectedState, String source) {
        if (candidate == null || !expectedInteractor.isInstance(candidate)) return;
        try {
            if (XposedHelpers.getIntField(candidate, "thisDisplayId") != 0) return;
            Object readonly = XposedHelpers.getObjectField(
                    candidate, "isShadeVisibleOnAnyDisplay");
            if (readonly == null) {
                Log.w(TAG, "Native status-bar flow not yet initialized via " + source);
                return;
            }

            Object delegate = XposedHelpers.getObjectField(readonly, DELEGATE);
            if (!expectedState.isInstance(delegate)) {
                // Record the actual runtime type, do not guess a different
                // field name or modify an arbitrary unrelated StateFlow.
                Log.w(TAG, "Native visibility delegate type unexpected via " + source
                        + ": " + (delegate == null ? "null"
                                : delegate.getClass().getName()));
                return;
            }

            nativeVisibility = new WeakReference<>(delegate);
            Object current = XposedHelpers.callMethod(delegate, "getValue");
            if (current instanceof Boolean) lastNativeValue = (Boolean) current;
            Log.i(TAG, "Captured native status-bar visibility StateFlow via " + source
                    + "; currentShadeVisible=" + current);
            if (bbMounted) writeValue(delegate, Boolean.FALSE);
        } catch (Throwable error) {
            Log.e(TAG, "Cannot capture Q25 visibility state via " + source, error);
        }
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
        Object state = nativeVisibility.get();
        if (state == null) {
            Log.w(TAG, "Native status-bar visibility flow NOT CAPTURED; "
                    + "check constructor capture and Q25 field diagnostics");
            return;
        }

        try {
            if (mounted) {
                Object current = XposedHelpers.callMethod(state, "getValue");
                if (current instanceof Boolean) lastNativeValue = (Boolean) current;
                writeValue(state, Boolean.FALSE);
                Log.i(TAG, "Preserving existing status-bar content during BB native shade");
            } else {
                Boolean original = lastNativeValue;
                if (original != null) writeValue(state, original);
                Log.i(TAG, "Restored normal native status-bar visibility policy");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Cannot update native visibility for BB shade transition", error);
        }
    }
}
