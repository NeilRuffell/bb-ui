package dev.q25.bb7pulldown;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs in SystemUI: read private app rules and report native conversation choices. */
final class FavoritesClient {
    private static final String TAG = "BB7-Favorites";
    private static String lastObserved;

    private FavoritesClient() {}

    static FavoritesRules read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                FavoritesSettingsProvider.RULES_URI,
                new String[]{"json"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return FavoritesJson.parse(cursor.getString(0));
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Favorites settings unavailable; existing shade remains usable", error);
        }
        return new FavoritesRules();
    }

    static void reportObserved(Context context, FavoritesRules rules,
            List<NotificationItem> notifications) {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();
        if (notifications != null && rules != null) {
            for (NotificationItem item : notifications) {
                if (item == null || item.groupSummary || item.conversationIds.isEmpty()
                        || rules.find(item.packageName) == null) continue;
                String label = item.conversationTitle.isEmpty()
                        ? item.title : item.conversationTitle;
                for (String id : item.conversationIds) {
                    String unique = item.packageName.length() + ":" + item.packageName + id;
                    if (!seen.add(unique) || id.isEmpty()) continue;
                    try {
                        JSONObject candidate = new JSONObject();
                        candidate.put("package", item.packageName);
                        candidate.put("id", id);
                        candidate.put("label", label);
                        result.put(candidate);
                    } catch (JSONException ignored) { }
                    if (result.length() >= 100) break;
                }
                if (result.length() >= 100) break;
            }
        }
        String json = result.toString();
        if (json.equals(lastObserved)) return;
        try {
            Bundle extras = new Bundle();
            extras.putString("json", json);
            context.getContentResolver().call(
                    FavoritesSettingsProvider.RULES_URI,
                    FavoritesSettingsProvider.METHOD_REPORT_CONVERSATIONS, null, extras);
            lastObserved = json;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to share native conversation choices with Favorites settings", error);
        }
    }
}
