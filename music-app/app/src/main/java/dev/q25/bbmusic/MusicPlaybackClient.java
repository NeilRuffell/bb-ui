package dev.q25.bbmusic;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * UI-side controller for BB Music's independent MediaSessionService.
 * A local selected-folder content URI is the only accepted queue source.
 * Disconnecting the Activity does not stop background audio playback.
 */
final class MusicPlaybackClient {
    private static final String TAG = "BBMusic-Playback";

    interface Listener {
        void onPlaybackChanged();
        void onPlaybackError(String message);
    }

    private final Context context;
    private final Listener listener;
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private boolean connected;
    // A failed controller future must never leave the player permanently stuck.
    private int connectionRetryCount;
    private List<MediaItem> pendingItems;
    private int pendingIndex;
    private boolean pendingShuffle;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) {
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                    || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                Log.i(TAG, "Player state=" + player.getPlaybackState()
                        + ", hasItem=" + (player.getCurrentMediaItem() != null)
                        + ", playWhenReady=" + player.getPlayWhenReady());
            }
            if (connected) listener.onPlaybackChanged();
        }

        @Override public void onPlayerError(PlaybackException error) {
            Log.e(TAG, "Selected-folder audio playback failed", error);
            if (connected) listener.onPlaybackError("Cannot play this audio file");
        }
    };

    MusicPlaybackClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void connect() {
        connected = true;
        if (controller != null || controllerFuture != null) return;
        try {
            SessionToken token = new SessionToken(context,
                    new ComponentName(context, MusicPlaybackService.class));
            final ListenableFuture<MediaController> future =
                    new MediaController.Builder(context, token).buildAsync();
            controllerFuture = future;
            Log.i(TAG, "Connecting Media3 controller");
            future.addListener(() -> {
                MediaController established;
                try {
                    established = future.get();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    onConnectionFailed(future, exception);
                    return;
                } catch (ExecutionException | RuntimeException exception) {
                    onConnectionFailed(future, exception);
                    return;
                }
                if (!connected || controllerFuture != future) {
                    // The Activity has stopped and released this generation.
                    return;
                }
                controller = established;
                connectionRetryCount = 0;
                controller.addListener(playerListener);
                Log.i(TAG, "Media3 controller connected; queuePending="
                        + (pendingItems != null));
                flushPendingQueue();
                listener.onPlaybackChanged();
            }, context.getMainExecutor());
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to initialize Media3 controller", exception);
            controllerFuture = null;
            listener.onPlaybackError("Cannot initialize BB Music player");
        }
    }

    private void onConnectionFailed(ListenableFuture<MediaController> future,
            Exception exception) {
        Log.e(TAG, "Media3 controller connection failed", exception);
        if (!connected || controllerFuture != future) return;
        controllerFuture = null;
        MediaController.releaseFuture(future);
        // A temporary startup/connection failure must not strand queued
        // music. One bounded retry is attempted; additional attempts require
        // another user-initiated play, preventing unbounded retry loops.
        if (pendingItems != null && connectionRetryCount++ == 0) {
            Log.w(TAG, "Retrying failed Media3 connection once");
            connect();
            return;
        }
        listener.onPlaybackError("Cannot connect to BB Music player");
    }

    private void flushPendingQueue() {
        if (controller == null || pendingItems == null) return;
        List<MediaItem> items = pendingItems;
        int index = pendingIndex;
        boolean shuffle = pendingShuffle;
        pendingItems = null;
        startQueue(items, index, shuffle);
    }

    void playSongs(List<LocalMusicSource.Song> songs, int index, boolean shuffle) {
        if (songs == null || songs.isEmpty() || index < 0 || index >= songs.size()) {
            listener.onPlaybackError("No selected-folder songs to play");
            return;
        }
        ArrayList<MediaItem> items = new ArrayList<>(songs.size());
        for (LocalMusicSource.Song song : songs) {
            if (song == null || song.uri == null
                    || !"content".equals(song.uri.getScheme())) {
                listener.onPlaybackError("Invalid selected-folder audio URI");
                return;
            }
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build();
            items.add(new MediaItem.Builder()
                    .setMediaId(song.uri.toString())
                    .setUri(song.uri)
                    .setMediaMetadata(metadata)
                    .build());
        }
        Log.i(TAG, "Play requested: queueSize=" + items.size() + ", index=" + index
                + ", controllerReady=" + (controller != null));
        // Store the request BEFORE connecting. An immediately completed
        // future must still find the queue waiting in the callback.
        pendingItems = items;
        pendingIndex = index;
        pendingShuffle = shuffle;
        if (controller != null) {
            flushPendingQueue();
        } else {
            // A previous failed future must not prevent this play request
            // from trying again.
            if (controllerFuture == null) connectionRetryCount = 0;
            connect();
        }
    }

    private void startQueue(List<MediaItem> songs, int index, boolean shuffle) {
        if (controller == null) return;
        try {
            Log.i(TAG, "Submitting Media3 playback queue: tracks=" + songs.size()
                    + ", index=" + index);
            controller.setShuffleModeEnabled(shuffle);
            controller.setRepeatMode(Player.REPEAT_MODE_OFF);
            controller.setMediaItems(songs, index, 0L);
            controller.prepare();
            controller.play();
            listener.onPlaybackChanged();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Cannot start local playback", exception);
            listener.onPlaybackError("Unable to play this audio file");
        }
    }

    MediaController controller() {
        return controller;
    }

    /**
     * The home Now Playing shortcut exists only while an actual track is
     * prepared/playing/paused. It never creates a queue or resumes playback.
     */
    boolean hasCurrentTrack() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            return false;
        }
        int state = controller.getPlaybackState();
        return state == Player.STATE_READY || state == Player.STATE_BUFFERING;
    }

    void playPause() {
        if (controller == null) return;
        if (controller.getPlayWhenReady()) controller.pause();
        else controller.play();
    }

    void previous() {
        if (controller != null) controller.seekToPreviousMediaItem();
    }

    void next() {
        if (controller != null) controller.seekToNextMediaItem();
    }

    void seek(long ms) {
        if (controller != null && controller.isCurrentMediaItemSeekable()) {
            controller.seekTo(ms);
        }
    }

    void toggleShuffle() {
        if (controller != null) {
            controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
        }
    }

    void toggleRepeat() {
        if (controller == null) return;
        int current = controller.getRepeatMode();
        controller.setRepeatMode(current == Player.REPEAT_MODE_OFF
                ? Player.REPEAT_MODE_ALL
                : current == Player.REPEAT_MODE_ALL
                ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    void disconnect() {
        connected = false;
        pendingItems = null;
        connectionRetryCount = 0;
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
    }
}
