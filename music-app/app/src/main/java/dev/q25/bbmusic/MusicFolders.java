package dev.q25.bbmusic;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit, persisted folder allowlist for the standalone music library.
 * The scanner receives only directories that the user selected using
 * Android's Storage Access Framework AND that still have read grants.
 */
final class MusicFolders {
    private static final String TAG = "BBMusic-Folders";
    private static final String PREFS = "bbmusic_selected_folders";
    private static final String TREES = "tree_uris";

    private MusicFolders() {}

    static List<Uri> selectedTrees(Context context) {
        Set<String> granted = new HashSet<>();
        for (UriPermission permission :
                context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission()) {
                granted.add(permission.getUri().toString());
            }
        }

        Set<String> saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(TREES, Collections.emptySet());
        ArrayList<String> authorized = new ArrayList<>();
        for (String encoded : saved) {
            if (granted.contains(encoded)) authorized.add(encoded);
        }
        Collections.sort(authorized);
        ArrayList<Uri> selected = new ArrayList<>(authorized.size());
        for (String encoded : authorized) selected.add(Uri.parse(encoded));
        return selected;
    }

    static boolean add(Context context, Uri tree) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> updated = new HashSet<>(preferences.getStringSet(
                TREES, Collections.emptySet()));
        if (!updated.add(tree.toString())) return false;
        // commit() makes the new allowlist durable before a library scan starts.
        if (!preferences.edit().putStringSet(TREES, updated).commit()) {
            throw new IllegalStateException("Cannot save selected music folder");
        }
        return true;
    }

    /** Friendly tree-root name from the selected document provider only. */
    static String displayName(Context context, Uri tree) {
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
            try (Cursor cursor = context.getContentResolver().query(
                    document, projection, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Cannot resolve selected folder name", exception);
        }
        String id = documentId(tree);
        if (id.isEmpty()) return "Music folder";
        int separator = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
        return separator >= 0 && separator < id.length() - 1
                ? id.substring(separator + 1) : id;
    }

    /** Readable location to distinguish identically named selected folders. */
    static String documentId(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            return id == null ? "" : id;
        } catch (RuntimeException exception) {
            return tree.toString();
        }
    }

    /** Remove only this app's allowance and persisted read grant; never files. */
    static boolean remove(Context context, Uri tree) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> updated = new HashSet<>(preferences.getStringSet(
                TREES, Collections.emptySet()));
        if (!updated.remove(tree.toString())) return false;
        // Write the allowlist first: even if the OS grant release fails,
        // future scans cannot read a folder that is no longer selected.
        if (!preferences.edit().putStringSet(TREES, updated).commit()) {
            throw new IllegalStateException("Cannot remove selected music folder");
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException exception) {
            // An already-revoked grant does not undo the allowlist removal.
            Log.w(TAG, "Selected folder read grant is no longer held", exception);
        }
        return true;
    }

}
