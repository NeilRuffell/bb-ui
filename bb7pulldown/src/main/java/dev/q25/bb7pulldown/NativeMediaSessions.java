package dev.q25.bb7pulldown;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

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
        final String title;
        final String artist;
        final boolean playing;
        final boolean canPrevious;
        final boolean canPlayPause;
        final boolean canNext;
        final boolean canSeek;
        final long durationMs;
        final long positionMs;
        final long positionUpdatedAtMs;
        final float playbackSpeed;
        final boolean advancing;

        Entry(MediaSession.Token token, String packageName, String title, String artist,
                boolean playing, boolean canPrevious, boolean canPlayPause, boolean canNext,
                boolean canSeek, long durationMs, long positionMs,
                long positionUpdatedAtMs, float playbackSpeed, boolean advancing) {
            this.token = token;
            this.packageName = packageName;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
            this.canPrevious = canPrevious;
            this.canPlayPause = canPlayPause;
            this.canNext = canNext;
            this.canSeek = canSeek;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.positionUpdatedAtMs = positionUpdatedAtMs;
            this.playbackSpeed = playbackSpeed;
            this.advancing = advancing;
        }

        long positionAt(long elapsedRealtimeMs) {
            return MediaSeekPolicy.positionAt(positionMs, durationMs,
                    positionUpdatedAtMs, playbackSpeed, advancing, elapsedRealtimeMs);
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
                @Override public void onMetadataChanged(MediaMetadata metadata) {
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
                MediaMetadata metadata = controller.getMetadata();
                String title = metadataValue(metadata, MediaMetadata.METADATA_KEY_TITLE);
                if (title.isEmpty()) {
                    title = metadataValue(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                }
                String artist = metadataValue(metadata, MediaMetadata.METADATA_KEY_ARTIST);
                if (artist.isEmpty()) {
                    artist = metadataValue(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                }
                long durationMs = metadata == null ? 0
                        : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                entries.add(new Entry(controller.getSessionToken(), controller.getPackageName(),
                        title, artist, playing,
                        (actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0,
                        (actions & (PlaybackState.ACTION_PLAY_PAUSE
                                | (playing ? PlaybackState.ACTION_PAUSE
                                        : PlaybackState.ACTION_PLAY))) != 0,
                        (actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0,
                        durationMs > 0 && (actions & PlaybackState.ACTION_SEEK_TO) != 0,
                        durationMs, state.getPosition(), state.getLastPositionUpdateTime(),
                        state.getPlaybackSpeed(), state.getState() == PlaybackState.STATE_PLAYING));
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot read native playback state", error);
            }
        }
        listener.onSessionsChanged(Collections.unmodifiableList(entries));
    }

    private static String metadataValue(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        CharSequence value = metadata.getText(key);
        return value == null ? "" : value.toString().trim();
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
                    if (playing && (actions & PlaybackState.ACTION_PAUSE) != 0) {
                        transport.pause();
                    } else if (!playing && (actions & PlaybackState.ACTION_PLAY) != 0) {
                        transport.play();
                    } else if ((actions & PlaybackState.ACTION_PLAY_PAUSE) != 0) {
                        // Some sessions advertise only the native toggle action.
                        controller.dispatchMediaButtonEvent(new KeyEvent(
                                KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                        controller.dispatchMediaButtonEvent(new KeyEvent(
                                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                    } else {
                        return false;
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

    /** Seek exclusively through the current Android media session, when permitted. */
    boolean seekTo(MediaSession.Token token, long positionMs) {
        if (!started || token == null) return false;
        MediaController controller = controllers.get(token);
        if (controller == null) return false;
        try {
            PlaybackState state = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();
            long duration = metadata == null ? 0
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (state == null || duration <= 0
                    || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) {
                return false;
            }
            controller.getTransportControls().seekTo(
                    MediaSeekPolicy.clamp(positionMs, duration));
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Native media seek command failed", error);
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
