package dev.q25.bb7shade;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Read-only stage-1 integration with the LineageOS 24 SystemUI shade.
 *
 * Registers a notification-list observer after the platform pipeline has
 * filtered and grouped entries. This stage does not create any visible UI,
 * suppress the stock shade, mutate notifications or intercept user input.
 *
 * The future two-page UI must take over atomically so the user never sees
 * competing shade surfaces.
 */
public final class ShadeIntegrationHook implements IXposedHookLoadPackage {
    private static final String PACKAGE = "com.android.systemui";
    private static final String TAG = "BB7-Shade";
    private static final String PIPELINE =
            "com.android.systemui.statusbar.notification.collection.NotifPipeline";
    private static final String RENDER_LISTENER =
            "com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener";
    private static final String WINDOW =
            "com.android.systemui.shade.NotificationShadeWindowView";

    // Listener proxies remain reachable for as long as their pipelines are.
    private static final Map<Object, Object> PIPELINE_LISTENERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PACKAGE.equals(lp.packageName) || !PACKAGE.equals(lp.processName)) return;

        Log.i(TAG, "BB7 Shade stage 1 loaded; stock shade retained");
        attachNotificationPipeline(lp);
        observeShadeWindow(lp);
    }

    private static void attachNotificationPipeline(XC_LoadPackage.LoadPackageParam lp) {
        final Class<?> pipeline = XposedHelpers.findClassIfExists(PIPELINE, lp.classLoader);
        final Class<?> listenerType = XposedHelpers.findClassIfExists(
                RENDER_LISTENER, lp.classLoader);
        if (pipeline == null || listenerType == null) {
            // Vector may invoke us with more than one classloader. A later
            // invocation can attach; do not alter the system to compensate.
            Log.w(TAG, "SystemUI notification pipeline not available in this classloader");
            return;
        }

        try {
            XposedBridge.hookAllConstructors(pipeline, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        attachListener(param.thisObject, listenerType);
                    } catch (Throwable error) {
                        Log.e(TAG, "Unable to attach notification index", error);
                    }
                }
            });
            Log.i(TAG, "Notification pipeline constructor hook registered");
        } catch (Throwable error) {
            Log.e(TAG, "Notification pipeline hook registration failed", error);
        }
    }

    private static void attachListener(Object pipeline, Class<?> listenerType) {
        synchronized (PIPELINE_LISTENERS) {
            if (PIPELINE_LISTENERS.containsKey(pipeline)) return;
            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    String methodName = method.getName();
                    if ("onBeforeRenderList".equals(methodName)) {
                        if (args != null && args.length == 1 && args[0] instanceof java.util.List) {
                            try {
                                ShadeNotificationIndex.update((java.util.List<?>) args[0]);
                            } catch (Throwable error) {
                                Log.e(TAG, "Cannot update notification index", error);
                            }
                        }
                        return null;
                    }
                    if ("toString".equals(methodName)) return "BB7ShadeNotificationObserver";
                    if ("hashCode".equals(methodName)) return System.identityHashCode(proxy);
                    if ("equals".equals(methodName)) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    return null;
                }
            };
            Object listener = Proxy.newProxyInstance(
                    listenerType.getClassLoader(), new Class<?>[]{listenerType}, handler);
            // This is an observer API: it never filters, removes, or reorders
            // any of the notifications seen by the platform renderer.
            XposedHelpers.callMethod(pipeline, "addOnBeforeRenderListListener", listener);
            PIPELINE_LISTENERS.put(pipeline, listener);
            Log.i(TAG, "Read-only rendered-notification index attached");
        }
    }

    private static void observeShadeWindow(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> shadeWindow = XposedHelpers.findClassIfExists(WINDOW, lp.classLoader);
        if (shadeWindow == null) {
            Log.w(TAG, "Notification shade window unavailable in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(shadeWindow, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Log.i(TAG, "Original notification shade window attached");
                }
            });
            Log.i(TAG, "Shade host observation registered (no UI replacement)");
        } catch (Throwable error) {
            Log.e(TAG, "Shade window observation registration failed", error);
        }
    }
}
