package dev.q25.bbmusic;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.List;

/**
 * Private, app-owned music catalog. The original files remain in the user's
 * Storage Access Framework folders; only their metadata and authorized URIs
 * are cached here. All queries and writes run on LocalMusicSource's worker.
 */
@Database(entities = {MusicLibraryDatabase.TrackRow.class,
        MusicLibraryDatabase.ScanRow.class}, version = 1, exportSchema = false)
abstract class MusicLibraryDatabase extends RoomDatabase {
    private static final String DATABASE_NAME = "bbmusic_library.db";

    @Entity(tableName = "library_tracks", indices = {@Index("sourceTree")})
    static class TrackRow {
        @PrimaryKey @NonNull public String uri = "";
        @NonNull public String sourceTree = "";
        @NonNull public String title = "";
        @NonNull public String artist = "";
        @NonNull public String album = "";
        @NonNull public String genre = "";
        public long id;
        public long artistId;
        public long albumId;
        public long durationMs;
        public int trackNumber;
    }

    /**
     * A completed scan is recorded even for an empty collection. A different
     * authorized folder set makes the cached snapshot invalid.
     */
    @Entity(tableName = "library_scan")
    static class ScanRow {
        @PrimaryKey public int id = 1;
        @NonNull public String selectedTreesKey = "";
    }

    @Dao
    interface LibraryDao {
        @Query("SELECT * FROM library_tracks ORDER BY title COLLATE NOCASE, uri")
        List<TrackRow> tracks();

        @Query("SELECT * FROM library_scan WHERE id = 1 LIMIT 1")
        ScanRow scanState();

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void insertTracks(List<TrackRow> tracks);

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        void setScanState(ScanRow row);

        @Query("DELETE FROM library_tracks")
        void deleteAllTracks();

        @Query("DELETE FROM library_tracks WHERE sourceTree = :sourceTree")
        void deleteTracksFromTree(String sourceTree);

        @Query("DELETE FROM library_scan")
        void deleteScanState();
    }

    abstract LibraryDao libraryDao();

    static MusicLibraryDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(),
                MusicLibraryDatabase.class, DATABASE_NAME).build();
    }
}
