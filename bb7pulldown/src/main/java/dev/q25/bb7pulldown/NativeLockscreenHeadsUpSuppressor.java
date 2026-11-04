package dev.q25.bb7pulldown;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Replace only stock heads-up presentation on the interactive BB6 keyguard,
 * via SystemUI's native VisualInterruptionDecisionProvider extension point.
 *
 * The stock notification listener, collection, ranking, shade, bubble and
 * full-screen-intent decision logic remain owned by Android. The BB6 strip
 * reads the same native notification collection as the unlocked shade.
 *
 * The native shared_notification_container is already handled separately by
 * BB6LockScreenHook. A heads-up interruption is NOT that notification stack;
 * without this suppression native heads-up banners can appear on top of BB6.
 */
final class NativeLockscreenHeadsUpSuppressor {
    private static final String TAG = "BB6-LockScreen";
    private static final String PROVIDER =
            "com.android.systemui.statusbar.notification.interruption."
                    + "VisualInterruptionDecisionProviderImpl";
    private static final String SUPPRESSOR =
            "com.android.systemui.statusbar.notification.interruption."
                    + "NotificationInterruptSuppressor";
    private static final String SUPPRESSOR_NAME = "BB6InteractiveLockscreen";

    // Weak provider references; the callback never retains its provider.
    private static final Map<Object, Object> registered =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean installed;
    private static boolean suppressionLogged;

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> providerClass = XposedHelpers.findClassIfExists(PROVIDER, loader);
        Class<?> suppressorClass = XposedHelpers.findClassIfExists(SUPPRESSOR, loader);
        if (providerClass == null || suppressorClass == null
                || !suppressorClass.isInterface()) {
            Log.w(TAG, "Native heads-up interruption extension unavailable in this ROM");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(providerClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    register(param.thisObject, suppressorClass);
                }
            });
            // Some SystemUI variants initialize a provider after its
            // constructor. Ensure registration precedes native start().
            XposedBridge.hookAllMethods(providerClass, "start", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    register(param.thisObject, suppressorClass);
                }
            });
            installed = true;
            Log.i(TAG, "Native heads-up presentation policy hooks installed");
        } catch (Throwable error) {
            Log.e(TAG, "Cannot observe native interruption provider", error);
        }
    }

    private static void register(Object provider, Class<?> suppressorClass) {
        if (provider == null) return;
        synchronized (registered) {
            if (registered.containsKey(provider)) return;
            try {
                InvocationHandler handler = (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == (args == null ? null : args[0]);
                        case "toString":
                            return SUPPRESSOR_NAME;
                        case "getName":
                            return SUPPRESSOR_NAME;
                        case "suppressAwakeHeadsUp":
                        case "suppressAwakeInterruptions":
                        case "suppressInterruptions":
                            boolean suppress = BB6NativeChromePolicy.suppressStockInterruption(
                                    method.getName(),
                                    BB6LockScreenHook.isInteractiveBb6LockscreenActive());
                            if (suppress && !suppressionLogged) {
                                suppressionLogged = true;
                                Log.i(TAG, "Native heads-up suppressed during BB6 keyguard");
                            } else if (!suppress) {
                                suppressionLogged = false;
                            }
                            return suppress;
                        default:
                            throw new UnsupportedOperationException(
                                    "Unknown native interruption callback: "
                                            + method.getName());
                    }
                };
                Object callback = Proxy.newProxyInstance(
                        suppressorClass.getClassLoader(),
                        new Class<?>[]{suppressorClass}, handler);
                Method registration = null;
                for (Method method : provider.getClass().getMethods()) {
                    if ("addLegacySuppressor".equals(method.getName())
                            && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == suppressorClass) {
                        registration = method;
                        break;
                    }
                }
                if (registration == null) {
                    Log.w(TAG, "Native addLegacySuppressor method unavailable");
                    return;
                }
                registration.invoke(provider, callback);
                registered.put(provider, callback);
                Log.i(TAG, "Native heads-up presentation suppressor registered");
            } catch (Throwable error) {
                Log.e(TAG, "Unable to register native heads-up presentation policy", error);
            }
        }
    }

    private NativeLockscreenHeadsUpSuppressor() {}
}
