package dev.q25.bb7pulldown;

import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collection;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Call existing SystemUI QS tile backends, not duplicate platform radio toggles.
 * Deliberately no tile creation, editing or persistence: only currently owned tiles.
 */
final class NativeQuickSettingsTiles {
    private static final String TAG = "BB6-Connections";
    private static volatile WeakReference<Object> host = new WeakReference<>(null);
    private static boolean installed;

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> type = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.QSTileHost", loader);
        if (type == null) {
            Log.w(TAG, "Native QS tile host unavailable; Android settings fallback retained");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    host = new WeakReference<>(param.thisObject);
                    Log.i(TAG, "Native QS tile host captured");
                }
            });
            installed = true;
        } catch (Throwable error) {
            Log.w(TAG, "Cannot observe native QS tile host", error);
        }
    }

    static boolean activate(int kind) {
        Object captured = host.get();
        if (captured == null) return false;
        try {
            Object tiles = XposedHelpers.callMethod(captured, "getTiles");
            if (!(tiles instanceof Collection)) return false;
            String expected = ConnectionControlPolicy.tileSpec(kind);
            for (Object tile : (Collection<?>) tiles) {
                if (tile == null) continue;
                Object spec = XposedHelpers.callMethod(tile, "getTileSpec");
                if (!expected.equals(spec)) continue;
                Object available = XposedHelpers.callMethod(tile, "isAvailable");
                if (Boolean.FALSE.equals(available)) return false;
                // The SystemUI QSTile API explicitly accepts a null Expandable.
                XposedHelpers.callMethod(tile, "click", (Object) null);
                Log.i(TAG, "Native QS tile activated spec=" + expected);
                return true;
            }
        } catch (Throwable error) {
            Log.w(TAG, "Native QS tile activation failed; using Android panel fallback", error);
        }
        return false;
    }

    private NativeQuickSettingsTiles() {}
}
