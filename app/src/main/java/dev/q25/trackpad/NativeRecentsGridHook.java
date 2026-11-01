package dev.q25.trackpad;

import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Q25 portrait Recents experiment built around Quickstep's existing TaskViews.
 *
 * Uses PagedView's layout pass and native scrollTo/scroll range, not a second
 * thumbnail renderer or a separate hierarchy. There is no stock Quickstep
 * two-column vertical mode; this is an opt-in compatibility adapter.
 */
public final class NativeRecentsGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB-UI-Recents";
    private static final String SETTING = "bb_ui_vertical_recents";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String PAGED = "com.android.launcher3.PagedView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final WeakHashMap<View, State> STATES = new WeakHashMap<>();

    private static final class State {
        boolean resolved;
        boolean enabled;
        boolean dragFromBackground;
        boolean dragging;
        float downX;
        float downY;
        float lastY;
        int offset;
        int maxScroll;
        int rowStep;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if ("android".equals(lp.packageName)
                || "com.android.systemui".equals(lp.packageName)
                || "dev.q25.trackpad".equals(lp.packageName)) return;

        Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, lp.classLoader);
        Class<?> paged = XposedHelpers.findClassIfExists(PAGED, lp.classLoader);
        Class<?> task = XposedHelpers.findClassIfExists(TASK, lp.classLoader);
        if (recents == null || paged == null || task == null) return;

        try {
            // Native PagedView handles the two-panel sizing and task navigation.
            XposedHelpers.findAndHookMethod(paged, "getPanelCount", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (recents.isInstance(p.thisObject) && active((View) p.thisObject)) {
                        p.setResult(2);
                    }
                }
            });
            // Keep Quickstep's own thumbnail measurement and aspect ratio.
            XposedHelpers.findAndHookMethod(recents, "getTaskSize", Rect.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!active(view)) return;
                    Rect bounds = (Rect) p.args[0];
                    if (bounds == null || bounds.isEmpty()) return;
                    int width = view.getWidth() > 0 ? view.getWidth()
                            : view.getResources().getDisplayMetrics().widthPixels;
                    int margin = dp(view, 8);
                    int available = width - 3 * margin;
                    if (available <= 0 || bounds.width() <= 0) return;
                    int cardWidth = available / 2;
                    int cardHeight = Math.max(1, Math.round(
                            bounds.height() * (cardWidth / (float) bounds.width())));
                    int top = bounds.centerY() - cardHeight / 2;
                    bounds.set(margin, top, margin + cardWidth, top + cardHeight);
                }
            });

            // IMPORTANT: getPageScrolls(layoutChildren=true) is called *inside*
            // PagedView.onLayout, before RecentsView updates its remote simulators.
            // Place native TaskViews here, not in an after-onLayout callback.
            XposedBridge.hookAllMethods(recents, "getPageScrolls", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!active(root)) return;
                    if ((boolean) p.args[1]) layoutCards(root, task);
                    int[] pageScrolls = (int[]) p.args[0];
                    if (pageScrolls == null) return;
                    boolean changed = false;
                    for (int i = 0; i < pageScrolls.length; i++) {
                        if (pageScrolls[i] != 0) changed = true;
                        pageScrolls[i] = 0;
                    }
                    if (changed) p.setResult(true);
                }
            });

            // After native RecentsView.onLayout has updated its task simulator,
            // request the appropriate vertical thumbnails from its native model.
            // No task geometry is changed here.
            XposedBridge.hookAllMethods(recents, "onLayout", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    if (!active(root) || state(root).rowStep == 0) return;
                    root.scrollTo(0, state(root).offset);
                    XposedHelpers.callMethod(root, "loadVisibleTaskData");
                }
            });

            // Let PagedView update mMaxScroll itself using its normal layout flow.
            // The only changed value is the *actual vertical* content extent.
            XposedHelpers.findAndHookMethod(paged, "computeMaxScroll",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (recents.isInstance(view) && active(view)) {
                        p.setResult(state(view).maxScroll);
                    }
                }
            });

            // All scroll mutations still pass through Android/Quickstep scrollTo.
            XposedHelpers.findAndHookMethod(paged, "scrollTo",
                    int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!recents.isInstance(view) || !active(view)) return;
                    p.args[0] = 0;
                    p.args[1] = state(view).offset;
                }
            });

            XposedHelpers.findAndHookMethod(paged, "onGenericMotionEvent",
                    MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!recents.isInstance(view) || !active(view)) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return;
                    float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (vScroll == 0) return; // Do not swallow unrelated axes.
                    int pixels = Math.round(-vScroll * ViewConfiguration
                            .get(view.getContext()).getScaledVerticalScrollFactor());
                    scroll(view, pixels);
                    p.setResult(true);
                }
            });

            // Only blank-space vertical drags are handled here. Task drags,
            // swipes and dismissal stay in Quickstep's own touch controllers.
            XposedHelpers.findAndHookMethod(paged, "onInterceptTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!recents.isInstance(root) || !active(root)) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    State st = state(root);
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            st.downX = event.getX();
                            st.downY = event.getY();
                            st.lastY = st.downY;
                            st.dragging = false;
                            st.dragFromBackground = !hitsTask(root, task,
                                    st.downX, st.downY);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (st.dragging) {
                                p.setResult(true);
                                return;
                            }
                            int slop = ViewConfiguration.get(root.getContext())
                                    .getScaledTouchSlop();
                            float dx = event.getX() - st.downX;
                            float dy = event.getY() - st.downY;
                            if (st.dragFromBackground
                                    && Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) {
                                st.dragging = true;
                                st.lastY = event.getY();
                                p.setResult(true);
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            if (!st.dragging) st.dragFromBackground = false;
                            break;
                        default:
                            break;
                    }
                }
            });
            XposedHelpers.findAndHookMethod(paged, "onTouchEvent",
                    MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!recents.isInstance(view) || !active(view)) return;
                    State st = state(view);
                    if (!st.dragging) return;
                    MotionEvent event = (MotionEvent) p.args[0];
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_MOVE:
                            scroll(view, Math.round(st.lastY - event.getY()));
                            st.lastY = event.getY();
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            st.dragging = false;
                            st.dragFromBackground = false;
                            break;
                        default:
                            break;
                    }
                    p.setResult(true);
                }
            });

            // Ask the existing thumbnail model to load only vertically visible
            // task IDs; Quickstep still owns caching and screenshot acquisition.
            Class<?> utils = XposedHelpers.findClassIfExists(
                    "com.android.quickstep.views.RecentsViewUtils", lp.classLoader);
            if (utils != null) {
                XposedBridge.hookAllMethods(utils, "getVisibleTaskIds",
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object owner = XposedHelpers.getObjectField(
                                p.thisObject, "recentsView");
                        if (!(owner instanceof ViewGroup)) return;
                        ViewGroup root = (ViewGroup) owner;
                        if (!recents.isInstance(root) || !active(root)) return;
                        State st = state(root);
                        if (st.rowStep == 0) return;
                        int buffer = Math.max(st.rowStep, root.getHeight() / 2);
                        int minY = root.getScrollY() - buffer;
                        int maxY = root.getScrollY() + root.getHeight() + buffer;
                        Set<Integer> ids = new LinkedHashSet<>();
                        for (int i = 0; i < root.getChildCount(); i++) {
                            View child = root.getChildAt(i);
                            if (!task.isInstance(child)
                                    || child.getBottom() < minY || child.getTop() > maxY) {
                                continue;
                            }
                            @SuppressWarnings("unchecked")
                            Set<Integer> taskIds = (Set<Integer>)
                                    XposedHelpers.callMethod(child, "getTaskIdSet");
                            if (taskIds != null) ids.addAll(taskIds);
                        }
                        p.setResult(new ArrayList<>(ids));
                    }
                });
            }
            Log.w(TAG, "Native TaskView grid hooks registered in " + lp.packageName);
        } catch (Throwable failure) {
            Log.e(TAG, "Cannot install vertical Recents hooks", failure);
            XposedBridge.log("BB UI vertical Recents unavailable: " + failure);
        }
    }

    private static synchronized State state(View view) {
        State st = STATES.get(view);
        if (st == null) {
            st = new State();
            STATES.put(view, st);
        }
        return st;
    }

    private static boolean active(View view) {
        State st = state(view);
        if (!st.resolved) {
            st.resolved = true;
            try {
                st.enabled = Settings.Global.getInt(
                        view.getContext().getContentResolver(), SETTING, 0) == 1;
            } catch (RuntimeException e) {
                Log.w(TAG, "Recents flag unreadable; leaving native overview unchanged", e);
            }
        }
        return st.enabled;
    }

    private static void layoutCards(ViewGroup root, Class<?> task) {
        State st = state(root);
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;
        List<View> cards = new ArrayList<>();
        View clearAll = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (task.isInstance(child)) cards.add(child);
            else if (child.getClass().getName().endsWith(".ClearAllButton"))
                clearAll = child;
        }

        int gap = dp(root, 8);
        int cardHeight = 0;
        for (View card : cards) cardHeight = Math.max(cardHeight, card.getHeight());
        if (cards.isEmpty() || cardHeight == 0) {
            st.rowStep = 0;
            st.maxScroll = 0;
            st.offset = 0;
            return;
        }
        int columnWidth = (root.getWidth() - gap * 3) / 2;
        if (columnWidth <= 0) return;
        st.rowStep = cardHeight + gap;
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            int column = i % 2;
            int row = i / 2;
            int x = gap + column * (columnWidth + gap)
                    + (columnWidth - card.getWidth()) / 2
                    - Math.round(card.getTranslationX());
            int y = gap + row * st.rowStep - Math.round(card.getTranslationY());
            card.offsetLeftAndRight(x - card.getLeft());
            card.offsetTopAndBottom(y - card.getTop());
        }
        int contentBottom = gap + ((cards.size() + 1) / 2) * st.rowStep;
        if (clearAll != null && clearAll.getHeight() > 0) {
            int x = (root.getWidth() - clearAll.getWidth()) / 2;
            clearAll.offsetLeftAndRight(x - clearAll.getLeft());
            clearAll.offsetTopAndBottom(contentBottom - clearAll.getTop());
            contentBottom += clearAll.getHeight() + gap;
        }
        st.maxScroll = Math.max(0, contentBottom - root.getHeight());
        st.offset = Math.max(0, Math.min(st.offset, st.maxScroll));
    }

    private static boolean hitsTask(ViewGroup root, Class<?> task, float x, float y) {
        float contentX = x + root.getScrollX();
        float contentY = y + root.getScrollY();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!task.isInstance(child)) continue;
            float left = child.getX();
            float top = child.getY();
            if (contentX >= left && contentX < left + child.getWidth()
                    && contentY >= top && contentY < top + child.getHeight()) return true;
        }
        return false;
    }

    private static void scroll(View view, int pixels) {
        State st = state(view);
        int next = Math.max(0, Math.min(st.maxScroll, st.offset + pixels));
        if (next == st.offset) return;
        st.offset = next;
        view.scrollTo(0, next);
        XposedHelpers.callMethod(view, "loadVisibleTaskData");
    }

    private static int dp(View view, int dip) {
        return Math.round(dip * view.getResources().getDisplayMetrics().density);
    }
}
