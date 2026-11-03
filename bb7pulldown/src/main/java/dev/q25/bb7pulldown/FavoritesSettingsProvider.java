package dev.q25.bb7pulldown;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONArray;

/**
 * Small read-only configuration bridge from the BB UI app to hooked SystemUI.
 * The only external caller permitted is the actual com.android.systemui UID.
 * This provider never accepts arbitrary app writes to favorite settings.
 */
public final class FavoritesSettingsProvider extends ContentProvider {
    static final String AUTHORITY = "dev.q25.trackpad.favorites";
    static final Uri RULES_URI = Uri.parse("content://" + AUTHORITY + "/rules");
    static final Uri CONVERSATIONS_URI = Uri.parse("content://" + AUTHORITY + "/conversations");
    static final String METHOD_REPORT_CONVERSATIONS = "reportConversations";
    private static final String PREFS = "bbui_favorites";
    private static final String RULES_KEY = "rules";
    private static final String OBSERVED_KEY = "observed";
    private static final String DEFAULT_RULES =
            "{\"version\":1,\"enabled\":false,\"apps\":[]}";

    @Override public boolean onCreate() { return true; }

    static FavoritesRules loadLocal(Context context) {
        return FavoritesJson.parse(prefs(context).getString(RULES_KEY, DEFAULT_RULES));
    }

    static void saveLocal(Context context, FavoritesRules favorites) {
        // Drop previously observed conversation labels once their app is removed.
        JSONArray observed = observedLocal(context);
        JSONArray retained = new JSONArray();
        for (int i = 0; i < observed.length(); i++) {
            org.json.JSONObject candidate = observed.optJSONObject(i);
            if (candidate != null
                    && favorites.find(candidate.optString("package", "")) != null) {
                retained.put(candidate);
            }
        }
        prefs(context).edit()
                .putString(RULES_KEY, FavoritesJson.serialize(favorites))
                .putString(OBSERVED_KEY, retained.toString())
                .apply();
        context.getContentResolver().notifyChange(RULES_URI, null);
    }

    static JSONArray observedLocal(Context context) {
        String json = prefs(context).getString(OBSERVED_KEY, "[]");
        try {
            return new JSONArray(json == null ? "[]" : json);
        } catch (org.json.JSONException error) {
            return new JSONArray();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void enforceCaller() {
        Context context = getContext();
        if (context == null) throw new SecurityException("Favorites provider unavailable");
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) return;
        try {
            int systemUiUid = context.getPackageManager().getPackageUid(
                    "com.android.systemui", 0);
            if (callingUid == systemUiUid) return;
        } catch (PackageManager.NameNotFoundException ignored) { }
        throw new SecurityException("Favorites configuration is private to BB UI and SystemUI");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceCaller();
        Context context = getContext();
        if (context == null) return null;
        String value;
        if (RULES_URI.equals(uri)) {
            value = prefs(context).getString(RULES_KEY, DEFAULT_RULES);
        } else if (CONVERSATIONS_URI.equals(uri)) {
            value = prefs(context).getString(OBSERVED_KEY, "[]");
        } else {
            throw new IllegalArgumentException("Unknown Favorites URI");
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"json"});
        cursor.addRow(new Object[]{value});
        return cursor;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        Context context = getContext();
        if (context == null || !METHOD_REPORT_CONVERSATIONS.equals(method)) {
            return super.call(method, arg, extras);
        }
        // SystemUI reports only currently active conversation IDs, not message bodies.
        String json = extras == null ? null : extras.getString("json");
        if (json == null || json.length() > 32_768) return Bundle.EMPTY;
        try {
            new JSONArray(json); // Reject invalid input.
            prefs(context).edit().putString(OBSERVED_KEY, json).apply();
            context.getContentResolver().notifyChange(CONVERSATIONS_URI, null);
        } catch (org.json.JSONException ignored) { }
        return Bundle.EMPTY;
    }

    @Override public String getType(Uri uri) {
        enforceCaller();
        return "vnd.android.cursor.item/vnd.bbui.favorites";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new SecurityException("Favorites settings are app-owned");
    }

    @Override public int update(Uri uri, ContentValues values,
            String selection, String[] selectionArgs) {
        throw new SecurityException("Favorites settings are app-owned");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("Favorites settings are app-owned");
    }
}
