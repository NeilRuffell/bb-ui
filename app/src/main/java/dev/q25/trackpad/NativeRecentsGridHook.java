package dev.q25.trackpad;

import android.graphics.Rect;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * Portrait two-column Recents using Quickstep's own TaskViews and native
 * PagedView scrolling. No replacement thumbnail/task renderer.
 *
 * The orientation adapter uses Quickstep's landscape PRIMARY AXIS (Y) for
 * scrolling, snapping, fling and accessibility. Quickstep's landscape
 * task-gesture methods provide horizontal dismissal and launch gestures.
 * Portrait-specific rendering remains upright. The only custom layout is
 * the required mapping of task indices to 2 columns.
 *
 * This is an experimental Xposed compatibility layer, not a stock setting.
 */
public final class NativeRecentsGridHook implements IXposedHookLoadPackage {
    private static final String TAG = "BB-UI-Recents";
    private static final String FLAG = "bb_ui_vertical_recents";
    private static final String TARGET = "com.android.launcher3";
    private static final String RECENTS = "com.android.quickstep.views.RecentsView";
    private static final String TASK = "com.android.quickstep.views.TaskView";
    private static final String PAGED = "com.android.launcher3.PagedView";
    private static final String HANDLER =
            "com.android.quickstep.orientation.RecentsPagedOrientationHandler";

    private static int enabled = -1;
    private static boolean firstLayoutLogged;
    private static final WeakHashMap<View, Boolean> SCREENSHOT_PENDING = new WeakHashMap<>();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName)) return;

        Log.w(TAG, "BB UI entry reached: package=" + lp.packageName
                + " process=" + lp.processName);

        ClassLoader loader = lp.classLoader;
        Class<?> recents = XposedHelpers.findClassIfExists(RECENTS, loader);
        Class<?> paged = XposedHelpers.findClassIfExists(PAGED, loader);
        Class<?> task = XposedHelpers.findClassIfExists(TASK, loader);
        Class<?> handler = XposedHelpers.findClassIfExists(HANDLER, loader);
        if (recents == null || paged == null || task == null || handler == null) {
            Log.e(TAG, "Unsupported Quickstep classes: RecentsView=" + (recents != null)
                    + " PagedView=" + (paged != null)
                    + " TaskView=" + (task != null)
                    + " OrientationHandler=" + (handler != null));
            return;
        }

        try {
            final Object portrait = XposedHelpers.getStaticObjectField(handler, "PORTRAIT");
            final Object landscape = XposedHelpers.getStaticObjectField(handler, "LANDSCAPE");
            final Object adapted = orientationProxy(handler, portrait, landscape, loader);

            // TaskView obtains its orientation directly from RecentsOrientedState,
            // not from the PagedView field. Redirect its native primary/secondary
            // translation properties to the same axis as the grid. Without this,
            // horizontal dismissal would still animate on the vertical axis.
            XposedHelpers.findAndHookMethod(task, "getPagedOrientationHandler",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (active((View) p.thisObject)) p.setResult(adapted);
                }
            });

            // Replace the pager axis at its own native configuration point.
            // This is invoked by RecentsView during initialization and rotation.
            XposedHelpers.findAndHookMethod(paged, "setOrientationHandler",
                    XposedHelpers.findClass(
                            "com.android.launcher3.touch.PagedOrientationHandler", loader),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!recents.isInstance(p.thisObject)
                            || !active((View) p.thisObject)) return;
                    if (p.args[0] == adapted) return;
                    p.args[0] = adapted;
                }
            });

            // The native two-panel machinery already groups pairs for paging.
            XposedHelpers.findAndHookMethod(paged, "getPanelCount", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (recents.isInstance(p.thisObject)
                            && active((View) p.thisObject)) p.setResult(2);
                }
            });

            // Resize native TaskViews via Quickstep's existing updateTaskSize().
            XposedHelpers.findAndHookMethod(recents, "getTaskSize", Rect.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    if (!active(root)) return;
                    Rect size = (Rect) p.args[0];
                    if (size == null || size.isEmpty()) return;
                    int width = root.getWidth() > 0
                            ? root.getWidth() : root.getResources()
                                    .getDisplayMetrics().widthPixels;
                    int gap = dp(root, 8);
                    int cardWidth = (width - gap * 3) / 2;
                    if (cardWidth <= 0 || size.width() <= 0) return;
                    int height = Math.max(1, Math.round(size.height()
                            * cardWidth / (float) size.width()));
                    int top = size.centerY() - height / 2;
                    size.set(gap, top, gap + cardWidth, top + height);
                }
            });

            // Own the two-column placement INSIDE Quickstep's native layout pass.
            // The page offsets are Y coordinates, consumed by native PagedView:
            // scrollTo, mScroller, velocity, snap-to-page and touch interception.
            XposedBridge.hookAllMethods(recents, "getPageScrolls",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ViewGroup root = (ViewGroup) p.thisObject;
                    if (!active(root)) return;
                    int[] scrolls = (int[]) p.args[0];
                    boolean layoutChildren = (Boolean) p.args[1];
                    if (root.getWidth() == 0 || root.getHeight() == 0
                            || scrolls.length != root.getChildCount()) return;
                    p.setResult(layoutNativeTasks(root, task, scrolls, layoutChildren));
                }
            });

            // Keep thumbnail loading in the stock RecentsViewModel. Only supply
            // the IDs intersecting the newly vertical viewport.
            Class<?> utils = XposedHelpers.findClassIfExists(
                    "com.android.quickstep.views.RecentsViewUtils", loader);
            if (utils != null) {
                XposedBridge.hookAllMethods(utils, "getVisibleTaskIds",
                        new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object owner = XposedHelpers.getObjectField(
                                p.thisObject, "recentsView");
                        if (!(owner instanceof ViewGroup)) return;
                        ViewGroup root = (ViewGroup) owner;
                        if (!recents.isInstance(root) || !active(root)
                                || root.getHeight() <= 0) return;

                        int buffer = root.getHeight() / 2;
                        int top = root.getScrollY() - buffer;
                        int bottom = root.getScrollY() + root.getHeight() + buffer;
                        Set<Integer> ids = new LinkedHashSet<>();
                        for (int i = 0; i < root.getChildCount(); i++) {
                            View card = root.getChildAt(i);
                            if (!task.isInstance(card)
                                    || card.getBottom() + card.getTranslationY() < top
                                    || card.getTop() + card.getTranslationY() > bottom) continue;
                            @SuppressWarnings("unchecked")
                            Set<Integer> taskIds = (Set<Integer>)
                                    XposedHelpers.callMethod(card, "getTaskIdSet");
                            if (taskIds != null) ids.addAll(taskIds);
                        }
                        p.setResult(new ArrayList<>(ids));
                    }
                });
            }

            // Quickstep deliberately hides the running task's screenshot and
            // composites its live remote app surface instead. That surface
            // follows the stock fullscreen simulator, not our two-column
            // TaskView geometry. Use Quickstep's own screenshot transition and
            // finish the remote animation once the screenshot is drawn.
            //
            // onGestureAnimationEnd is the normal entry point. onLayout acts
            // as a fallback for three-button Recents activation paths.
            XposedBridge.hookAllMethods(recents, "onGestureAnimationEnd",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    requestNativeRunningTaskScreenshot((View) p.thisObject);
                }
            });

            XposedBridge.hookAllMethods(recents, "onLayout",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View root = (View) p.thisObject;
                    if (!active(root)) return;
                    if (!firstLayoutLogged) {
                        firstLayoutLogged = true;
                        Object orientation = XposedHelpers.getObjectField(
                                root, "mOrientationHandler");
                        Log.w(TAG, "Vertical grid layout reached; children="
                                + ((ViewGroup) root).getChildCount()
                                + " handler=" + orientation.getClass().getName()
                                + " scrollY=" + root.getScrollY());
                    }
                    requestNativeRunningTaskScreenshot(root);
                }
            });

            Log.w(TAG, "Two-column vertical Quickstep adapter registered");
        } catch (Throwable error) {
            Log.e(TAG, "Quickstep hook installation failed", error);
            XposedBridge.log("BB UI: Quickstep hook installation failed: " + error);
        }
    }

    /**
     * A running task uses a live SurfaceControl layer instead of its TaskView
     * thumbnail. The layer is rendered using TaskViewSimulator's stock geometry
     * and therefore stays fullscreen behind our resized grid card. Capture the
     * native task screenshot, wait for its thumbnail frame, and then finish the
     * live Recents animation through the same path used by stock Quickstep for
     * rotation/split selection. No screenshot copying or custom renderer.
     */
    private static void requestNativeRunningTaskScreenshot(View root) {
        if (!active(root)
                || !XposedHelpers.getBooleanField(root, "mOverviewStateEnabled")
                || XposedHelpers.getObjectField(root, "mActiveGestureGroupedTaskInfo") != null
                || XposedHelpers.getBooleanField(root, "mRunningTaskShowScreenshot")
                || XposedHelpers.getObjectField(root, "mRecentsAnimationController") == null
                || XposedHelpers.callMethod(root, "getRunningTaskView") == null) {
            return;
        }
        synchronized (SCREENSHOT_PENDING) {
            if (SCREENSHOT_PENDING.containsKey(root)) return;
            SCREENSHOT_PENDING.put(root, Boolean.TRUE);
        }
        // Run after Quickstep's entry/layout callbacks complete.
        root.post(() -> {
            try {
                if (!active(root)
                        || !XposedHelpers.getBooleanField(root, "mOverviewStateEnabled")
                        || XposedHelpers.getObjectField(root, "mRecentsAnimationController") == null) {
                    clearScreenshotPending(root);
                    return;
                }
                Log.w(TAG, "Capturing running task via native Quickstep screenshot");
                XposedHelpers.callMethod(root, "switchToScreenshot", (Runnable) () -> {
                    try {
                        if (XposedHelpers.getObjectField(root, "mRecentsAnimationController") != null) {
                            XposedHelpers.callMethod(root, "finishRecentsAnimation",
                                    true, false, (Runnable) () -> {
                                        try {
                                            XposedHelpers.callMethod(root,
                                                    "setEnableDrawingLiveTile", false);
                                            Log.w(TAG, "Running task converted to native grid thumbnail");
                                        } finally {
                                            clearScreenshotPending(root);
                                        }
                                    });
                        } else {
                            XposedHelpers.callMethod(root, "setEnableDrawingLiveTile", false);
                            Log.w(TAG, "Running task screenshot ready; no remote animation");
                            clearScreenshotPending(root);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "Failed to finish running task live tile", error);
                        clearScreenshotPending(root);
                    }
                });
            } catch (Throwable error) {
                Log.e(TAG, "Failed to capture running task screenshot", error);
                clearScreenshotPending(root);
            }
        });
    }

    private static void clearScreenshotPending(View root) {
        synchronized (SCREENSHOT_PENDING) {
            SCREENSHOT_PENDING.remove(root);
        }
    }

    private static boolean active(View view) {
        if (enabled == -1) {
            try {
                enabled = Settings.Global.getInt(view.getContext()
                        .getContentResolver(), FLAG, 0) == 1 ? 1 : 0;
            } catch (Throwable error) {
                enabled = 0; // fail closed: untouched native overview
                Log.e(TAG, "Cannot read Recents setting", error);
            }
            Log.w(TAG, "Vertical Recents setting=" + enabled);
        }
        return enabled == 1;
    }

    /**
     * Use the native landscape primary (Y) pager axis and horizontal task
     * dismiss/launch detector while delegating rendering/rotation to portrait.
     * The same proxy is supplied to RecentsView and TaskView.
     */
    private static Object orientationProxy(Class<?> type, Object portrait,
            Object landscape, ClassLoader loader) {
        InvocationHandler adapter = new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args)
                    throws Throwable {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(name)) return proxy == args[0];
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    return "BBUIVerticalPrimaryAxis";
                }
                Object delegate = isVerticalGridAxisOrGestureMethod(name)
                        ? landscape : portrait;
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, adapter);
    }

    private static boolean isVerticalGridAxisOrGestureMethod(String method) {
        switch (method) {
            case "getPrimaryValue":
            case "getSecondaryValue":
            case "getPrimaryDirection":
            case "getPrimaryVelocity":
            case "getPrimaryScroll":
            case "getMeasuredSize":
            case "getPrimarySize":
            case "getSecondarySize":
            case "getSecondaryDimension":
            case "getChildStart":
            case "getChildEnd":
            case "getChildBounds":
            case "getStart":
            case "getEnd":
            case "getPrimaryScale":
            case "getScrollOffsetStart":
            case "getScrollOffsetEnd":
            case "getCenterForPage":
            case "getPrimaryViewTranslate":
            case "getSecondaryViewTranslate":
            case "setPrimary":
            case "setMaxScroll":
            // Native Quickstep task gesture controllers construct their
            // SingleAxisSwipeDetector from these interface methods.
            // Landscape means HORIZONTAL dismiss/launch on a portrait display.
            case "getUpDownSwipeDirection":
            case "getUpDirection":
            case "getDownDirection":
            case "isGoingUp":
            case "getTaskDragDisplacementFactor":
            case "getTaskDismissVerticalDirection":
            case "getTaskDismissLength":
            case "getTaskLaunchLength":
            case "extendRectForPrimaryTranslation":
            case "extendRectForSecondaryTranslation":
                return true;
            default:
                return false;
        }
    }

    private static boolean layoutNativeTasks(ViewGroup root, Class<?> task,
            int[] pageScrolls, boolean place) {
        final int count = root.getChildCount();
        final List<View> cards = new ArrayList<>();
        View clearAll = null;

        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            if (task.isInstance(child)) cards.add(child);
            else if (child.getClass().getName().endsWith(".ClearAllButton")) clearAll = child;
        }

        int gap = dp(root, 8);
        int width = (root.getWidth() - gap * 3) / 2;
        int rowHeight = 0;
        for (View card : cards)
            rowHeight = Math.max(rowHeight, card.getMeasuredHeight());
        if (width <= 0 || (rowHeight == 0 && !cards.isEmpty())) return false;

        int rowStep = rowHeight + gap;
        int rows = (cards.size() + 1) / 2;
        int contentHeight = gap + rows * rowStep + gap;
        if (clearAll != null)
            contentHeight += clearAll.getMeasuredHeight() + gap;
        int maxScroll = Math.max(0, contentHeight - root.getHeight());

        // Existing TaskViews are direct children of RecentsView. Do not reparent,
        // recreate previews or change task snapshot data.
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            int col = i % 2;
            int row = i / 2;
            int x = gap + col * (width + gap)
                    + (width - card.getMeasuredWidth()) / 2
                    - Math.round(card.getTranslationX());
            int y = gap + row * rowStep
                    - Math.round(card.getTranslationY());
            if (place) card.layout(x, y, x + card.getMeasuredWidth(),
                    y + card.getMeasuredHeight());
        }
        if (clearAll != null && place) {
            int x = (root.getWidth() - clearAll.getMeasuredWidth()) / 2;
            int y = gap + rows * rowStep;
            clearAll.layout(x, y, x + clearAll.getMeasuredWidth(),
                    y + clearAll.getMeasuredHeight());
        }

        // Native PagedView expects identical page scrolls for each pair.
        // Avoid horizontal scroll suppression: this is now a vertical axis.
        boolean changed = false;
        int taskIndex = 0;
        for (int i = 0; i < count; i++) {
            View child = root.getChildAt(i);
            int scroll;
            if (task.isInstance(child)) {
                scroll = Math.min((taskIndex / 2) * rowStep, maxScroll);
                taskIndex++;
            } else if (child == clearAll) {
                scroll = maxScroll;
            } else {
                scroll = Math.min((taskIndex / 2) * rowStep, maxScroll);
            }
            if (pageScrolls[i] != scroll) {
                pageScrolls[i] = scroll;
                changed = true;
            }
        }
        return changed;
    }

    private static int dp(View view, int size) {
        return Math.round(size * view.getResources().getDisplayMetrics().density);
    }
}
