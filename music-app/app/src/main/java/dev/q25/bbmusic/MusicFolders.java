package dev.q25.bbmusic;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

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
}
