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
    private List<MediaItem> pendingItems;
    private int pendingIndex;
    private boolean pendingShuffle;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) {
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
        if (connected) return;
        connected = true;
        SessionToken token = new SessionToken(context,
                new ComponentName(context, MusicPlaybackService.class));
        controllerFuture = new MediaController.Builder(context, token).buildAsync();
        final ListenableFuture<MediaController> future = controllerFuture;
        future.addListener(() -> {
            MediaController established;
            try {
                established = future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Controller connection interrupted", exception);
                if (connected && controllerFuture == future) {
                    listener.onPlaybackError("Player connection interrupted");
                }
                return;
            } catch (ExecutionException | RuntimeException exception) {
                Log.e(TAG, "Cannot connect to BB Music playback service", exception);
                if (connected && controllerFuture == future) {
                    listener.onPlaybackError("Cannot connect to BB Music player");
                }
                return;
            }
            if (!connected || controllerFuture != future) return;
            controller = established;
            controller.addListener(playerListener);
            if (pendingItems != null) {
                startQueue(pendingItems, pendingIndex, pendingShuffle);
                pendingItems = null;
            }
            listener.onPlaybackChanged();
        }, context.getMainExecutor());
    }

    void playSongs(List<LocalMusicSource.Song> songs, int index, boolean shuffle) {
        if (songs == null || songs.isEmpty() || index < 0 || index >= songs.size()) {
            listener.onPlaybackError("No selected-folder songs to play");
            return;
        }
        ArrayList<MediaItem> items = new ArrayList<>(songs.size());
        for (LocalMusicSource.Song song : songs) {
            // Never construct a file path or scan anything outside the
            // user-selected SAF trees; these were issued by LocalMusicSource.
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
        if (!connected) connect();
        if (controller == null) {
            pendingItems = items;
            pendingIndex = index;
            pendingShuffle = shuffle;
        } else {
            startQueue(items, index, shuffle);
        }
    }

    private void startQueue(List<MediaItem> songs, int index, boolean shuffle) {
        if (controller == null) return;
        try {
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

    void playPause() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
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
