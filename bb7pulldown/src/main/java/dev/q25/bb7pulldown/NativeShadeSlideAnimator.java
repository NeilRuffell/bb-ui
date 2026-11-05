package dev.q25.bb7pulldown;

import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.lang.reflect.Method;

/**
 * Position/gesture controller for the ONE existing SystemUI shade window.
 * Android continues to own expansion, native notifications, Back and QS.
 *
 * Touch directly adjusts the same window translation used by the ordinary
 * keyboard/trackpad animations; never create a separate surface or controller.
 */
final class NativeShadeSlideAnimator {
    private static final String TAG = "BB7-ShadeSlide";
    private static final String PROP = "debug.bb7pulldown.native_slide.enabled";
    private static final String DRAG_PROP = "debug.bb7pulldown.native_drag.enabled";
    private static final long OPEN_MS = 220L;
    private static final long CLOSE_MS = 180L;

    private enum Phase { NONE, OPENING, DRAG_OPEN, OPEN, DRAG_CLOSE, CLOSING }

    private static View animatedWindow;
    private static float originalY;
    private static float hiddenY;
    private static Phase phase = Phase.NONE;

    private NativeShadeSlideAnimator() {}

    static boolean prepareOpen(FrameLayout window, boolean byTouch) {
        if (!enabled() || window == null || !window.isAttachedToWindow()
                || !NativeShadeHostBridge.ownsNativeShadeWindow()) return false;
        restore();
        int screenHeight = window.getResources().getDisplayMetrics().heightPixels;
        int distance = NativeShadeSlidePolicy.travelDistance(window.getHeight(), screenHeight);
        originalY = window.getTranslationY();
        hiddenY = NativeShadeSlidePolicy.hiddenTranslation(originalY, distance);
        animatedWindow = window;
        phase = byTouch && touchEnabled() ? Phase.DRAG_OPEN : Phase.OPENING;
        window.animate().cancel();
        window.setTranslationY(hiddenY);
        Log.i(TAG, "Native shade slide prepared: distance=" + distance
                + "px touch=" + (phase == Phase.DRAG_OPEN));
        return true;
    }

    static boolean animateOpen(Runnable completed) {
        final View target = animatedWindow;
        if (target == null || phase != Phase.OPENING) return false;
        long duration = remainingDuration(target.getTranslationY(), originalY, OPEN_MS);
        target.animate().cancel();
        target.animate().translationY(originalY).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    if (animatedWindow != target || phase != Phase.OPENING) return;
                    phase = Phase.OPEN;
                    Log.i(TAG, "Native shade slide-down completed");
                    completed.run();
                }).start();
        Log.i(TAG, "Native shade sliding down");
        return true;
    }

    static boolean animateClose(Runnable completed) {
        final View target = animatedWindow;
        if (target == null || phase == Phase.CLOSING || phase == Phase.NONE) {
            return false;
        }
        phase = Phase.CLOSING;
        long duration = remainingDuration(target.getTranslationY(), hiddenY, CLOSE_MS);
        target.animate().cancel();
        target.animate().translationY(hiddenY).setDuration(duration)
                .setInterpolator(new AccelerateInterpolator(1.1f))
                .withEndAction(() -> {
                    if (animatedWindow != target || phase != Phase.CLOSING) return;
                    Log.i(TAG, "Native shade slide-up completed");
                    completed.run();
                }).start();
        Log.i(TAG, "Native shade sliding up");
        return true;
    }

    /** Called from the real native status bar before releasing a touch-open. */
    static void updateTouchOpen(float fingerTravel) {
        if (animatedWindow == null || phase != Phase.DRAG_OPEN) return;
        float fraction = NativeShadeDragPolicy.fraction(fingerTravel, distance());
        animatedWindow.setTranslationY(hiddenY + fraction * distance());
    }

    static boolean finishTouchOpen(float velocityY, boolean canceled,
            Runnable opened, Runnable closed) {
        if (animatedWindow == null || phase != Phase.DRAG_OPEN) return false;
        float fraction = NativeShadeDragPolicy.fraction(
                animatedWindow.getTranslationY() - hiddenY, distance());
        if (NativeShadeDragPolicy.shouldOpen(fraction, velocityY, canceled)) {
            phase = Phase.OPENING;
            Log.i(TAG, "Touch-open released: completing down");
            return animateOpen(opened);
        }
        Log.i(TAG, "Touch-open released: returning up");
        return animateClose(closed);
    }

    static boolean canDragClose() {
        return animatedWindow != null && phase == Phase.OPEN;
    }

    static boolean beginTouchClose() {
        if (!canDragClose()) return false;
        phase = Phase.DRAG_CLOSE;
        animatedWindow.animate().cancel();
        return true;
    }

    static void updateTouchClose(float fingerTravelUp) {
        if (animatedWindow == null || phase != Phase.DRAG_CLOSE) return;
        float fraction = NativeShadeDragPolicy.fraction(fingerTravelUp, distance());
        animatedWindow.setTranslationY(originalY - fraction * distance());
    }

    static boolean finishTouchClose(float velocityY, boolean canceled,
            Runnable closed, Runnable reopened) {
        if (animatedWindow == null || phase != Phase.DRAG_CLOSE) return false;
        float fraction = NativeShadeDragPolicy.fraction(
                originalY - animatedWindow.getTranslationY(), distance());
        if (NativeShadeDragPolicy.shouldClose(fraction, velocityY, canceled)) {
            Log.i(TAG, "Touch-close released: completing up");
            return animateClose(closed);
        }
        phase = Phase.OPENING;
        Log.i(TAG, "Touch-close released: returning down");
        return animateOpen(reopened);
    }

    private static float distance() {
        return Math.max(1f, originalY - hiddenY);
    }

    private static long remainingDuration(float from, float to, long fullDuration) {
        float remaining = NativeShadeDragPolicy.fraction(Math.abs(to - from), distance());
        return Math.max(65L, Math.round(fullDuration * remaining));
    }

    static boolean isOpening() {
        return phase == Phase.OPENING || phase == Phase.DRAG_OPEN;
    }

    static boolean isTouchOpening() { return phase == Phase.DRAG_OPEN; }

    static boolean isClosing() {
        return phase == Phase.CLOSING || phase == Phase.DRAG_CLOSE;
    }

    static boolean isActiveOn(View window) {
        return window != null && window == animatedWindow;
    }

    static void restore() {
        final View target = animatedWindow;
        if (target == null) return;
        animatedWindow = null;
        phase = Phase.NONE;
        target.animate().cancel();
        target.setTranslationY(originalY);
        Log.i(TAG, "Original native shade window position restored");
    }

    static boolean touchEnabled() {
        return enabled() && readBoolean(DRAG_PROP, true);
    }

    private static boolean enabled() {
        return readBoolean(PROP, false);
    }

    private static boolean readBoolean(String name, boolean defaultValue) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method method = props.getMethod("getBoolean", String.class, boolean.class);
            return Boolean.TRUE.equals(method.invoke(null, name, defaultValue));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read " + name + "; using disabled touch slide", error);
            return false;
        }
    }
}
