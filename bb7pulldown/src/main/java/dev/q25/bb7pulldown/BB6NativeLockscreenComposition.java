package dev.q25.bb7pulldown;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ROM-oriented composition adapter for the BB6 lock screen.
 *
 * Physically DETACHES duplicate Android UI nodes instead of making them
 * transparent or GONE. Re-attaches them when the same native keyguard root
 * is needed for AOD, unlock transitions or non-BB6 modes.
 *
 * Keep the parent containers and their nonduplicate children (weather,
 * charge/security indications, emergency actions, battery and signal).
 * No authentication or notification records are mutated.
 *
 * On a native LineageOS fork, implement this policy at KeyguardBlueprint /
 * ClockSection / SmartspaceSection composition time rather than detaching.
 */
final class BB6NativeLockscreenComposition {
    private static final String TAG = "BB6-LockScreen";
    private static final String SYSTEM_UI = "com.android.systemui";

    static final class Slot {
        final View view;
        final ViewGroup parent;
        final int index;
        final ViewGroup.LayoutParams layoutParams;

        Slot(View view, ViewGroup parent) {
            this.view = view;
            this.parent = parent;
            this.index = parent.indexOfChild(view);
            this.layoutParams = view.getLayoutParams();
        }
    }

    private final Map<View, Slot> detached = new LinkedHashMap<>();
    private final List<WeakReference<View>> clockViews = new ArrayList<>();
    private WeakReference<ViewGroup> nativeRoot = new WeakReference<>(null);
    private int smallClockId;
    private int largeClockId;
    private boolean missingClockLogged;
    private boolean active;
    private boolean strippedLogged;

    void setClockRootIds(int small, int large) {
        smallClockId = small;
        largeClockId = large;
    }

    /**
     * Native ClockViewIds uses View.generateViewId(), so root clock IDs
     * cannot be looked up by Android resource name. Keep the actual views
     * captured from KeyguardClockViewBinder.addClockViews as a fallback.
     */
    void captureClockController(Object controller) {
        if (controller == null) return;
        ArrayList<View> views = new ArrayList<>();
        collectClockFace(controller, "getSmallClock", views);
        collectClockFace(controller, "getLargeClock", views);
        if (views.isEmpty()) {
            if (!missingClockLogged) {
                missingClockLogged = true;
                Log.w(TAG, "Native clock face views not exposed on this build");
            }
            return;
        }
        clockViews.clear();
        for (View v : views) clockViews.add(new WeakReference<>(v));
        missingClockLogged = false;
    }

    private static void collectClockFace(Object clock, String getter, List<View> out) {
        try {
            Object face = callGetter(clock, getter);
            Object layout = callGetter(face, "getLayout");
            Object value = callGetter(layout, "getViews");
            if (!(value instanceof Iterable)) return;
            for (Object candidate : (Iterable<?>) value) {
                if (candidate instanceof View && !out.contains(candidate)) {
                    out.add((View) candidate);
                }
            }
        } catch (Throwable error) {
            // The runtime view IDs provide an independent fallback.
        }
    }

    private static Object callGetter(Object target, String name) throws Exception {
        if (target == null) return null;
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    void sync(ViewGroup outerRoot, boolean showBb6LockScreen) {
        ViewGroup keyguardRoot = findKeyguardRoot(outerRoot);
        if (keyguardRoot == null) {
            if (!showBb6LockScreen) restore();
            return;
        }
        ViewGroup previousRoot = nativeRoot.get();
        if (previousRoot != null && previousRoot != keyguardRoot) restore();
        nativeRoot = new WeakReference<>(keyguardRoot);

        if (!showBb6LockScreen) {
            restore();
            return;
        }

        active = true;
        int removed = 0;
        // Native clock faces are dynamically generated and may contain more
        // than one view (e.g. per-digit custom clock layouts).
        for (WeakReference<View> reference : clockViews) {
            View clock = reference.get();
            if (clock != null && belongsTo(clock, keyguardRoot)) {
                if (detach(clock)) removed++;
            }
        }
        if (smallClockId != 0) {
            if (detachById(keyguardRoot, smallClockId)) removed++;
        }
        if (largeClockId != 0) {
            if (detachById(keyguardRoot, largeClockId)) removed++;
        }

        // Native keyguard carrier is redundant with the BB6 operator line.
        // Its sibling remains the native battery/signal/privacy icon group.
        if (detachByName(keyguardRoot, "keyguard_carrier_text")) removed++;

        // Date/weather roots are mixed-content. Retain their weather, alarm
        // and DND children; detach only the first child (the date plugin),
        // which SmartspaceSection.setupDateWeather inserts before weather.
        if (detachDateOnly(keyguardRoot, "date_smartspace_view")) removed++;
        if (detachDateOnly(keyguardRoot, "date_smartspace_view_large")) removed++;

        // This icon row is also used on AOD. Restore it on screen-off,
        // then BB6 app+count indicators replace it while interactive.
        if (detachByName(keyguardRoot, "aod_notification_icon_container")) removed++;

        if (removed > 0 && !strippedLogged) {
            strippedLogged = true;
            Log.i(TAG, "Detached redundant native lockscreen elements; count="
                    + detached.size());
        }
    }

    boolean isActive() {
        return active;
    }

    void restore() {
        if (detached.isEmpty()) {
            active = false;
            strippedLogged = false;
            return;
        }
        // Restore reversed insertion order so nested children reattach only
        // after their original parent is available.
        ArrayList<Slot> slots = new ArrayList<>(detached.values());
        Collections.reverse(slots);
        for (Slot slot : slots) {
            if (slot.view.getParent() != null) continue;
            if (slot.parent == null) continue;
            int index = Math.min(slot.index, slot.parent.getChildCount());
            try {
                slot.parent.addView(slot.view, index, slot.layoutParams);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not reattach native keyguard child "
                        + slot.view.getClass().getSimpleName());
            }
        }
        detached.clear();
        active = false;
        strippedLogged = false;
        Log.i(TAG, "Native keyguard composition restored");
    }

    private static ViewGroup findKeyguardRoot(ViewGroup outer) {
        if (outer == null) return null;
        int id = id(outer.getContext(), "keyguard_root_view");
        View root = id == 0 ? null : outer.findViewById(id);
        return root instanceof ViewGroup ? (ViewGroup) root : null;
    }

    private boolean detachByName(ViewGroup root, String resourceName) {
        int id = id(root.getContext(), resourceName);
        return id != 0 && detachById(root, id);
    }

    private boolean detachById(ViewGroup root, int id) {
        View view = root.findViewById(id);
        if (view == root) return false;
        return view != null && belongsTo(view, root) && detach(view);
    }

    private boolean detachDateOnly(ViewGroup root, String groupResourceName) {
        int id = id(root.getContext(), groupResourceName);
        if (id == 0) return false;
        View group = root.findViewById(id);
        if (!(group instanceof ViewGroup)) return false;
        ViewGroup children = (ViewGroup) group;
        if (children.getChildCount() == 0) return false;
        View date = children.getChildAt(0);
        // In upstream SmartspaceSection the date view is inserted first,
        // weather (if any) is inserted at index 1. Never remove weather.
        return date != null && detach(date);
    }

    private static int id(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", SYSTEM_UI);
    }

    private boolean detach(View view) {
        if (view == null || detached.containsKey(view)) return false;
        ViewParent parent = view.getParent();
        if (!(parent instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) parent;
        Slot slot = new Slot(view, group);
        if (slot.index < 0) return false;
        try {
            group.removeView(view);
            detached.put(view, slot);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Native keyguard view detach declined: "
                    + view.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean belongsTo(View child, ViewGroup root) {
        for (ViewParent parent = child.getParent(); parent != null;
                parent = parent.getParent()) {
            if (parent == root) return true;
        }
        return false;
    }
}
