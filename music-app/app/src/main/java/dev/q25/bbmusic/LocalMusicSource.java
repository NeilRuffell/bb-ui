package dev.q25.bbmusic;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Folder-scoped local music index using the Storage Access Framework.
 *
 * Important privacy invariant: never query MediaStore's device-wide tables,
 * enumerate storage roots, or walk directories outside the persisted trees
 * explicitly selected in Settings > Add folders.
 *
 * Queries and audio metadata reads run on one worker thread. The UI owns
 * how the resulting list is presented; no media playback dependency exists.
 */
final class LocalMusicSource {
    private static final String TAG = "BBMusic-Local";
    private final Context context;
    private final ContentResolver resolver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private volatile int latestRequest;

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
        this.context = context.getApplicationContext();
        resolver = this.context.getContentResolver();
    }

    void load(Listener listener) {
        if (closed) return;
        final int request = ++latestRequest;
        worker.execute(() -> {
            try {
                List<Song> songs = scanSelectedFolders(request);
                main.post(() -> {
                    if (!closed && request == latestRequest) listener.onLoaded(songs);
                });
            } catch (RuntimeException exception) {
                Log.e(TAG, "Selected-folder scan failed", exception);
                main.post(() -> {
                    if (!closed && request == latestRequest) {
                        listener.onError("Cannot read selected music folders");
                    }
                });
            }
        });
    }

    void invalidate() {
        // Discard in-flight results after a folder is added.
        latestRequest++;
    }

    private List<Song> scanSelectedFolders(int request) {
        // Only the selected and currently authorized document trees are used.
        // If no folder was selected there are zero queries to shared storage.
        List<Uri> trees = MusicFolders.selectedTrees(context);
        if (trees.isEmpty()) return Collections.emptyList();

        ArrayList<Song> songs = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int readableTrees = 0;
        for (Uri tree : trees) {
            if (closed || request != latestRequest) break;
            try {
                scanTree(tree, request, visited, songs);
                readableTrees++;
            } catch (SecurityException exception) {
                Log.w(TAG, "Lost access to selected folder " + tree, exception);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to scan selected folder " + tree, exception);
            }
        }
        if (!closed && request == latestRequest && readableTrees == 0) {
            throw new IllegalStateException("All selected music folders were inaccessible");
        }
        songs.sort(Comparator.comparing((Song song) -> song.title,
                String.CASE_INSENSITIVE_ORDER).thenComparing(song -> song.uri.toString()));
        Log.i(TAG, "Indexed music files in selected folders: " + songs.size()
                + ", readable roots: " + readableTrees);
        return Collections.unmodifiableList(songs);
    }

    private void scanTree(Uri tree, int request, Set<String> visited,
            List<Song> songs) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        ArrayDeque<String> directories = new ArrayDeque<>();
        directories.add(rootId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        while (!directories.isEmpty() && !closed && request == latestRequest) {
            String directoryId = directories.removeFirst();
            String visitKey = tree.getAuthority() + "|" + directoryId;
            if (!visited.add(visitKey)) continue;
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, directoryId);
            try (Cursor cursor = resolver.query(childrenUri, projection,
                    null, null, null)) {
                if (cursor == null) {
                    Log.w(TAG, "No child list for authorized directory " + childrenUri);
                    continue;
                }
                int idColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE);

                while (cursor.moveToNext() && !closed && request == latestRequest) {
                    String docId = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    String mime = cursor.getString(mimeColumn);
                    if (docId == null || docId.isEmpty()) continue;

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        directories.addLast(docId);
                    } else if (isAudio(name, mime)) {
                        // buildDocumentUriUsingTree can only use permissions
                        // granted for the currently selected subtree.
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(
                                tree, docId);
                        Song song = inspectAudio(fileUri, name);
                        if (song != null) songs.add(song);
                    }
                }
            } catch (SecurityException exception) {
                Log.w(TAG, "No access to directory within selected tree", exception);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot enumerate directory within selected tree", exception);
            }
        }
    }

    private static boolean isAudio(String filename, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
            return false;
        }
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".flac")
                || lower.endsWith(".ogg") || lower.endsWith(".opus")
                || lower.endsWith(".wav") || lower.endsWith(".wma")
                || lower.endsWith(".aiff") || lower.endsWith(".aif")
                || lower.endsWith(".alac") || lower.endsWith(".ape")
                || lower.endsWith(".amr");
    }

    private Song inspectAudio(Uri fileUri, String filename) {
        String fallbackTitle = filename == null ? "Unknown Title" : filename;
        int dot = fallbackTitle.lastIndexOf('.');
        if (dot > 0) fallbackTitle = fallbackTitle.substring(0, dot);

        String title = fallbackTitle;
        String artist = "Unknown Artist";
        String album = "Unknown Album";
        String genre = "Unknown Genre";
        long duration = 0L;
        int track = 0;

        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            // Only a URI returned from a selected DocumentsProvider tree.
            metadata.setDataSource(context, fileUri);
            title = name(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE), fallbackTitle);
            artist = name(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST), artist);
            album = name(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM), album);
            genre = name(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_GENRE), genre);
            duration = parseLong(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            track = parseTrack(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
        } catch (RuntimeException exception) {
            // Audio extensions without readable tags remain browseable by
            // filename and their selected-folder content URI.
            Log.d(TAG, "Metadata unavailable for " + fileUri, exception);
        } finally {
            metadata.release();
        }
        long songId = fileUri.toString().hashCode() & 0xffffffffL;
        return new Song(songId, fileUri, title, artist, album, genre,
                -1L, -1L, duration, track);
    }

    private static String name(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static long parseLong(String value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private static int parseTrack(String value) {
        if (value == null) return 0;
        int separator = value.indexOf('/');
        return (int) Math.min(Integer.MAX_VALUE,
                parseLong(separator >= 0 ? value.substring(0, separator) : value));
    }

    void close() {
        closed = true;
        invalidate();
        worker.shutdownNow();
    }
}
