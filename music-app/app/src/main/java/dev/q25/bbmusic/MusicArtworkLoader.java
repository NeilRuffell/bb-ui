package dev.q25.bbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded album art from exactly one selected-folder track's document URI.
 * Track changes invalidate old results; decoding never blocks the UI thread.
 * Does not scan any other files or folders.
 */
final class MusicArtworkLoader {
    private static final String TAG = "BBMusic-Artwork";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;
    private boolean closed;

    interface Listener {
        void onArtwork(Bitmap image);
    }

    MusicArtworkLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    void load(Uri uri, Listener listener) {
        int request = ++generation;
        if (closed || uri == null) {
            listener.onArtwork(null);
            return;
        }
        worker.execute(() -> {
            Bitmap result = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                byte[] picture = retriever.getEmbeddedPicture();
                if (picture != null && picture.length > 0) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                    int maxSide = Math.max(options.outWidth, options.outHeight);
                    options.inSampleSize = 1;
                    while (maxSide / options.inSampleSize > 600) {
                        options.inSampleSize *= 2;
                    }
                    options.inJustDecodeBounds = false;
                    result = BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
                }
            } catch (RuntimeException exception) {
                Log.d(TAG, "Embedded artwork unavailable for selected track", exception);
            } catch (OutOfMemoryError error) {
                Log.w(TAG, "Embedded artwork exceeds memory budget");
            } finally {
                try {
                    retriever.release();
                } catch (IOException | RuntimeException exception) {
                    Log.w(TAG, "Artwork retriever cleanup failed", exception);
                }
            }
            final Bitmap decoded = result;
            main.post(() -> {
                if (!closed && request == generation) listener.onArtwork(decoded);
            });
        });
    }

    void close() {
        closed = true;
        generation++;
        worker.shutdownNow();
    }
}
