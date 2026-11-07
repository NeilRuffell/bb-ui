package dev.q25.bbmusic;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
    // Rebuild previously indexed libraries once after introducing actual
    // audio-track validation. No database schema/data reset is required.
    private static final String INDEX_RULES_VERSION = "verified-audio-v2:";
    private final Context context;
    private final ContentResolver resolver;
    private final MusicLibraryDatabase libraryDatabase;
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
        final Uri sourceTree;
        final String title;
        final String artist;
        final String album;
        final String genre;
        final long artistId;
        final long albumId;
        final long durationMs;
        final int trackNumber;

        Song(long id, Uri uri, Uri sourceTree, String title, String artist, String album,
                String genre, long artistId, long albumId, long durationMs,
                int trackNumber) {
            this.id = id;
            this.uri = uri;
            this.sourceTree = sourceTree;
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
        libraryDatabase = MusicLibraryDatabase.open(this.context);
    }

    /** Read the durable index; scan only if the selected folder set changed. */
    void load(Listener listener) {
        request(false, listener);
    }

    /** Explicitly rebuild metadata after music files are added or changed. */
    void refresh(Listener listener) {
        request(true, listener);
    }

    private void request(boolean forceScan, Listener listener) {
        if (closed) return;
        final int request = ++latestRequest;
        worker.execute(() -> {
            try {
                List<Uri> trees = MusicFolders.selectedTrees(context);
                String key = selectionKey(trees);
                MusicLibraryDatabase.LibraryDao dao = libraryDatabase.libraryDao();
                MusicLibraryDatabase.ScanRow state = dao.scanState();
                List<Song> songs;

                if (!forceScan && state != null
                        && key.equals(state.selectedTreesKey)) {
                    // Do not expose metadata belonging to a tree whose SAF
                    // grant has been revoked since a prior app launch.
                    Set<String> authorized = new HashSet<>();
                    for (Uri tree : trees) authorized.add(tree.toString());
                    ArrayList<Song> cached = new ArrayList<>();
                    for (MusicLibraryDatabase.TrackRow row : dao.tracks()) {
                        if (!authorized.contains(row.sourceTree)) continue;
                        Uri uri = Uri.parse(row.uri);
                        if (!"content".equals(uri.getScheme())) continue;
                        cached.add(new Song(row.id, uri, Uri.parse(row.sourceTree),
                                row.title, row.artist, row.album, row.genre,
                                row.artistId, row.albumId, row.durationMs,
                                row.trackNumber));
                    }
                    // Match the scanner's Java sort exactly on every launch.
                    cached.sort(Comparator.comparing((Song song) -> song.title,
                            String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(song -> song.uri.toString()));
                    songs = Collections.unmodifiableList(cached);
                    Log.i(TAG, "Loaded persistent music index: " + songs.size());
                } else {
                    // First use, folder change or explicit Settings refresh.
                    // Scanner reads only user-selected SAF trees, never MediaStore.
                    songs = scanSelectedFolders(request);
                    if (closed || request != latestRequest) return;
                    ArrayList<MusicLibraryDatabase.TrackRow> records =
                            new ArrayList<>(songs.size());
                    for (Song song : songs) {
                        MusicLibraryDatabase.TrackRow row =
                                new MusicLibraryDatabase.TrackRow();
                        row.id = song.id;
                        row.uri = song.uri.toString();
                        row.sourceTree = song.sourceTree.toString();
                        row.title = song.title;
                        row.artist = song.artist;
                        row.album = song.album;
                        row.genre = song.genre;
                        row.artistId = song.artistId;
                        row.albumId = song.albumId;
                        row.durationMs = song.durationMs;
                        row.trackNumber = song.trackNumber;
                        records.add(row);
                    }
                    MusicLibraryDatabase.ScanRow next =
                            new MusicLibraryDatabase.ScanRow();
                    next.selectedTreesKey = key;
                    // The new catalog and its scan marker commit atomically.
                    // A failed refresh never wipes the previous good snapshot.
                    libraryDatabase.runInTransaction(() -> {
                        if (closed || request != latestRequest) return;
                        dao.deleteAllTracks();
                        dao.insertTracks(records);
                        dao.setScanState(next);
                    });
                }
                main.post(() -> {
                    if (!closed && request == latestRequest) {
                        listener.onLoaded(songs);
                    }
                });
            } catch (RuntimeException exception) {
                Log.e(TAG, "Music library load failed", exception);
                main.post(() -> {
                    if (!closed && request == latestRequest) {
                        listener.onError("Cannot read selected music folders");
                    }
                });
            }
        });
    }

    /** Length-prefixed sorted URI list; an empty scanned library is valid. */
    private static String selectionKey(List<Uri> trees) {
        ArrayList<String> sorted = new ArrayList<>(trees.size());
        for (Uri tree : trees) sorted.add(tree.toString());
        Collections.sort(sorted);
        StringBuilder key = new StringBuilder();
        for (String tree : sorted) {
            key.append(tree.length()).append(':').append(tree);
        }
        return INDEX_RULES_VERSION + key;
    }

    void invalidate() {
        // Discard in-flight scans after a selection changes.
        latestRequest++;
    }

    /**
     * Erase metadata for a removed folder even if the next scan fails or the
     * user immediately closes the Activity. Queue it ahead of the rescan.
     */
    void forgetRemovedTree(Uri tree, boolean noAuthorizedTreesRemain) {
        if (closed) return;
        worker.execute(() -> {
            try {
                MusicLibraryDatabase.LibraryDao dao = libraryDatabase.libraryDao();
                libraryDatabase.runInTransaction(() -> {
                    if (noAuthorizedTreesRemain) {
                        dao.deleteAllTracks();
                    } else {
                        dao.deleteTracksFromTree(tree.toString());
                    }
                    // A failed subsequent rescan cannot validate old rows.
                    dao.deleteScanState();
                });
            } catch (RuntimeException exception) {
                Log.e(TAG, "Cannot remove deleted folder's cached metadata",
                        exception);
            }
        });
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
                throw exception;
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to scan selected folder " + tree, exception);
                throw exception;
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
                    throw new IllegalStateException(
                            "No child list for authorized directory " + childrenUri);
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
                        Song song = inspectAudio(tree, fileUri, name);
                        if (song != null) {
                            songs.add(song);
                        } else if (!hasAudioExtension(name)
                                && isBrowsableDirectory(tree, docId)) {
                            // Some document providers misreport a child
                            // directory as audio/*. Keep walking its actual
                            // children rather than losing nested songs.
                            directories.addLast(docId);
                            Log.i(TAG, "Traversing misclassified music folder: " + name);
                        }
                    }
                }
            } catch (SecurityException exception) {
                Log.w(TAG, "No access to directory within selected tree", exception);
                throw exception;
            } catch (RuntimeException exception) {
                Log.w(TAG, "Cannot enumerate directory within selected tree", exception);
                throw exception;
            }
        }
    }

    private static boolean isAudio(String filename, String mime) {
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) return false;
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/")) {
            return false;
        }
        return hasAudioExtension(filename);
    }

    private static boolean hasAudioExtension(String filename) {
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

    /**
     * Probe children only for a failed audio entry with no audio extension.
     * A successful SAF child query identifies a directory despite a bad MIME
     * label, while unselected folders are never queried.
     */
    private boolean isBrowsableDirectory(Uri tree, String documentId) {
        Uri childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, documentId);
        try (Cursor cursor = resolver.query(childUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            return cursor != null && cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID) >= 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Confirm that an authorized SAF document contains an actual audio track
     * before creating a playable row. A provider's audio/* MIME type or a
     * filename extension alone is not evidence: some providers label
     * directories or non-audio documents as audio.
     *
     * Return -1 when the document is not recognized as audio. A verified
     * audio document may legitimately have an unknown duration (zero).
     * MediaExtractor is short-lived and runs only on the scan worker.
     */
    private long probeAudioTrack(Uri fileUri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, fileUri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
                return Math.max(0L, durationUs / 1000L);
            }
        } catch (java.io.IOException | RuntimeException exception) {
            Log.d(TAG, "Document is not a readable audio track: " + fileUri,
                    exception);
        } finally {
            extractor.release();
        }
        return -1L;
    }

    private Song inspectAudio(Uri sourceTree, Uri fileUri, String filename) {
        long verifiedDurationMs = probeAudioTrack(fileUri);
        if (verifiedDurationMs < 0L) {
            Log.i(TAG, "Ignoring non-audio selected document: " + fileUri);
            return null;
        }

        String fallbackTitle = filename == null ? "Unknown Title" : filename;
        int dot = fallbackTitle.lastIndexOf('.');
        if (dot > 0) fallbackTitle = fallbackTitle.substring(0, dot);

        String title = fallbackTitle;
        String artist = "Unknown Artist";
        String album = "Unknown Album";
        String genre = "Unknown Genre";
        long duration = verifiedDurationMs;
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
            long taggedDuration = parseLong(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (taggedDuration > 0L) duration = taggedDuration;
            track = parseTrack(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
        } catch (RuntimeException exception) {
            // A positively verified audio stream remains browseable by
            // filename even when it lacks readable tags.
            Log.d(TAG, "Metadata unavailable for " + fileUri, exception);
        } finally {
            try {
                metadata.release();
            } catch (java.io.IOException | RuntimeException exception) {
                // A cleanup error must not discard a readable track or abort
                // the remaining scan of user-selected music folders.
                Log.w(TAG, "Unable to release metadata retriever for " + fileUri,
                        exception);
            }
        }
        long songId = fileUri.toString().hashCode() & 0xffffffffL;
        return new Song(songId, fileUri, sourceTree, title, artist, album, genre,
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
        // Close Room after the queued worker operation has finished or exited.
        worker.execute(libraryDatabase::close);
        worker.shutdown();
    }
}
