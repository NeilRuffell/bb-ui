package dev.q25.bb7pulldown;

import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.KeyEvent;

import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostic-only, opt-in SystemUI shade lifecycle and physical Back timing.
 * NEVER sleeps, posts delayed closures, changes input handling, or animates.
 *
 * Logs use the monotonic uptimeMillis clock (also used by KeyEvent). The
 * NEXT_FRAME_CALLBACK is Choreographer's next frame callback, NOT proof of
 * actual rendered/presented pixels. This distinction is intentional.
 */
final class ShadeLatencyProbe {
    private static final String TAG = "BB7-ShadeTiming";
    private static final String PROP = "debug.bb7pulldown.shade_latency.enabled";
    private static final boolean ENABLED = enabled();
    private static int session;
    private static long openedAt;
    private static long closeStartedAt;
    private static long backDownEventAt;
    private static long backUpEventAt;

    private ShadeLatencyProbe() {}

    private static boolean enabled() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return Boolean.TRUE.equals(XposedHelpers.callStaticMethod(
                    properties, "getBoolean", PROP, false));
        } catch (Throwable error) {
            Log.w(TAG, "Cannot read timing diagnostic property; disabled", error);
            return false;
        }
    }

    static void open(boolean nativeShade, int page) {
        if (!ENABLED) return;
        session++;
        openedAt = SystemClock.uptimeMillis();
        closeStartedAt = 0L;
        backDownEventAt = 0L;
        backUpEventAt = 0L;
        mark("OPEN_BEGIN native=" + nativeShade + " page=" + page);
    }

    static void key(String source, KeyEvent event) {
        if (!ENABLED || event == null) return;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_ESCAPE) return;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return;
        long now = SystemClock.uptimeMillis();
        if (action == KeyEvent.ACTION_DOWN && backDownEventAt == 0L) {
            backDownEventAt = event.getEventTime();
        } else if (action == KeyEvent.ACTION_UP) {
            backUpEventAt = event.getEventTime();
        }
        mark("BACK_KEY source=" + source
                + " action=" + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                + " repeat=" + event.getRepeatCount()
                + " eventAgeMs=" + Math.max(0L, now - event.getEventTime())
                + " heldMs=" + Math.max(0L, event.getEventTime() - event.getDownTime()));
    }

    static void mark(String event) {
        if (!ENABLED) return;
        long now = SystemClock.uptimeMillis();
        String elapsedOpen = openedAt == 0 ? "na" : Long.toString(now - openedAt);
        String elapsedClose = closeStartedAt == 0
                ? "na" : Long.toString(now - closeStartedAt);
        String elapsedBackDown = backDownEventAt == 0
                ? "na" : Long.toString(now - backDownEventAt);
        String elapsedBackUp = backUpEventAt == 0
                ? "na" : Long.toString(now - backUpEventAt);
        Log.i(TAG, "s=" + session + " uptimeMs=" + now
                + " sinceOpenMs=" + elapsedOpen
                + " sinceCloseMs=" + elapsedClose
                + " sinceBackDownMs=" + elapsedBackDown
                + " sinceBackUpMs=" + elapsedBackUp
                + " " + event);
    }

    static void closeBegin() {
        if (!ENABLED) return;
        if (closeStartedAt == 0L) closeStartedAt = SystemClock.uptimeMillis();
        mark("CLOSE_BEGIN");
    }

    static void nextFrameCallback(String stage) {
        if (!ENABLED) return;
        final int s = session;
        final long scheduledAt = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            long now = SystemClock.uptimeMillis();
            Log.i(TAG, "s=" + s + " uptimeMs=" + now
                    + " afterScheduleMs=" + (now - scheduledAt)
                    + " callback=NEXT_FRAME_CALLBACK_AFTER_" + stage
                    + " (vsync callback; NOT actual pixel presentation)");
        });
    }
}
