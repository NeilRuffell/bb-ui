package dev.q25.trackpad;

import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Experimental SystemUI-only LSPosed legacy API module. */
public final class TrackpadHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!TARGET.equals(param.packageName) || !TARGET.equals(param.processName)) return;
        hookTileClass(param.classLoader, "com.android.systemui.qs.tileimpl.QSTileViewImpl");
        hookTileClass(param.classLoader, "com.android.systemui.qs.tileimpl.QSTileView");
    }

    private void hookTileClass(ClassLoader loader, String className) {
        try {
            Class<?> cls = XposedHelpers.findClassIfExists(className, loader);
            if (cls == null) return;
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View tile = (View) param.thisObject;
                    tile.setFocusable(true);
                    tile.setDefaultFocusHighlightEnabled(true);
                }
            });
            XposedBridge.log("BB UI: hooked " + className);
        } catch (Throwable t) {
            XposedBridge.log("BB UI: skipped " + className + ": " + t);
        }
    }
}
