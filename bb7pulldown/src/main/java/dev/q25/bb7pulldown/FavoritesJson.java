package dev.q25.bb7pulldown;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned JSON format shared by the local Settings UI and SystemUI bridge. */
final class FavoritesJson {
    private FavoritesJson() {}

    static FavoritesRules parse(String json) {
        FavoritesRules favorites = new FavoritesRules();
        if (json == null || json.isEmpty() || json.length() > 100_000) return favorites;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("version", 1) != 1) return favorites;
            favorites.enabled = root.optBoolean("enabled", false);
            JSONArray apps = root.optJSONArray("apps");
            if (apps == null) return favorites;
            for (int i = 0; i < Math.min(apps.length(), 100); i++) {
                JSONObject data = apps.optJSONObject(i);
                if (data == null) continue;
                String pkg = data.optString("package", "").trim();
                if (pkg.isEmpty() || pkg.length() > 255 || favorites.find(pkg) != null) continue;
                FavoritesRules.AppRule app = new FavoritesRules.AppRule(pkg);
                app.onlyMatching = data.optBoolean("onlyMatching", false);
                app.matchAll = data.optBoolean("matchAll", false);
                JSONArray rules = data.optJSONArray("rules");
                if (rules != null) {
                    for (int j = 0; j < Math.min(rules.length(), 30); j++) {
                        JSONObject candidate = rules.optJSONObject(j);
                        if (candidate == null) continue;
                        String type = candidate.optString("type", "");
                        String value = candidate.optString("value", "").trim();
                        if (value.isEmpty() || value.length() > 255) continue;
                        if (!FavoritesRules.TYPE_CONVERSATION.equals(type)
                                && !FavoritesRules.TYPE_TITLE_CONTAINS.equals(type)) continue;
                        app.rules.add(new FavoritesRules.Rule(type, value));
                    }
                }
                favorites.apps.add(app);
            }
        } catch (Exception ignored) {
            return new FavoritesRules();
        }
        return favorites;
    }

    static String serialize(FavoritesRules favorites) {
        JSONObject root = new JSONObject();
        JSONArray apps = new JSONArray();
        try {
            root.put("version", 1);
            root.put("enabled", favorites.enabled);
            for (FavoritesRules.AppRule app : favorites.apps) {
                if (app.packageName.isEmpty()) continue;
                JSONObject data = new JSONObject();
                data.put("package", app.packageName);
                data.put("onlyMatching", app.onlyMatching);
                data.put("matchAll", app.matchAll);
                JSONArray rules = new JSONArray();
                for (FavoritesRules.Rule rule : app.rules) {
                    if (rule.value.isEmpty()) continue;
                    JSONObject value = new JSONObject();
                    value.put("type", rule.type);
                    value.put("value", rule.value);
                    rules.put(value);
                }
                data.put("rules", rules);
                apps.put(data);
            }
            root.put("apps", apps);
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to encode Favorites settings", error);
        }
        return root.toString();
    }
}
