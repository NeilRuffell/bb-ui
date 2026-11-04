package dev.q25.bb7pulldown;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Vector adapter to the stock LineageOS 24 KeyguardBlueprint machinery.
 *
 * A KeyguardBlueprint is an interface with getId() and getSections() in the
 * Q25 compiled SystemUI APK. The framework's own DefaultImpls performs
 * replaceViews(), including the normal removeViews/addViews/bindData lifecycle.
 *
 * The alternate blueprint omits redundant stock sections at composition time.
 * It does NOT remove live clock/Smartspace View children, set their alpha, or
 * install a replacement authentication screen. The native default blueprint
 * remains available for AOD, shade, and bouncer.
 *
 * This is the development Vector equivalent of the native ROM blueprint
 * in rom/lineage-24.0. Do not extend this adapter into a general View hook.
 */
public final class BB6BlueprintHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB6-Blueprint";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String NAME = "bb6-interactive";
    private static final String DEFAULT = "default";
    private static final String REPO =
            "com.android.systemui.keyguard.data.repository.KeyguardBlueprintRepository";
    private static final String BLUEPRINT =
            "com.android.systemui.keyguard.shared.model.KeyguardBlueprint";
    private static final String CLOCK =
            "com.android.systemui.keyguard.ui.view.layout.sections.ClockSection";
    private static final String SMARTSPACE =
            "com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection";
    private static final String AOD_LAYER =
            "com.android.systemui.keyguard.ui.view.layout.sections.AodBurnInLayer";

    private static WeakReference<Object> repoRef = new WeakReference<>(null);
    private static WeakReference<View> rootRef = new WeakReference<>(null);
    private static volatile boolean registered;
    private static volatile boolean composingBb6;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEMUI.equals(lp.packageName) || !SYSTEMUI.equals(lp.processName)) return;

        Class<?> repository = XposedHelpers.findClassIfExists(REPO, lp.classLoader);
        Class<?> blueprint = XposedHelpers.findClassIfExists(BLUEPRINT, lp.classLoader);
        if (repository == null || blueprint == null || !blueprint.isInterface()) {
            Log.e(TAG, "Native Q25 keyguard blueprint contract unavailable");
            return;
        }
        try {
            XposedBridge.hookAllConstructors(repository, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    registerBlueprint(param.thisObject, blueprint);
                }
            });
            XposedBridge.hookAllMethods(repository, "applyBlueprint",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registered || param.args.length == 0
                            || !(param.args[0] instanceof String)) return;
                    String request = (String) param.args[0];
                    if (DEFAULT.equals(request) || NAME.equals(request)) {
                        param.args[0] = eligible() ? NAME : DEFAULT;
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    composingBb6 = NAME.equals(appliedId(param.thisObject));
                    BB6LockScreenHook.onBlueprintChanged();
                }
            });
            Log.i(TAG, "Native KeyguardBlueprintRepository adapter registered");
        } catch (Throwable error) {
            Log.e(TAG, "Could not register native blueprint adapter: " + error);
            return;
        }

        hookAodReferenceConsistency(lp.classLoader);
        hookSmartspaceAodCleanup(lp.classLoader);
    }

    private static void registerBlueprint(Object repository, Class<?> blueprintClass) {
        try {
            Object mapValue = XposedHelpers.getObjectField(repository, "blueprintIdMap");
            if (!(mapValue instanceof TreeMap)) {
                Log.e(TAG, "Unexpected native blueprint repository map");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) mapValue;
            Object original = map.get(DEFAULT);
            if (original == null || !blueprintClass.isInstance(original)) {
                Log.e(TAG, "Native default blueprint missing; BB6 not installed");
                return;
            }

            Object source = XposedHelpers.callMethod(original, "getSections");
            if (!(source instanceof List)) return;
            List<?> sections = (List<?>) source;
            ArrayList<Object> chosen = new ArrayList<>();
            boolean clockFound = false;
            boolean smartspaceFound = false;
            for (Object section : sections) {
                if (section == null) continue;
                String name = section.getClass().getSimpleName();
                if ("ClockSection".equals(name)) clockFound = true;
                if ("SmartspaceSection".equals(name)) smartspaceFound = true;
                if (!isDuplicate(name)) chosen.add(section);
            }
            // Fail closed on a ROM whose blueprint structure is different.
            if (!clockFound || !smartspaceFound || chosen.size() == sections.size()) {
                Log.e(TAG, "Unknown native blueprint sections; refusing BB6 replacement");
                return;
            }
            final List<Object> bb6Sections =
                    Collections.unmodifiableList(new ArrayList<>(chosen));

            Object alternative = Proxy.newProxyInstance(
                    blueprintClass.getClassLoader(), new Class<?>[]{blueprintClass},
                    new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    switch (method.getName()) {
                        case "getId":
                            return NAME;
                        case "getSections":
                            return bb6Sections;
                        case "toString":
                            return "BB6VectorKeyguardBlueprint";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && args.length == 1 && proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "Unverified Q25 blueprint method: " + method.getName());
                    }
                }
            });
            map.put(NAME, alternative);
            repoRef = new WeakReference<>(repository);
            registered = true;
            Log.i(TAG, "BB6 native blueprint inserted: original sections="
                    + sections.size() + ", BB6 sections=" + chosen.size());
            scheduleSync();
        } catch (Throwable error) {
            registered = false;
            Log.e(TAG, "BB6 blueprint creation failed; native default retained", error);
        }
    }

    private static boolean isDuplicate(String name) {
        return BB6BlueprintPolicy.omitStockSection(name);
    }

    /** Called by the existing SystemUI-root adapter after native attachment. */
    static void onRootAttached(View root) {
        rootRef = new WeakReference<>(root);
        scheduleSync();
    }

    static void scheduleSync() {
        View root = rootRef.get();
        if (root == null) return;
        root.post(BB6BlueprintHook::sync);
    }

    static boolean isApplied() {
        return registered && composingBb6;
    }

    private static void sync() {
        Object repository = repoRef.get();
        View root = rootRef.get();
        if (!registered || repository == null || root == null
                || !root.isAttachedToWindow()) return;
        String target = eligible() ? NAME : DEFAULT;
        String current = appliedId(repository);
        if (target.equals(current)) {
            composingBb6 = NAME.equals(current);
            BB6LockScreenHook.onBlueprintChanged();
            return;
        }
        try {
            Object result = XposedHelpers.callMethod(repository, "applyBlueprint", target);
            composingBb6 = NAME.equals(appliedId(repository));
            Log.i(TAG, "Native blueprint transition " + current + " -> "
                    + appliedId(repository) + " result=" + result);
            BB6LockScreenHook.onBlueprintChanged();
        } catch (Throwable error) {
            Log.e(TAG, "Native blueprint transition rejected: " + error, error);
            composingBb6 = false;
        }
    }

    private static String appliedId(Object repository) {
        try {
            Object stateFlow = XposedHelpers.getObjectField(repository, "blueprint");
            Object selected = XposedHelpers.callMethod(stateFlow, "getValue");
            return selected == null ? null
                    : (String) XposedHelpers.callMethod(selected, "getId");
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean eligible() {
        View root = rootRef.get();
        if (root == null || !root.isAttachedToWindow() || !enabled()) return false;
        Context context = root.getContext();
        try {
            KeyguardManager keyguard = (KeyguardManager)
                    context.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager power = (PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            if (keyguard == null || power == null) return false;
            return BB6BlueprintPolicy.enabledForState(
                    keyguard.isKeyguardLocked(), power.isInteractive(),
                    BB6LockScreenHook.isNativeDozing(),
                    BB6LockScreenHook.isBouncerActive(),
                    BB6LockScreenHook.statusStateForBlueprint());
        } catch (RuntimeException error) {
            Log.w(TAG, "Native state unavailable; preserving default blueprint");
            return false;
        }
    }

    private static boolean enabled() {
        try {
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", null);
            return !"false".equals(XposedHelpers.callStaticMethod(
                    props, "get", "debug.bb6lockscreen.enabled", "true"))
                    && !"false".equals(XposedHelpers.callStaticMethod(
                    props, "get", "debug.bb6lockscreen.blueprint", "true"));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Stock SmartspaceSection.removeViews removes its date/Smartspace roots
     * without unregistering them from AodBurnInLayer. Remove their native
     * helper references BEFORE the stock section's own lifecycle method
     * runs. This is part of the blueprint transition, not an overlay hack.
     */
    private static void hookSmartspaceAodCleanup(ClassLoader loader) {
        Class<?> section = XposedHelpers.findClassIfExists(SMARTSPACE, loader);
        if (section == null) return;
        try {
            XposedBridge.hookAllMethods(section, "removeViews", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!registered || !eligible() || param.args.length == 0
                            || !(param.args[0] instanceof ViewGroup)) return;
                    ViewGroup root = (ViewGroup) param.args[0];
                    int id = root.getResources().getIdentifier(
                            "burn_in_layer", "id", SYSTEMUI);
                    View helper = id == 0 ? null : root.findViewById(id);
                    if (helper == null) return;
                    try {
                        List<Integer> removed = new ArrayList<>();
                        for (String field : Arrays.asList(
                                "smartspaceView", "dateView", "dateViewLargeClock")) {
                            Object item = XposedHelpers.getObjectField(param.thisObject, field);
                            if (item instanceof View && ((View) item).getId() != View.NO_ID) {
                                removed.add(((View) item).getId());
                            }
                        }
                        if (removed.isEmpty()) return;
                        int[] refs = (int[]) XposedHelpers.callMethod(
                                helper, "getReferencedIds");
                        if (refs == null || refs.length == 0) return;
                        int[] retained = new int[refs.length];
                        int count = 0;
                        for (int ref : refs) {
                            if (!removed.contains(ref)) retained[count++] = ref;
                        }
                        if (count != refs.length && count > 0) {
                            XposedHelpers.callMethod(helper, "setReferencedIds",
                                    (Object) Arrays.copyOf(retained, count));
                            Log.i(TAG, "Unregistered removed Smartspace from AOD Layer");
                        }
                    } catch (Throwable error) {
                        Log.w(TAG, "Native Smartspace cleanup unavailable: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "Cannot observe native Smartspace removal: " + error);
        }
    }

    /**
     * LineageOS 24's AodBurnInLayer is an AndroidX Layer/ConstraintHelper.
     * It transforms the IDs registered in getReferencedIds(). The previous
     * prototype's crash was dereferencing a removed child ID. During native
     * blueprint transitions we keep this reference list consistent.
     *
     * The native burn-in anchor is retained; do not detach views or override
     * any native transform method. This is an ID membership repair ONLY
     * when a reference is provably absent in the original ConstraintLayout.
     */
    private static void hookAodReferenceConsistency(ClassLoader loader) {
        Class<?> layer = XposedHelpers.findClassIfExists(AOD_LAYER, loader);
        if (layer == null) return;
        XC_MethodHook safeguard = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!registered) return;
                validateLayerReferences(param.thisObject);
            }
        };
        for (String name : Arrays.asList("transform", "updatePostLayout")) {
            try {
                XposedBridge.hookAllMethods(layer, name, safeguard);
            } catch (Throwable error) {
                Log.w(TAG, "AOD reference safeguard unavailable: " + name);
            }
        }
    }

    private static void validateLayerReferences(Object layer) {
        if (!(layer instanceof View)) return;
        View helper = (View) layer;
        ViewParent parent = helper.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        try {
            int[] ids = (int[]) XposedHelpers.callMethod(layer, "getReferencedIds");
            if (ids == null || ids.length == 0) return;
            int[] valid = new int[ids.length];
            int count = 0;
            for (int id : ids) {
                boolean found = false;
                for (int childIndex = 0; childIndex < group.getChildCount(); childIndex++) {
                    if (group.getChildAt(childIndex).getId() == id) {
                        found = true;
                        break;
                    }
                }
                if (found) valid[count++] = id;
            }
            if (count == ids.length || count == 0) return;
            int[] remaining = Arrays.copyOf(valid, count);
            XposedHelpers.callMethod(layer, "setReferencedIds", (Object) remaining);
            Log.w(TAG, "Repaired stale AOD Layer references: " + (ids.length - count));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot validate native AOD Layer references: " + error);
        }
    }
}
