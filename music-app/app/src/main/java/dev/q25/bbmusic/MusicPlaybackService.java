package dev.q25.bbmusic;

import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * BB Music's own playback engine. Media3 runs the player and its media
 * session here, not inside the Activity, Vector or another music application.
 * Only the app's explicitly selected document-tree URIs enter its queue.
 */
public final class MusicPlaybackService extends MediaSessionService {
    private static final String TAG = "BBMusic-Service";
    private ExoPlayer player;
    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        session = new MediaSession.Builder(this, player).build();
        Log.i(TAG, "Media3 playback service created");
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        Log.i(TAG, "Media3 playback session requested; available=" + (session != null));
        return session;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Media3 playback service destroyed");
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
