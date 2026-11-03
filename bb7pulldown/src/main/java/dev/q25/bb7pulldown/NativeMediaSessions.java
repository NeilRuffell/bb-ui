package dev.q25.bb7pulldown;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses Android's own SystemUI-accessible media controllers and transport actions.
 * No media services, artificial playback state, or notification action parsing.
 * All callbacks and UI updates run on SystemUI's main thread.
 */
final class NativeMediaSessions {
    static final int PREVIOUS = 0;
    static final int PLAY_PAUSE = 1;
    static final int NEXT = 2;
    private static final String TAG = "BB7-Media";

    interface Listener {
        void onSessionsChanged(List<Entry> sessions);
    }

    static final class Entry {
        final MediaSession.Token token;
        final String packageName;
        final boolean playing;
        final boolean canPrevious;
        final boolean canPlayPause;
        final boolean canNext;

        Entry(MediaSession.Token token, String packageName, boolean playing,
                boolean canPrevious, boolean canPlayPause, boolean canNext) {
            this.token = token;
            this.packageName = packageName;
            this.playing = playing;
            this.canPrevious = canPrevious;
            this.canPlayPause = canPlayPause;
            this.canNext = canNext;
        }
    }

    private final MediaSessionManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<MediaSession.Token, MediaController> controllers = new LinkedHashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks =
            new LinkedHashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener activeListener =
            this::replaceControllers;
    private boolean started;

    NativeMediaSessions(Context context, Listener listener) {
        manager = (MediaSessionManager) context.getSystemService(
                Context.MEDIA_SESSION_SERVICE);
        this.listener = listener;
    }

    void start() {
        if (started) return;
        started = true;
        if (manager == null) {
            Log.w(TAG, "Android MediaSessionManager unavailable");
            listener.onSessionsChanged(Collections.emptyList());
            return;
        }
        try {
            manager.addOnActiveSessionsChangedListener(activeListener, null, handler);
            refresh();
        } catch (SecurityException error) {
            Log.w(TAG, "SystemUI media session access unavailable", error);
            stop();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to observe native media sessions", error);
            stop();
        }
    }

    /** Refresh when Android notifications change, as well as session membership. */
    void refresh() {
        if (!started || manager == null) return;
        try {
            replaceControllers(manager.getActiveSessions(null));
        } catch (SecurityException error) {
            Log.w(TAG, "Native media session query denied", error);
            publishEmpty();
        } catch (RuntimeException error) {
            Log.w(TAG, "Native media session query failed", error);
            publishEmpty();
        }
    }

    private void publishEmpty() {
        if (started) listener.onSessionsChanged(Collections.emptyList());
    }

    private void replaceControllers(List<MediaController> current) {
        if (!started) return;
        LinkedHashMap<MediaSession.Token, MediaController> next = new LinkedHashMap<>();
        if (current != null) {
            for (MediaController controller : current) {
                if (controller == null || controller.getSessionToken() == null) continue;
                MediaSession.Token token = controller.getSessionToken();
                MediaController existing = controllers.get(token);
                next.put(token, existing != null ? existing : controller);
            }
        }
        for (Map.Entry<MediaSession.Token, MediaController> old : controllers.entrySet()) {
            if (next.containsKey(old.getKey())) continue;
            MediaController.Callback callback = callbacks.remove(old.getKey());
            if (callback != null) {
                try {
                    old.getValue().unregisterCallback(callback);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to release inactive media controller callback", error);
                }
            }
        }
        for (Map.Entry<MediaSession.Token, MediaController> item : next.entrySet()) {
            if (callbacks.containsKey(item.getKey())) continue;
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) {
                    publish();
                }
                @Override public void onSessionDestroyed() {
                    refresh();
                }
            };
            try {
                item.getValue().registerCallback(callback, handler);
                callbacks.put(item.getKey(), callback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot observe media controller", error);
            }
        }
        controllers.clear();
        controllers.putAll(next);
        publish();
    }

    private void publish() {
        if (!started) return;
        Set<MediaSession.Token> visibleTokens = new LinkedHashSet<>(
                SystemUiNotificationObserver.mediaSessionTokensSnapshot());
        List<Entry> entries = new ArrayList<>();
        for (MediaController controller : controllers.values()) {
            if (!visibleTokens.contains(controller.getSessionToken())) continue;
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state == null) continue;
                boolean playing = isPlaying(state);
                long actions = state.getActions();
                entries.add(new Entry(controller.getSessionToken(), controller.getPackageName(),
                        playing,
                        (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0,
                        (actions & (PlaybackState.ACTION_PLAY_PAUSE
                                | (playing ? PlaybackState.ACTION_PAUSE
                                        : PlaybackState.ACTION_PLAY))) != 0,
                        (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0));
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot read native playback state", error);
            }
        }
        listener.onSessionsChanged(Collections.unmodifiableList(entries));
    }

    /** Dispatch only commands that the current native PlaybackState advertises. */
    boolean send(MediaSession.Token token, int action) {
        if (!started || token == null) return false;
        MediaController controller = controllers.get(token);
        if (controller == null) return false;
        try {
            PlaybackState state = controller.getPlaybackState();
            if (state == null) return false;
            long actions = state.getActions();
            MediaController.TransportControls transport = controller.getTransportControls();
            switch (action) {
                case PREVIOUS:
                    if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) == 0) return false;
                    transport.skipToPrevious();
                    return true;
                case PLAY_PAUSE:
                    boolean playing = isPlaying(state);
                    if (playing) {
                        if ((actions & (PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE)) == 0) return false;
                        transport.pause();
                    } else {
                        if ((actions & (PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PLAY_PAUSE)) == 0) return false;
                        transport.play();
                    }
                    return true;
                case NEXT:
                    if ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) == 0) return false;
                    transport.skipToNext();
                    return true;
                default:
                    return false;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Native media transport command failed", error);
            return false;
        }
    }

    private static boolean isPlaying(PlaybackState state) {
        int value = state.getState();
        return value == PlaybackState.STATE_PLAYING
                || value == PlaybackState.STATE_BUFFERING
                || value == PlaybackState.STATE_CONNECTING;
    }

    void stop() {
        if (!started) return;
        started = false;
        if (manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(activeListener);
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed to unregister media session listener", error);
            }
        }
        for (Map.Entry<MediaSession.Token, MediaController> item : controllers.entrySet()) {
            MediaController.Callback callback = callbacks.get(item.getKey());
            if (callback == null) continue;
            try {
                item.getValue().unregisterCallback(callback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed to unregister media controller", error);
            }
        }
        callbacks.clear();
        controllers.clear();
        // The panel is being removed; do not alter its focus during teardown.
    }
}
