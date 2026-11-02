package dev.q25.bb7pulldown;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Execute the original Notification.Action through Android's PendingIntent and
 * RemoteInput APIs. Never manufacture an app intent or clear a notification.
 */
final class NativeNotificationActions {
    private static final String TAG = "BB7-PullDown";

    static boolean activate(String key, int index, Context context, Runnable onActivityLaunch) {
        Notification.Action action = SystemUiNotificationObserver.getAction(key, index);
        if (action == null) return false;
        if (hasFreeFormReply(action)) {
            Log.i(TAG, "Reply action needs text entry before sending");
            return false;
        }
        PendingIntent intent = action.actionIntent;
        try {
            if (intent.isActivity()) {
                if (!NativeNotificationClicks.startActionActivity(intent, onActivityLaunch)) {
                    return false;
                }
            } else {
                intent.send(context, 0, null, null, null, null, allowedActivityOptions());
            }
            NativeNotificationClicks.reportActionClick(key, index, action);
            Log.i(TAG, "Original Android notification action dispatched index=" + index);
            return true;
        } catch (PendingIntent.CanceledException error) {
            Log.w(TAG, "Notification action intent canceled", error);
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "Native notification action dispatch failed", error);
            return false;
        }
    }

    static boolean hasFreeFormReply(Notification.Action action) {
        if (action == null) return false;
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null) return false;
        for (RemoteInput input : inputs) {
            if (input != null && input.getAllowFreeFormInput()) return true;
        }
        return false;
    }

    static boolean reply(String key, int index, String text, Context context) {
        Notification.Action action = SystemUiNotificationObserver.getAction(key, index);
        if (action == null || !hasFreeFormReply(action)
                || text == null || text.trim().isEmpty()) return false;
        RemoteInput[] inputs = action.getRemoteInputs();
        Bundle results = new Bundle();
        for (RemoteInput input : inputs) {
            if (input != null && input.getAllowFreeFormInput()) {
                results.putCharSequence(input.getResultKey(), text);
            }
        }

        // Identical Android RemoteInput intent encoding used in
        // LineageOS RemoteInputViewController.prepareRemoteInputFromText().
        Intent fillIn = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addResultsToIntent(inputs, fillIn, results);
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT);

        try {
            action.actionIntent.send(context, 0, fillIn, null, null, null,
                    allowedActivityOptions());
            Log.i(TAG, "Native Android RemoteInput reply sent");
            return true;
        } catch (PendingIntent.CanceledException error) {
            Log.w(TAG, "RemoteInput action canceled", error);
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "Native RemoteInput dispatch failed", error);
            return false;
        }
    }

    private static Bundle allowedActivityOptions() {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return options.toBundle();
    }

    private NativeNotificationActions() {}
}
