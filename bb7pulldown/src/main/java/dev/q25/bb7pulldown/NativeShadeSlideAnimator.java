package dev.q25.bb7pulldown;

import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.lang.reflect.Method;

/**
 * Visual motion for the EXISTING SystemUI NotificationShadeWindowView.
 *
 * Native shade expansion, input, notification data, QS tiles, and Back
 * remain owned by SystemUI. Translating the entire native window ensures
 * that stock shade children never peek through while the BB face slides.
 * The genuine status bar is in a different window and does not move.
 *
 * Opt-in during Q25 testing. Never permanently mutate native view state:
 * restore() is called on close, Home/screen-off and failed transitions.
 */
final class NativeShadeSlideAnimator {
    private static final String TAG = "BB7-ShadeSlide";
    private static final String PROP = "debug.bb7pulldown.native_slide.enabled";
    private static final long OPEN_MS = 220L;
    private static final long CLOSE_MS = 180L;

    private enum Phase { NONE, OPENING, OPEN, CLOSING }

    private static View animatedWindow;
    private static float originalY;
    private static float hiddenY;
    private static Phase phase = Phase.NONE;

    private NativeShadeSlideAnimator() {}

    static boolean prepareOpen(FrameLayout window) {
        if (!enabled() || window == null || !window.isAttachedToWindow()
                || !NativeShadeHostBridge.ownsNativeShadeWindow()) return false;
        restore();
        int screenHeight = window.getResources().getDisplayMetrics().heightPixels;
        int distance = NativeShadeSlidePolicy.travelDistance(window.getHeight(), screenHeight);
        originalY = window.getTranslationY();
        hiddenY = NativeShadeSlidePolicy.hiddenTranslation(originalY, distance);
        animatedWindow = window;
        phase = Phase.OPENING;
        window.animate().cancel();
        window.setTranslationY(hiddenY);
        Log.i(TAG, "Native shade slide prepared: distance=" + distance + "px");
        return true;
    }

    static boolean animateOpen(Runnable completed) {
        final View target = animatedWindow;
        if (target == null || phase != Phase.OPENING) return false;
        target.animate().translationY(originalY).setDuration(OPEN_MS)
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
        // Reverse an ongoing entrance from its CURRENT screen position.
        // Make stale entrance callbacks inert before canceling that animator.
        phase = Phase.CLOSING;
        target.animate().cancel();
        target.animate().translationY(hiddenY).setDuration(CLOSE_MS)
                .setInterpolator(new AccelerateInterpolator(1.1f))
                .withEndAction(() -> {
                    if (animatedWindow != target || phase != Phase.CLOSING) return;
                    Log.i(TAG, "Native shade slide-up completed");
                    completed.run();
                }).start();
        Log.i(TAG, "Native shade sliding up");
        return true;
    }

    static boolean isOpening() { return phase == Phase.OPENING; }

    static boolean isClosing() { return phase == Phase.CLOSING; }

    static boolean isActiveOn(View window) {
        return window != null && window == animatedWindow;
    }

    static void restore() {
        final View target = animatedWindow;
        if (target == null) return;
        // Clear state FIRST; a canceled animation must never run its
        // stale completion callback or dismiss a new shade session.
        animatedWindow = null;
        phase = Phase.NONE;
        target.animate().cancel();
        target.setTranslationY(originalY);
        Log.i(TAG, "Original native shade window position restored");
    }

    private static boolean enabled() {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method getBoolean = props.getMethod("getBoolean", String.class, boolean.class);
            return Boolean.TRUE.equals(getBoolean.invoke(null, PROP, false));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read slide enable property; using immediate shade", error);
            return false;
        }
    }
}
