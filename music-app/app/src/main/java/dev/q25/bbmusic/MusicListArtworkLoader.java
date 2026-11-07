package dev.q25.bbmusic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Asynchronously extracts small embedded cover thumbnails for library rows.
 *
 * Request keys represent albums; candidates are tracks from that album in
 * one or more explicitly selected Storage Access Framework folder trees.
 * This class never queries MediaStore, sibling folders or external services.
 *
 * ListView recycling is safe: the Activity checks each ImageView's binding
 * key when a completed request returns.
 */
final class MusicListArtworkLoader {
    private static final String TAG = "BBMusic-ListArt";
    private static final int THUMBNAIL_MAX_PX = 160;

    interface Listener {
        void onLoaded(Bitmap cover);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(12 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) {
            return Math.max(1, image.getByteCount() / 1024);
        }
    };
    // Owned exclusively by the main thread.
    private final Map<String, List<Listener>> waiting = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private int generation;
    private boolean closed;

    MusicListArtworkLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    void request(String key, List<Uri> albumTracks, Listener listener) {
        if (closed || key == null || albumTracks == null || albumTracks.isEmpty()) {
            listener.onLoaded(null);
            return;
        }
        Bitmap cached = covers.get(key);
        if (cached != null) {
            listener.onLoaded(cached);
            return;
        }
        if (missing.contains(key)) {
            listener.onLoaded(null);
            return;
        }
        List<Listener> callbacks = waiting.get(key);
        if (callbacks != null) {
            callbacks.add(listener);
            return;
        }
        callbacks = new ArrayList<>();
        callbacks.add(listener);
        waiting.put(key, callbacks);

        final int requestGeneration = generation;
        final ArrayList<Uri> allowedCandidates = new ArrayList<>(albumTracks);
        workers.execute(() -> {
            Bitmap found = null;
            for (Uri uri : allowedCandidates) {
                if (Thread.currentThread().isInterrupted()) break;
                found = embeddedCover(uri);
                if (found != null) break;
            }
            final Bitmap result = found;
            main.post(() -> {
                if (closed || requestGeneration != generation) return;
                if (result != null) covers.put(key, result);
                else missing.add(key);
                List<Listener> listeners = waiting.remove(key);
                if (listeners == null) return;
                for (Listener callback : listeners) callback.onLoaded(result);
            });
        });
    }

    private Bitmap embeddedCover(Uri trackUri) {
        if (!"content".equals(trackUri.getScheme())) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            // Content URI was supplied by the selected-folder music scanner.
            retriever.setDataSource(context, trackUri);
            byte[] embedded = retriever.getEmbeddedPicture();
            if (embedded == null || embedded.length == 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(embedded, 0, embedded.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            int maxSide = Math.max(options.outWidth, options.outHeight);
            while (maxSide / options.inSampleSize > THUMBNAIL_MAX_PX) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(embedded, 0, embedded.length, options);
        } catch (RuntimeException exception) {
            Log.d(TAG, "Embedded cover unavailable for selected track", exception);
            return null;
        } catch (OutOfMemoryError error) {
            Log.w(TAG, "Skipping oversized embedded cover for selected track");
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException exception) {
                Log.w(TAG, "Cover retriever cleanup failed", exception);
            }
        }
    }

    /** Invalidates only in-memory artwork state after the selected trees change. */
    void clear() {
        generation++;
        covers.evictAll();
        missing.clear();
        waiting.clear();
    }

    void close() {
        if (closed) return;
        closed = true;
        clear();
        workers.shutdownNow();
    }
}
