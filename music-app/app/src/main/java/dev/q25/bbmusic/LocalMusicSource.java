package dev.q25.bbmusic;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Standalone on-device music catalog via Android's public MediaStore API.
 *
 * Does not depend on Symfonium, playback services, root, Vector or BB UI.
 * Only the caller requests READ_MEDIA_AUDIO; this class never prompts.
 * Metadata scanning stays off the main/UI thread.
 */
final class LocalMusicSource {
    private static final String TAG = "BBMusic-Local";
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private int latestRequest;

    interface Listener {
        void onLoaded(List<Song> songs);
        void onError(String reason);
    }

    static final class Song {
        final long id;
        final Uri uri;
        final String title;
        final String artist;
        final String album;
        final String genre;
        final long artistId;
        final long albumId;
        final long durationMs;
        final int trackNumber;

        Song(long id, Uri uri, String title, String artist, String album,
                String genre, long artistId, long albumId, long durationMs,
                int trackNumber) {
            this.id = id;
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.genre = genre;
            this.artistId = artistId;
            this.albumId = albumId;
            this.durationMs = durationMs;
            this.trackNumber = trackNumber;
        }
    }

    LocalMusicSource(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
    }

    void load(Listener listener) {
        if (closed) return;
        final int request = ++latestRequest;
        worker.execute(() -> {
            try {
                final List<Song> songs = scan();
                main.post(() -> {
                    if (!closed && request == latestRequest) listener.onLoaded(songs);
                });
            } catch (SecurityException exception) {
                Log.w(TAG, "Audio permission missing or revoked", exception);
                main.post(() -> {
                    if (!closed && request == latestRequest) {
                        listener.onError("Audio access denied");
                    }
                });
            } catch (RuntimeException exception) {
                Log.e(TAG, "MediaStore query failed", exception);
                main.post(() -> {
                    if (!closed && request == latestRequest) {
                        listener.onError("Cannot read the device's music library");
                    }
                });
            }
        });
    }

    private List<Song> scan() {
        // Merged view of indexed audio across mounted external volumes
        // (including the device's emulated internal shared storage).
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.MediaColumns.GENRE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK
        };
        ArrayList<Song> found = new ArrayList<>();
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        try (Cursor cursor = resolver.query(collection, projection, selection,
                null, MediaStore.Audio.Media.TITLE + " ASC")) {
            if (cursor == null) {
                throw new IllegalStateException("MediaStore returned a null audio cursor");
            }
            int id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int artistId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID);
            int albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int genre = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENRE);
            int duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);
            while (!closed && cursor.moveToNext()) {
                long mediaId = cursor.getLong(id);
                found.add(new Song(
                        mediaId, ContentUris.withAppendedId(collection, mediaId),
                        name(cursor.getString(title), "Unknown Title"),
                        name(cursor.getString(artist), "Unknown Artist"),
                        name(cursor.getString(album), "Unknown Album"),
                        name(cursor.getString(genre), "Unknown Genre"),
                        cursor.isNull(artistId) ? -1L : cursor.getLong(artistId),
                        cursor.isNull(albumId) ? -1L : cursor.getLong(albumId),
                        cursor.isNull(duration) ? 0L : cursor.getLong(duration),
                        cursor.isNull(track) ? 0 : cursor.getInt(track)));
            }
        }
        found.sort(Comparator.comparing((Song song) -> song.title,
                String.CASE_INSENSITIVE_ORDER).thenComparingLong(song -> song.id));
        Log.i(TAG, "Indexed local music tracks: " + found.size());
        return Collections.unmodifiableList(found);
    }

    private static String name(String name, String fallback) {
        if (name == null) return fallback;
        String trimmed = name.trim();
        return trimmed.isEmpty() || "<unknown>".equalsIgnoreCase(trimmed)
                ? fallback : trimmed;
    }

    void close() {
        closed = true;
        latestRequest++;
        worker.shutdownNow();
    }
}
