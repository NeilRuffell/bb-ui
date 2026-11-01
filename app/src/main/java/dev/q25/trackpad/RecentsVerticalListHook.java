package dev.q25.trackpad;

import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Vertical rows of the *existing* two-up Quickstep TaskViews.
 *
 * Does not create thumbnails, call TaskView.layout(), change TaskView scaling,
 * replace child views, or intercept app clicks / dismissal / animations.
 * Native one-finger gestures starting on an app remain with Quickstep.
 *
 * Vertical scroll: trackpad wheel, drag on blank space, or two-finger drag.
 * Experimental: Quickstep is fundamentally a horizontal PagedView.
 * Disable with: adb shell settings put global bb_ui_recents_vertical_list 0
 * Reboot to restore native layout.
 */
public final class RecentsVerticalListHook implements IXposedHookLoadPackage {
    private static final String TOGGLE = "bb_ui_recents_vertical_list";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String PAGED = "com.android.launcher3.PagedView";
    private static final String LOG_TAG = "BB-UI-Recents";
    private static final WeakHashMap<View, GridState> STATES = new WeakHashMap<>();

    private static final class GridState {
        int offset;
        int maxOffset;
        int rowHeight;
        boolean placing;
        boolean loggedActive;
        boolean dragging;
        boolean startedOutsideTask;
        boolean horizontalDrag;
        float downX;
        float downY;
        float lastY;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName) || "com.android.systemui".equals(lp.packageName)
                || "dev.q25.trackpad".equals(lp.packageName)) return;

        final Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        final Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        final Class<?> paged = XposedHelpers.findClassIfExists(PAGED, lp.classLoader);
        if (recents == null || task == null || paged == null) return;

        try {
            // All native Quickstep layout/preview work happens BEFORE this hook.
            XposedBridge.hookAllMethods(recents, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!enabled(root)) return;
                    GridState st = state(root);
                    if (!st.loggedActive) {
                        st.loggedActive = true;
                        Log.i(LOG_TAG, "Vertical Recents layout active: children="
                                + root.getChildCount());
                    }
                    positionNativeTaskViews(root, task);
                    refreshVisibleTaskData(root);
                }
            });

            // Block only the horizontal scroll of this RecentsView. Vertical scroll is a
            // normal View.scrollY, so drawing, clipping and hit-testing stay native.
            XposedHelpers.findAndHookMethod(paged, "scrollTo", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View view = (View) p.thisObject;
                            if (!recents.isInstance(view) || !enabled(view)) return;
                            p.args[0] = 0;
                            if ((int) p.args[1] == 0) p.args[1] = state(view).offset;
                        }
                    });

            XposedBridge.hookAllMethods(paged, "onGenericMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!recents.isInstance(view) || !enabled(view)) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                    float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (wheel != 0) scrollBy(view, Math.round(
                            -wheel * view.getResources().getDisplayMetrics().density * 64f));
                    p.setResult(true); // Never turn trackpad movement into horizontal paging.
                }
            });

            // Preserve Quickstep's own one-finger task gestures (including dismissal).
            // Empty-space drags and two-finger drags scroll the vertical list.
            XposedBridge.hookAllMethods(paged, "onInterceptTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!recents.isInstance(root) || !enabled(root)) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    GridState st = state(root);
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            st.downX = event.getX();
                            st.downY = event.getY();
                            st.lastY = st.downY;
                            st.dragging = false;
                            st.horizontalDrag = false;
                            st.startedOutsideTask = !touchesTask(
                                    root, task, st.downX, st.downY);
                            p.setResult(false);
                            return;
                        case MotionEvent.ACTION_POINTER_DOWN:
                            st.dragging = true;
                            st.lastY = event.getY();
                            p.setResult(true);
                            return;
                        case MotionEvent.ACTION_MOVE:
                            int slop = ViewConfiguration.get(root.getContext())
                                    .getScaledTouchSlop();
                            float dx = event.getX() - st.downX;
                            float dy = event.getY() - st.downY;
                            if (st.dragging) {
                                p.setResult(true);
                            } else if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                                st.horizontalDrag = true;
                                p.setResult(false); // no native horizontal page interception
                            } else if ((st.startedOutsideTask || event.getPointerCount() > 1)
                                    && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                                st.dragging = true;
                                st.lastY = event.getY();
                                p.setResult(true);
                            } else {
                                p.setResult(false);
                            }
                            return;
                        case MotionEvent.ACTION_CANCEL:
                        case MotionEvent.ACTION_UP:
                            // Let onTouchEvent consume the terminal event before clearing
                            // dragging, or Quickstep may treat the release as a home tap.
                            p.setResult(st.dragging);
                            if (!st.dragging) st.horizontalDrag = false;
                            return;
                        default:
                            p.setResult(false);
                    }
                }
            });

            XposedBridge.hookAllMethods(recents, "onTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!enabled(view)) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    GridState st = state(view);
                    if (!st.dragging) return;
                    if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        float y = event.getY();
                        scrollBy(view, Math.round(st.lastY - y));
                        st.lastY = y;
                    } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                            && event.getPointerCount() <= 2) {
                        st.dragging = false;
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        st.dragging = false;
                        st.horizontalDrag = false;
                    }
                    p.setResult(true);
                }
            });

            XposedBridge.hookAllMethods(paged, "onTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (recents.isInstance(view) && enabled(view)
                            && state(view).horizontalDrag) {
                        p.setResult(true);
                    }
                }
            });

            // Quickstep normally only fetches thumbnails around the horizontal page.
            // Let its normal thumbnail loader see every card in/near the vertical viewport.
            Class<?> utils = XposedHelpers.findClassIfExists(
                    "com.android.quickstep.views.RecentsViewUtils", lp.classLoader);
            if (utils != null) {
                XposedBridge.hookAllMethods(utils, "getVisibleTaskIds", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object owner = XposedHelpers.getObjectField(p.thisObject, "recentsView");
                        if (!(owner instanceof ViewGroup)) return;
                        ViewGroup root = (ViewGroup) owner;
                        if (!recents.isInstance(root) || !enabled(root)) return;
                        @SuppressWarnings("unchecked")
                        List<Integer> ids = (List<Integer>) p.getResult();
                        LinkedHashSet<Integer> visible = new LinkedHashSet<>();
                        if (ids != null) visible.addAll(ids);
                        int extra = Math.max(root.getHeight() / 2, state(root).rowHeight);
                        int start = root.getScrollY() - extra;
                        int end = root.getScrollY() + root.getHeight() + extra;
                        for (int i = 0; i < root.getChildCount(); i++) {
                            View child = root.getChildAt(i);
                            if (!task.isInstance(child) || child.getBottom() < start
                                    || child.getTop() > end) continue;
                            int[] taskIds = (int[]) XposedHelpers.callMethod(child, "getTaskIds");
                            if (taskIds != null) {
                                for (int id : taskIds) if (id >= 0) visible.add(id);
                            }
                        }
                        p.setResult(new ArrayList<>(visible));
                    }
                });
            }
            XposedBridge.log("BB UI: vertical native TaskView list hooked in " + lp.packageName);
            Log.i(LOG_TAG, "Vertical hook installed in " + lp.packageName);
        } catch (Throwable ex) {
            XposedBridge.log("BB UI: vertical native list unavailable: " + ex);
            Log.e(LOG_TAG, "Vertical hook installation failed", ex);
        }
    }

    private static boolean enabled(View view) {
        try {
            // The first layout can occur before mOverviewStateEnabled turns true;
            // setOverviewStateEnabled() does not request a relayout. Gating on that
            // field silently prevented the entire vertical layout from activating.
            return Settings.Global.getInt(view.getContext().getContentResolver(),
                    TOGGLE, 0) == 1
                    && Settings.Global.getInt(view.getContext().getContentResolver(),
                    "bb_ui_recents_two_up", 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized GridState state(View view) {
        GridState st = STATES.get(view);
        if (st == null) {
            st = new GridState();
            STATES.put(view, st);
        }
        return st;
    }

    private static void positionNativeTaskViews(ViewGroup root, Class<?> task) {
        GridState st = state(root);
        if (st.placing || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        st.placing = true;
        try {
            ArrayList<View> cards = new ArrayList<>();
            View clearAll = null;
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (task.isInstance(child)) cards.add(child);
                else if (child.getClass().getName().endsWith(".ClearAllButton")) {
                    clearAll = child;
                }
            }
            if (cards.isEmpty()) return;

            int margin = dp(root, 8);
            int gap = dp(root, 8);
            int rowHeight = 0;
            for (View card : cards) rowHeight = Math.max(rowHeight, card.getHeight());
            if (rowHeight <= 0) return;
            st.rowHeight = rowHeight + gap;
            int columns = 2;
            int columnWidth = (root.getWidth() - 2 * margin - gap) / columns;
            int firstVisualX = Math.round(cards.get(0).getX());
            int secondVisualX = cards.size() >= 2
                    ? Math.round(cards.get(1).getX()) : firstVisualX + columnWidth + gap;
            int initialVisualY = margin;

            // The first two columns retain their native Quickstep horizontal positions.
            // Other tasks are moved into rows by adjusting bounds only. Their existing
            // translations, scaling, snapshots, and click/dismiss listeners are untouched.
            for (int i = 0; i < cards.size(); i++) {
                View card = cards.get(i);
                int col = i % columns;
                int row = i / columns;
                int targetX = (col == 0 ? firstVisualX : secondVisualX)
                        - Math.round(card.getTranslationX());
                int targetY = initialVisualY + row * st.rowHeight
                        - Math.round(card.getTranslationY());
                card.offsetLeftAndRight(targetX - card.getLeft());
                card.offsetTopAndBottom(targetY - card.getTop());
            }

            int rows = (cards.size() + 1) / columns;
            int contentBottom = initialVisualY + rows * st.rowHeight + margin;
            if (clearAll != null && clearAll.getHeight() > 0) {
                int targetX = (root.getWidth() - clearAll.getWidth()) / 2
                        - Math.round(clearAll.getTranslationX());
                int targetY = contentBottom - Math.round(clearAll.getTranslationY());
                clearAll.offsetLeftAndRight(targetX - clearAll.getLeft());
                clearAll.offsetTopAndBottom(targetY - clearAll.getTop());
                contentBottom += clearAll.getHeight() + gap;
            }

            st.maxOffset = Math.max(0, contentBottom - root.getHeight());
            st.offset = Math.min(Math.max(0, st.offset), st.maxOffset);

            // PagedView.scrollTo clamps both axes against mMaxScroll. Its native X
            // limit is replaced with the actual vertical content extent.
            XposedHelpers.setIntField(root, "mMaxScroll", st.maxOffset);
            root.setClipToPadding(false);
            root.scrollTo(0, st.offset);
        } catch (Throwable error) {
            XposedBridge.log("BB UI: vertical list placement error: " + error);
            Log.e(LOG_TAG, "Vertical placement failed", error);
        } finally {
            st.placing = false;
        }
    }

    private static boolean touchesTask(ViewGroup root, Class<?> task, float x, float y) {
        float contentY = y + root.getScrollY();
        for (int i = 0; i < root.getChildCount(); i++) {
            View view = root.getChildAt(i);
            if (!task.isInstance(view)) continue;
            float vx = view.getX();
            float vy = view.getY();
            if (x >= vx && x < vx + view.getWidth()
                    && contentY >= vy && contentY < vy + view.getHeight()) {
                return true;
            }
        }
        return false;
    }

    private static void scrollBy(View view, int delta) {
        if (delta == 0) return;
        GridState st = state(view);
        int target = Math.max(0, Math.min(st.offset + delta, st.maxOffset));
        if (target == st.offset) return;
        st.offset = target;
        view.scrollTo(0, target);
        refreshVisibleTaskData(view);
    }

    private static void refreshVisibleTaskData(View view) {
        try {
            XposedHelpers.callMethod(view, "loadVisibleTaskData");
        } catch (Throwable ex) {
            XposedBridge.log("BB UI: native thumbnail loading failed: " + ex);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }
}
