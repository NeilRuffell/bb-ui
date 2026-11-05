package dev.q25.trackpad.input;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/**
 * Reusable hardware confirm-key short/long press handler.
 *
 * Use from a focused surface's dispatchKeyEvent(), before its ordinary
 * confirm-key activation. Uses Android's real long-press timeout and does
 * not synthesize key events, clicks or focus changes. Also suitable for a
 * notification card or media item in future.
 */
public final class TrackpadPressHandler {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TrackpadPressState state = new TrackpadPressState();
    private final Runnable click;
    private final Runnable longClick;
    private final Runnable timeout;

    public TrackpadPressHandler(Runnable click, Runnable longClick) {
        this.click = click;
        this.longClick = longClick;
        this.timeout = () -> {
            if (state.fireLongPress()) this.longClick.run();
        };
    }

    public static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * Returns true for hardware confirm keys, whether handled or canceled.
     * Repeated DOWN events do not restart the timeout or invoke extra clicks.
     */
    public boolean handle(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isConfirmKey(keyCode)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // A dialog can consume the previous key-up event. Only a fresh
            // DOWN (repeatCount=0) arms a physical press; autorepeat must
            // never synthesize an additional click or long press.
            if (event.getRepeatCount() == 0) {
                if (state.isHeld()) cancel();
                if (state.press(keyCode)) {
                    handler.postDelayed(timeout, ViewConfiguration.getLongPressTimeout());
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(timeout);
            if (event.isCanceled()) {
                state.reset();
            } else if (state.release(keyCode)) {
                click.run();
            }
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE) {
            // Repeated confirmations are not separate presses.
        } else {
            cancel();
        }
        return true;
    }

    /** Call on focus loss, page change, detach or any navigation key. */
    public void cancel() {
        handler.removeCallbacks(timeout);
        state.reset();
    }
}
