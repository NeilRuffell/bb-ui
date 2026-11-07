package dev.q25.bbmusic;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/**
 * Independent port of the hardware confirm-key handler used by the BB UI
 * Quick Settings ListView: one short press on release, or one long press.
 */
final class TrackpadPressHandler {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TrackpadPressState state = new TrackpadPressState();
    private final Runnable click;
    private final Runnable longClick;
    private final Runnable timeout;

    TrackpadPressHandler(Runnable click, Runnable longClick) {
        this.click = click;
        this.longClick = longClick;
        timeout = () -> {
            if (state.fireLongPress()) this.longClick.run();
        };
    }

    static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (!isConfirmKey(code)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                if (state.isHeld()) cancel();
                if (state.press(code)) {
                    handler.postDelayed(timeout, ViewConfiguration.getLongPressTimeout());
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(timeout);
            if (event.isCanceled()) {
                state.reset();
            } else if (state.release(code)) {
                click.run();
            }
        } else if (event.getAction() != KeyEvent.ACTION_MULTIPLE) {
            cancel();
        }
        return true;
    }

    void cancel() {
        handler.removeCallbacks(timeout);
        state.reset();
    }
}
