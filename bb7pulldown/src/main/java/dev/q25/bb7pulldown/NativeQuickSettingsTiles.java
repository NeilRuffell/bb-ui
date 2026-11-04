package dev.q25.bb7pulldown;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Thin Vector adapter to the native LineageOS 24 QSHostAdapter / QSTile API.
 *
 * Tile creation, persistence, user switching, third-party TileServices,
 * authentication, permissions, dialogs and actions remain owned by SystemUI.
 * This class never constructs a QSTile, changes QS_TILES or touches radio APIs.
 *
 * Sources:
 * LineageOS/android_frameworks_base lineage-24.0:
 *   packages/SystemUI/src/com/android/systemui/qs/QSHostAdapter.kt
 *   packages/SystemUI/src/com/android/systemui/qs/QSHost.java
 *   packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSTile.java
 */
final class NativeQuickSettingsTiles {
    private static final String TAG = "BB6-NativeQS";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Object> host = new WeakReference<>(null);
    private static volatile WeakReference<Object> interactor = new WeakReference<>(null);
    private static final Set<Session> sessions = new HashSet<>();
    private static boolean installed;
    private static boolean sourceFailureLogged;
    private static boolean hostAccessFailureLogged;

    enum Action { PRIMARY, SECONDARY, LONG_PRESS }

    static final class TileInfo {
        final String spec;
        final CharSequence label;
        final CharSequence secondary;
        final CharSequence contentDescription;
        final int state;
        final boolean available;
        final boolean disabledByPolicy;
        final boolean hasSecondaryAction;
        final boolean hasLongAction;
        final Object icon;

        TileInfo(String spec, CharSequence label, CharSequence secondary,
                CharSequence contentDescription, int state, boolean available,
                boolean disabledByPolicy, boolean hasSecondaryAction,
                boolean hasLongAction, Object icon) {
            this.spec = spec;
            this.label = label;
            this.secondary = secondary;
            this.contentDescription = contentDescription;
            this.state = state;
            this.available = available;
            this.disabledByPolicy = disabledByPolicy;
            this.hasSecondaryAction = hasSecondaryAction;
            this.hasLongAction = hasLongAction;
            this.icon = icon;
        }

        CharSequence statusText() {
            if (!TextUtils.isEmpty(secondary)) return secondary;
            if (state == Tile.STATE_UNAVAILABLE || !available) return "Unavailable";
            if (state == Tile.STATE_ACTIVE) return "On";
            return "Off";
        }
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        // Both types are present in inspected LineageOS 24 sources.
        // The Compose QS grid reads CurrentTilesInteractor directly; it must
        // not be assumed that a QSHostAdapter instance is always constructed.
        Class<?> adapter = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.QSHostAdapter", loader);
        Class<?> pipeline = XposedHelpers.findClassIfExists(
                "com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractorImpl",
                loader);
        if (adapter != null) {
            try {
                XposedBridge.hookAllConstructors(adapter, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object captured = param.thisObject;
                        host = new WeakReference<>(captured);
                        MAIN.post(() -> {
                            for (Session session : new ArrayList<>(sessions)) {
                                session.bindHost(captured);
                            }
                        });
                        Log.i(TAG, "QSHostAdapter captured");
                    }
                });
                Log.i(TAG, "QSHostAdapter constructor hook installed");
            } catch (Throwable error) {
                Log.e(TAG, "QSHostAdapter observer unavailable", error);
            }
        } else {
            Log.w(TAG, "QSHostAdapter class unavailable in this SystemUI build");
        }
        if (pipeline != null) {
            try {
                XposedBridge.hookAllConstructors(pipeline, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object captured = param.thisObject;
                        interactor = new WeakReference<>(captured);
                        MAIN.post(() -> {
                            for (Session session : new ArrayList<>(sessions)) {
                                session.refresh();
                            }
                        });
                        Log.i(TAG, "CurrentTilesInteractorImpl captured");
                    }
                });
                Log.i(TAG, "CurrentTilesInteractorImpl constructor hook installed");
            } catch (Throwable error) {
                Log.e(TAG, "Native tile interactor observer unavailable", error);
            }
        } else {
            Log.w(TAG, "CurrentTilesInteractorImpl class unavailable in this SystemUI build");
        }
        installed = true;
    }

    /**
     * Read the already-existing native tile collection. The Q25's actual
     * SystemUI binary contains QSHostAdapter and CurrentTilesInteractorImpl,
     * but DOES NOT expose their source-level getTiles/getCurrentTiles names
     * (confirmed by the Q25 logcat). Do not call those absent methods.
     *
     * Instead inspect only the verified pipeline's StateFlow fields, and
     * accept a collection ONLY when each entry contains a native QSTile.
     * No tile instance is created, stored, or persisted here.
     */
    private static Collection<?> currentTiles() {
        Object pipeline = interactor.get();
        if (pipeline != null) {
            Collection<?> tiles = interactorTiles(pipeline);
            if (tiles != null) return tiles;
        }
        Object adapter = host.get();
        if (adapter != null) {
            Collection<?> tiles = adapterTiles(adapter);
            if (tiles != null) return tiles;
        }
        if (!sourceFailureLogged) {
            sourceFailureLogged = true;
            Log.w(TAG, "No native tile source resolved; interactor=" + (pipeline != null)
                    + ", adapter=" + (adapter != null));
            if (pipeline != null) describePipeline(pipeline);
        }
        return Collections.emptyList();
    }

    private static Collection<?> adapterTiles(Object adapter) {
        // Only use a declared, actually-present method. Some ROM binaries
        // optimize away the source-level QSHostAdapter.getTiles() symbol.
        try {
            Method method = adapter.getClass().getMethod("getTiles");
            Object value = method.invoke(adapter);
            if (value instanceof Collection) return (Collection<?>) value;
        } catch (NoSuchMethodException error) {
            if (!hostAccessFailureLogged) {
                hostAccessFailureLogged = true;
                Log.i(TAG, "QSHostAdapter.getTiles absent in Q25 binary; using pipeline");
            }
        } catch (Throwable error) {
            if (!hostAccessFailureLogged) {
                hostAccessFailureLogged = true;
                Log.w(TAG, "Cannot read QSHostAdapter tiles; using pipeline", error);
            }
        }
        return null;
    }

    private static Collection<?> interactorTiles(Object pipeline) {
        ClassLoader loader = pipeline.getClass().getClassLoader();
        final Class<?> flowType = XposedHelpers.findClassIfExists(
                "kotlinx.coroutines.flow.StateFlow", loader);
        final Class<?> tileType = XposedHelpers.findClassIfExists(
                "com.android.systemui.plugins.qs.QSTile", loader);
        if (flowType == null || tileType == null) return null;
        for (Class<?> type = pipeline.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    // Java/Kotlin field names may be optimized or obfuscated.
                    // Check the live object type rather than a guessed name.
                    field.setAccessible(true);
                    Object flow = field.get(pipeline);
                    if (flow == null || !flowType.isInstance(flow)) continue;
                    Object models = stateFlowValue(flowType, flow);
                    if (!(models instanceof Collection)) continue;
                    Collection<?> collection = (Collection<?>) models;
                    if (collection.isEmpty()) continue;
                    List<Object> result = new ArrayList<>(collection.size());
                    for (Object model : collection) {
                        Object tile = nativeTileFromModel(model, tileType);
                        if (tile == null) {
                            result.clear();
                            break;
                        }
                        result.add(tile);
                    }
                    if (!result.isEmpty() && result.size() == collection.size()) {
                        return result;
                    }
                } catch (Throwable ignored) {
                    // Fields unrelated to the native QS tile collection are
                    // expected; never modify them or invoke their actions.
                }
            }
        }
        return null;
    }

    private static Object stateFlowValue(Class<?> flowType, Object flow) throws Exception {
        // On a stock build the source-level accessor is getValue(). In an
        // optimized ROM its method name can differ. The StateFlow interface
        // has one zero-argument, Object-returning value accessor, which we
        // resolve from the actual loaded interface rather than guessing.
        for (Method method : flowType.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0
                    || method.getReturnType() != Object.class) continue;
            method.setAccessible(true);
            return method.invoke(flow);
        }
        return null;
    }

    private static Object nativeTileFromModel(Object model, Class<?> tileType)
            throws IllegalAccessException {
        if (model == null) return null;
        if (tileType.isInstance(model)) return model;
        // LineageOS TileModel carries a QSTile, TileSpec and Expandable.
        // Read the QSTile field by its verified type, not the field name.
        for (Class<?> type = model.getClass();
                type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!tileType.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object tile = field.get(model);
                if (tileType.isInstance(tile)) return tile;
            }
        }
        return null;
    }

    /** One-time diagnostics without dumping tile contents or user data. */
    private static void describePipeline(Object pipeline) {
        Class<?> type = pipeline.getClass();
        StringBuilder fields = new StringBuilder();
        for (Field field : type.getDeclaredFields()) {
            if (fields.length() > 0) fields.append(", ");
            fields.append(field.getType().getSimpleName());
            if (fields.length() > 500) break;
        }
        Log.i(TAG, "Native interactor field types: " + fields);
    }

    static boolean activate(String spec, Action action) {
        if (TextUtils.isEmpty(spec)) return false;
        try {
            Object item = findTile(spec);
            if (item == null || !Boolean.TRUE.equals(
                    XposedHelpers.callMethod(item, "isAvailable"))) return false;
            if (action == Action.SECONDARY) {
                Object state = XposedHelpers.callMethod(item, "getState");
                if (!booleanField(state, "handlesSecondaryClick", false)) return false;
                XposedHelpers.callMethod(item, "secondaryClick", (Object) null);
            } else if (action == Action.LONG_PRESS) {
                Object state = XposedHelpers.callMethod(item, "getState");
                if (!booleanField(state, "handlesLongClick", true)) return false;
                XposedHelpers.callMethod(item, "longClick", (Object) null);
            } else {
                XposedHelpers.callMethod(item, "click", (Object) null);
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Native tile action failed for " + spec, error);
            return false;
        }
    }

    private static Object findTile(String spec) {
        for (Object tile : currentTiles()) {
            if (tile != null && spec.equals(
                    XposedHelpers.callMethod(tile, "getTileSpec"))) return tile;
        }
        return null;
    }

    static Drawable iconDrawable(Context context, Object icon) {
        if (icon == null) return null;
        try {
            if (icon instanceof Supplier) icon = ((Supplier<?>) icon).get();
            if (icon == null) return null;
            Drawable result = (Drawable) XposedHelpers.callMethod(icon, "getDrawable", context);
            if (result == null) return null;
            Drawable.ConstantState constant = result.getConstantState();
            return constant != null ? constant.newDrawable(context.getResources()).mutate()
                    : result.mutate();
        } catch (Throwable error) {
            Log.w(TAG, "Native tile icon could not be rendered", error);
            return null;
        }
    }

    private static boolean booleanField(Object state, String name, boolean fallback) {
        if (state == null) return fallback;
        try {
            return XposedHelpers.getBooleanField(state, name);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static CharSequence charField(Object state, String name) {
        if (state == null) return null;
        try {
            Object value = XposedHelpers.getObjectField(state, name);
            return value instanceof CharSequence ? (CharSequence) value : null;
        } catch (Throwable error) {
            return null;
        }
    }

    static final class Session {
        private final Context context;
        private final Runnable changed;
        private final Object listeningToken = new Object();
        private final Map<Object, Object> callbacks = new IdentityHashMap<>();
        private Object boundHost;
        private Object hostCallback;
        private boolean active;
        private int warmupAttempts;
        private int previousTileCount = -1;
        private final Runnable warmup = new Runnable() {
            @Override public void run() {
                if (!active) return;
                refresh();
                if (currentTiles().isEmpty() && ++warmupAttempts < 8) {
                    MAIN.postDelayed(this, 300);
                }
            }
        };

        Session(Context context, Runnable changed) {
            this.context = context;
            this.changed = changed;
        }

        void start() {
            if (active) return;
            active = true;
            sessions.add(this);
            Log.i(TAG, "Native QS session opened: host=" + (host.get() != null)
                    + " interactor=" + (interactor.get() != null));
            bindHost(host.get());
            // CurrentTilesInteractor starts its tile collection asynchronously
            // (scope.launch). Only retry initial loading, not ongoing tile
            // state; native tile callbacks own steady-state updates.
            MAIN.postDelayed(warmup, 300);
        }

        void stop() {
            if (!active) return;
            active = false;
            sessions.remove(this);
            MAIN.removeCallbacks(warmup);
            unbind();
        }

        private Object callback(ClassLoader loader, String type, Runnable action)
                throws ClassNotFoundException {
            Class<?> iface = Class.forName(type, false, loader);
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == (args == null ? null : args[0]);
                    case "toString": return "BB6 native QS callback";
                    default: MAIN.post(() -> { if (active) action.run(); }); return null;
                }
            };
            return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, handler);
        }

        private void bindHost(Object newHost) {
            if (!active || boundHost == newHost) return;
            unbind();
            if (newHost == null) {
                refresh();
                return;
            }
            try {
                Object cb = callback(newHost.getClass().getClassLoader(),
                        "com.android.systemui.qs.QSHost$Callback", this::refresh);
                XposedHelpers.callMethod(newHost, "addCallback", cb);
                boundHost = newHost;
                hostCallback = cb;
                refresh();
            } catch (Throwable error) {
                Log.w(TAG, "Unable to bind QSHost callback", error);
                unbind();
                changed.run();
            }
        }

        private void refresh() {
            if (!active) return;
            try {
                Collection<?> value = currentTiles();
                if (value.size() != previousTileCount) {
                    Log.i(TAG, "Native QS tile count=" + value.size()
                            + " host=" + (host.get() != null)
                            + " interactor=" + (interactor.get() != null));
                    previousTileCount = value.size();
                }
                Set<Object> present = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Object tile : value) {
                    if (tile == null) continue;
                    present.add(tile);
                    if (callbacks.containsKey(tile)) continue;
                    try {
                        Object cb = callback(tile.getClass().getClassLoader(),
                                "com.android.systemui.plugins.qs.QSTile$Callback", changed);
                        XposedHelpers.callMethod(tile, "addCallback", cb);
                        XposedHelpers.callMethod(tile, "setListening", listeningToken, true);
                        callbacks.put(tile, cb);
                        XposedHelpers.callMethod(tile, "refreshState");
                    } catch (Throwable error) {
                        Log.w(TAG, "Tile callback registration failed", error);
                    }
                }
                Iterator<Map.Entry<Object, Object>> it = callbacks.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Object, Object> entry = it.next();
                    if (!present.contains(entry.getKey())) {
                        stopListening(entry.getKey(), entry.getValue());
                        it.remove();
                    }
                }
                changed.run();
            } catch (Throwable error) {
                Log.w(TAG, "Native tile list refresh failed", error);
            }
        }

        List<TileInfo> snapshot() {
            if (!active) return Collections.emptyList();
            List<TileInfo> result = new ArrayList<>();
            try {
                for (Object tile : currentTiles()) {
                    if (tile == null) continue;
                    try {
                        boolean available = Boolean.TRUE.equals(
                                XposedHelpers.callMethod(tile, "isAvailable"));
                        String spec = (String) XposedHelpers.callMethod(tile, "getTileSpec");
                        Object state = XposedHelpers.callMethod(tile, "getState");
                        if (TextUtils.isEmpty(spec) || state == null) continue;
                        CharSequence title = charField(state, "label");
                        if (TextUtils.isEmpty(title)) {
                            title = (CharSequence) XposedHelpers.callMethod(
                                    tile, "getTileLabel");
                        }
                        Object icon = XposedHelpers.getObjectField(state, "icon");
                        if (icon == null) {
                            icon = XposedHelpers.getObjectField(state, "iconSupplier");
                        }
                        result.add(new TileInfo(spec, title, charField(state, "secondaryLabel"),
                                charField(state, "contentDescription"),
                                XposedHelpers.getIntField(state, "state"), available,
                                booleanField(state, "disabledByPolicy", false),
                                booleanField(state, "handlesSecondaryClick", false),
                                booleanField(state, "handlesLongClick", true), icon));
                    } catch (Throwable error) {
                        Log.w(TAG, "Skipping unreadable native tile state", error);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Unable to snapshot native QS tiles", error);
            }
            return result;
        }

        private void stopListening(Object tile, Object cb) {
            try { XposedHelpers.callMethod(tile, "setListening", listeningToken, false); }
            catch (Throwable error) { Log.w(TAG, "Tile listening cleanup failed", error); }
            try { XposedHelpers.callMethod(tile, "removeCallback", cb); }
            catch (Throwable error) { Log.w(TAG, "Tile callback cleanup failed", error); }
        }

        private void unbind() {
            for (Map.Entry<Object, Object> entry : callbacks.entrySet()) {
                stopListening(entry.getKey(), entry.getValue());
            }
            callbacks.clear();
            if (boundHost != null && hostCallback != null) {
                try { XposedHelpers.callMethod(boundHost, "removeCallback", hostCallback); }
                catch (Throwable error) { Log.w(TAG, "Host callback cleanup failed", error); }
            }
            boundHost = null;
            hostCallback = null;
        }
    }

    private NativeQuickSettingsTiles() {}
}
