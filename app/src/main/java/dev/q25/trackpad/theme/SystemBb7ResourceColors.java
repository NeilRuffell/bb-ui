package dev.q25.trackpad.theme;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Shared native Android *resource colour* boundary.
 *
 * Android Settings / SettingsLib XML frequently references concrete
 * @color/materialColorSurface* resources instead of theme attributes.
 * Resources.Theme.resolveAttribute therefore cannot recolour these views.
 *
 * Hook Resources.getColor/getColorStateList in scoped OS processes, filtering
 * by package AND semantic Material/SettingsLib colour names. Never change
 * unknown resource IDs, error/warning/success semantics, or any third-party
 * package's drawable/text resources. Classification is cached per resource ID
 * to avoid repeatedly allocating strings during UI inflation.
 */
final class SystemBb7ResourceColors {
    private static final String TAG = "BB7-SystemSkin";
    private static final int NONE = 0;
    private static final int CANVAS = 1;
    private static final int CARD = 2;
    private static final int ACTION = 3;
    private static final int TITLE = 4;
    private static final int BODY = 5;
    private static final int MUTED = 6;
    private static final int DIVIDER = 7;
    private static final int BLUE = 8;
    private static final int ON_BLUE = 9;

    private static final ConcurrentHashMap<Integer, Integer> ROLES =
            new ConcurrentHashMap<>();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean APPLIED = new AtomicBoolean();
    private static final AtomicBoolean ERROR = new AtomicBoolean();

    private SystemBb7ResourceColors() {}

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(Resources.class, "getColor",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) return;
                    Resources res = (Resources) param.thisObject;
                    int id = (Integer) param.args[0];
                    int role = role(res, id);
                    if (role == NONE) return;
                    param.setResult(color(res, role));
                    if (APPLIED.compareAndSet(false, true))
                        Log.i(TAG, "Native OS Material resource palette applied");
                }
            });
            XposedBridge.hookAllMethods(Resources.class, "getColorStateList",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length < 1
                            || !(param.args[0] instanceof Integer)) return;
                    Resources res = (Resources) param.thisObject;
                    int role = role(res, (Integer) param.args[0]);
                    if (role == NONE) return;
                    param.setResult(ColorStateList.valueOf(color(res, role)));
                }
            });
            Log.i(TAG, "Shared native Material/SettingsLib resource-colour hooks installed");
        } catch (Throwable error) {
            INSTALLED.set(false);
            Log.e(TAG, "Resource colours unchanged", error);
        }
    }

    private static int role(Resources res, int id) {
        if (id == 0 || !SystemBb7VisualHook.enabled()) return NONE;
        Integer cached = ROLES.get(id);
        if (cached != null) return cached;
        int role = NONE;
        try {
            String pkg = res.getResourcePackageName(id);
            if ("android".equals(pkg) || pkg.startsWith("com.android.")
                    || pkg.startsWith("org.lineageos.")) {
                role = classify(res.getResourceEntryName(id));
            }
        } catch (Resources.NotFoundException ignored) {
            // Invalid IDs retain their original Android result.
        } catch (Throwable error) {
            if (ERROR.compareAndSet(false, true))
                Log.w(TAG, "Resource palette classification unavailable", error);
        }
        ROLES.putIfAbsent(id, role);
        return role;
    }

    /** Public within this package for deterministic palette unit tests. */
    static int classify(String entry) {
        if (entry == null) return NONE;
        String n = entry.toLowerCase(Locale.ROOT).replace("_", "");
        if ("settingslibtextcolorprimary".equals(n)) return TITLE;
        if ("settingslibtextcolorsecondary".equals(n)) return BODY;
        // Only designated shared theme tokens. No app-specific colours.
        if (!(n.startsWith("materialcolor")
                || n.startsWith("settingslibmaterialcolor")
                || n.startsWith("colorsurface")
                || n.startsWith("coloronsurface")
                || n.equals("colorbackground")
                || n.equals("colorprimary")
                || n.equals("coloraccent")
                || n.equals("coloroutline"))) return NONE;
        if (n.contains("error") || n.contains("warning")
                || n.contains("success") || n.contains("tertiary")) return NONE;
        if (n.contains("onsurfacevariant")) return BODY;
        if (n.contains("onbackground") || n.contains("onsurface"))
            return TITLE;
        if (n.contains("onprimary") || n.contains("onsecondary")) return ON_BLUE;
        if (n.contains("outline") || n.contains("divider")) return DIVIDER;
        if (n.contains("primary") || n.contains("accent")) return BLUE;
        if (n.contains("secondary")) return BODY;
        if (n.contains("background")) return CANVAS;
        if (n.contains("surfacecontainerhigh") || n.contains("surfacebright"))
            return ACTION;
        if (n.contains("surfacecontainerlowest") || n.contains("surfacedim"))
            return CANVAS;
        if (n.contains("surface")) return CARD;
        return NONE;
    }

    private static int color(Resources res, int role) {
        boolean dark = (res.getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        BB7Theme.Palette p = dark ? BB7Theme.Palette.DARK : BB7Theme.Palette.LIGHT;
        switch (role) {
            case CANVAS: return p.canvas;
            case CARD: return p.card;
            case ACTION: return p.action;
            case TITLE: return p.title;
            case BODY: return p.body;
            case MUTED: return p.muted;
            case DIVIDER: return p.divider;
            case BLUE: return BB7Theme.BLUE_MIDDLE;
            case ON_BLUE: return BB7Theme.TITLE;
            default: return p.canvas;
        }
    }
}
