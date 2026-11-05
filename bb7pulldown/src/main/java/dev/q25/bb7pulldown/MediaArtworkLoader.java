package dev.q25.bb7pulldown;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads native MediaMetadata artwork URIs without blocking SystemUI's UI thread.
 * Only local Android resolver URIs are opened; no independent network requests.
 * Obsolete results cannot replace the currently selected session's artwork.
 */
final class MediaArtworkLoader {
    private static final String TAG = "BB7-Media";
    private static final int MAX_DIMENSION = 1024;

    interface Listener {
        void onArtworkLoaded(Bitmap artwork);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bb-ui-media-artwork");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private int generation;
    private boolean closed;

    void load(Context context, String uriString, Listener listener) {
        if (closed) return;
        final int request = ++generation;
        final ContentResolver resolver = context.getApplicationContext().getContentResolver();
        executor.execute(() -> {
            Bitmap image = decode(resolver, uriString);
            main.post(() -> {
                if (!closed && request == generation) listener.onArtworkLoaded(image);
            });
        });
    }

    void cancel() {
        generation++;
    }

    void close() {
        closed = true;
        generation++;
        executor.shutdownNow();
    }

    static boolean supportedScheme(String scheme) {
        return "content".equalsIgnoreCase(scheme)
                || "android.resource".equalsIgnoreCase(scheme);
    }

    private static Bitmap decode(ContentResolver resolver, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            if (!supportedScheme(uri.getScheme())) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) return null;
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options scaled = new BitmapFactory.Options();
            scaled.inSampleSize = 1;
            while (bounds.outWidth / scaled.inSampleSize > MAX_DIMENSION
                    || bounds.outHeight / scaled.inSampleSize > MAX_DIMENSION) {
                scaled.inSampleSize *= 2;
            }
            try (InputStream stream = resolver.openInputStream(uri)) {
                return stream == null ? null : BitmapFactory.decodeStream(stream, null, scaled);
            }
        } catch (IOException | RuntimeException error) {
            Log.d(TAG, "Media session artwork URI unavailable", error);
            return null;
        } catch (OutOfMemoryError error) {
            Log.w(TAG, "Media session artwork decoding exceeded memory limit");
            return null;
        }
    }
}
