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
     * Return tile objects owned by the native QS pipeline. QSHostAdapter and
     * CurrentTilesInteractor expose the SAME native QSTile objects.
     * Reading the interactor here is not tile creation or a second backend.
     */
    private static Collection<?> currentTiles() {
        Object capturedHost = host.get();
        if (capturedHost != null) {
            try {
                Object result = XposedHelpers.callMethod(capturedHost, "getTiles");
                if (result instanceof Collection && !((Collection<?>) result).isEmpty()) {
                    return (Collection<?>) result;
                }
            } catch (Throwable error) {
                Log.w(TAG, "QSHostAdapter tile query unavailable", error);
            }
        }
        Object capturedInteractor = interactor.get();
        if (capturedInteractor != null) {
            try {
                // Kotlin: CurrentTilesInteractor.currentTiles: StateFlow<List<TileModel>>
                // Each TileModel carries the SystemUI-owned QSTile instance.
                Object flow = XposedHelpers.callMethod(capturedInteractor, "getCurrentTiles");
                Object models = XposedHelpers.callMethod(flow, "getValue");
                if (models instanceof Collection) {
                    List<Object> tiles = new ArrayList<>();
                    for (Object model : (Collection<?>) models) {
                        if (model != null) {
                            tiles.add(XposedHelpers.callMethod(model, "getTile"));
                        }
                    }
                    return tiles;
                }
            } catch (Throwable error) {
                Log.w(TAG, "CurrentTilesInteractor tile query unavailable", error);
            }
        }
        return Collections.emptyList();
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
