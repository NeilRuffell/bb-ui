package dev.q25.bb7pulldown;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

/** SystemUI reads the app-owned shade organization setting via the existing provider. */
final class NotificationShadeModeClient {
    private static final String TAG = "BB7-ShadeMode";

    private NotificationShadeModeClient() {}

    static String read(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                FavoritesSettingsProvider.MODE_URI,
                new String[]{"json"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return NotificationShadeModePolicy.normalized(cursor.getString(0));
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Shade organization setting unavailable; using app grouping", error);
        }
        return NotificationShadeModePolicy.APPS;
    }
}
