package dev.q25.bbmusic;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

/**
 * Standalone BB Music UI placeholder.
 *
 * No list, transport bar, layout styling or navigation is established here.
 * The user-guided BBOS6 interface will be constructed separately.
 * Media integration is retained independently in SymfoniumSource.
 */
public final class MusicActivity extends Activity {
    private static final String TAG = "BBMusic";
    private SymfoniumSource musicSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        View blank = new View(this);
        blank.setBackgroundColor(Color.BLACK);
        setContentView(blank);

        musicSource = new SymfoniumSource(this, new SymfoniumSource.Listener() {
            @Override public void onConnectionChanged(boolean connected, String rootId) {
                Log.i(TAG, connected
                        ? "Symfonium connected, root=" + rootId
                        : "Symfonium disconnected or unavailable");
            }

            @Override public void onItems(String parentId, int page,
                    java.util.List<MediaBrowser.MediaItem> items) {
                Log.i(TAG, "Symfonium browse result " + parentId
                        + " page=" + page + " count=" + items.size());
            }

            @Override public void onBrowseError(String parentId, int page) {
                Log.w(TAG, "Symfonium browse error " + parentId + " page=" + page);
            }

            @Override public void onPlaybackChanged(MediaMetadata metadata,
                    PlaybackState state) {
                Log.d(TAG, "Symfonium playback state updated");
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        musicSource.connect();
    }

    @Override protected void onStop() {
        musicSource.disconnect();
        super.onStop();
    }
}
