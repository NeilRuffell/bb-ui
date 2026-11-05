package dev.q25.bb7pulldown;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.content.pm.ShortcutInfo;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Read active notification *package identities* from the same registered
 * NotificationListenerService instance owned by SystemUI.
 *
 * The shade-list hook alone can miss its first dispatch when the pipeline
 * was built before Vector hooked it. The native listener supplies both a
 * complete initial snapshot and post/remove callbacks. No notification
 * text/actions, UI views or platform notification state are changed.
 */
final class SystemUiNotificationObserver {
    private static final String TAG = "BB7-PullDown";
    private static final String LISTENER =
            "com.android.systemui.statusbar.NotificationListener";
    private static final Object LOCK = new Object();
    // Keys avoid duplicate posts and preserve the insertion order across apps.
    private static final Map<String, NotificationItem> NOTIFICATIONS = new LinkedHashMap<>();
    // Real platform notification references: action intents and clearability are never synthesized.
    private static final Map<String, StatusBarNotification> ACTIVE_SBNS = new LinkedHashMap<>();
    private static volatile List<String> activePackages = Collections.emptyList();
    private static volatile List<NotificationItem> activeItems = Collections.emptyList();
    private static volatile WeakReference<NotificationListenerService> listener =
            new WeakReference<>(null);
    private static boolean installed;
    private static boolean snapshotLogged;
    private static boolean refreshErrorLogged;

    static List<String> snapshot() {
        return activePackages;
    }

    static List<NotificationItem> itemsSnapshot() {
        return activeItems;
    }

    /**
     * Lock-screen indicators include app identity and count only; no private
     * notification text or PendingIntent is handed to the lock-screen UI.
     * Honour the global lock-screen notification toggle and Android's
     * notification/ranking VISIBILITY_SECRET restrictions.
     */
    static Map<String, Integer> lockscreenCountsSnapshot(Context context) {
        if (context == null) return Collections.emptyMap();
        try {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    "lock_screen_show_notifications", 1) == 0) {
                return Collections.emptyMap();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not read lock-screen notification visibility setting");
            return Collections.emptyMap();
        }

        List<LockscreenNotificationCounts.Record> records = new ArrayList<>();
        NotificationListenerService service = listener.get();
        NotificationListenerService.RankingMap rankings = null;
        if (service != null) {
            try { rankings = service.getCurrentRanking(); }
            catch (RuntimeException ignored) { /* Use notification visibility. */ }
        }
        synchronized (LOCK) {
            for (StatusBarNotification sbn : ACTIVE_SBNS.values()) {
                if (sbn == null || sbn.getNotification() == null) continue;
                Notification n = sbn.getNotification();
                // OS6's indicator strip represents alerts, not persistent
                // foreground-service/media controls.
                if ((n.flags & (Notification.FLAG_ONGOING_EVENT
                        | Notification.FLAG_FOREGROUND_SERVICE)) != 0) continue;
                if (n.visibility == Notification.VISIBILITY_SECRET) continue;
                if (rankings != null) {
                    NotificationListenerService.Ranking rank =
                            new NotificationListenerService.Ranking();
                    if (rankings.getRanking(sbn.getKey(), rank)) {
                        if (rank.getLockscreenVisibilityOverride()
                                == Notification.VISIBILITY_SECRET
                                || rank.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                            continue;
                        }
                    }
                }
                records.add(new LockscreenNotificationCounts.Record(
                        sbn.getPackageName(), sbn.getGroupKey(),
                        (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0, true));
            }
        }
        return LockscreenNotificationCounts.count(records);
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        Class<?> type = XposedHelpers.findClassIfExists(LISTENER, loader);
        if (type == null) {
            Log.i(TAG, "SystemUI notification listener absent in this classloader");
            return;
        }
        try {
            XposedBridge.hookAllMethods(type, "onListenerConnected", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    captureAndRefresh(param.thisObject, "connected");
                }
            });
            XposedBridge.hookAllMethods(type, "onNotificationPosted", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !(param.args[0] instanceof StatusBarNotification)) return;
                    StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                    listener = new WeakReference<>((NotificationListenerService) param.thisObject);
                    synchronized (LOCK) {
                        NOTIFICATIONS.put(sbn.getKey(), toItem(sbn,
                                ((NotificationListenerService) param.thisObject).getCurrentRanking()));
                        ACTIVE_SBNS.put(sbn.getKey(), sbn);
                        publishLocked();
                    }
                }
            });
            XposedBridge.hookAllMethods(type, "onNotificationRankingUpdate",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !(param.args[0] instanceof NotificationListenerService.RankingMap))
                        return;
                    NotificationListenerService.RankingMap rankings =
                            (NotificationListenerService.RankingMap) param.args[0];
                    synchronized (LOCK) {
                        for (StatusBarNotification sbn : ACTIVE_SBNS.values()) {
                            NOTIFICATIONS.put(sbn.getKey(), toItem(sbn, rankings));
                        }
                        publishLocked();
                    }
                }
            });
            XposedBridge.hookAllMethods(type, "onNotificationRemoved", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0
                            || !(param.args[0] instanceof StatusBarNotification)) return;
                    StatusBarNotification sbn = (StatusBarNotification) param.args[0];
                    synchronized (LOCK) {
                        NOTIFICATIONS.remove(sbn.getKey());
                        ACTIVE_SBNS.remove(sbn.getKey());
                        NativeNotificationClicks.forget(sbn.getKey());
                        publishLocked();
                    }
                }
            });
            installed = true;
            Log.i(TAG, "SystemUI active-notification listener observer registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register active-notification observer", error);
        }
    }

    /** Refresh on opening as well, in case the listener connected early. */
    static void refreshBeforeOpen() {
        NotificationListenerService service = listener.get();
        if (service != null) refresh(service, "open");
        else Log.i(TAG, "Notification listener not captured yet; apps="
                + activePackages.size());
    }

    private static void captureAndRefresh(Object service, String source) {
        if (!(service instanceof NotificationListenerService)) return;
        NotificationListenerService notificationListener = (NotificationListenerService) service;
        listener = new WeakReference<>(notificationListener);
        refresh(notificationListener, source);
    }

    private static void refresh(NotificationListenerService service, String source) {
        try {
            StatusBarNotification[] current = service.getActiveNotifications();
            if (current == null) {
                Log.w(TAG, "Active notification snapshot unavailable source=" + source);
                return;
            }
            // Take one native ranking snapshot, not one per notification.
            NotificationListenerService.RankingMap rankings = service.getCurrentRanking();
            synchronized (LOCK) {
                NOTIFICATIONS.clear();
                ACTIVE_SBNS.clear();
                for (StatusBarNotification sbn : current) {
                    if (sbn == null) continue;
                    NOTIFICATIONS.put(sbn.getKey(), toItem(sbn, rankings));
                    ACTIVE_SBNS.put(sbn.getKey(), sbn);
                }
                if (!snapshotLogged || "open".equals(source)) {
                    snapshotLogged = true;
                    Log.i(TAG, "Native notification snapshot: source=" + source
                            + " entries=" + current.length + " apps="
                            + packageNames().size());
                }
                publishLocked();
            }
        } catch (Throwable error) {
            if (!refreshErrorLogged) {
                refreshErrorLogged = true;
                Log.w(TAG, "Cannot read active notifications from SystemUI", error);
            }
        }
    }

    /**
     * Tokens advertised by real native media notifications. Reuse Android's
     * EXTRA_MEDIA_SESSION rather than interpreting action titles or icons.
     */
    static List<MediaSession.Token> mediaSessionTokensSnapshot() {
        synchronized (LOCK) {
            LinkedHashSet<MediaSession.Token> tokens = new LinkedHashSet<>();
            for (StatusBarNotification sbn : ACTIVE_SBNS.values()) {
                Notification notification = sbn.getNotification();
                if (notification == null || notification.extras == null) continue;
                try {
                    MediaSession.Token token = notification.extras.getParcelable(
                            Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                    if (token != null) tokens.add(token);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to extract native media notification token", error);
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(tokens));
        }
    }

    /**
     * Stock media surfaces use the media notification's large icon as a final
     * artwork fallback when the session does not provide an embedded bitmap.
     * Return only already-materialized bitmap icons; URI icons require I/O.
     */
    static Bitmap mediaNotificationArtwork(MediaSession.Token selectedToken, Context context) {
        if (selectedToken == null) return null;
        synchronized (LOCK) {
            for (StatusBarNotification sbn : ACTIVE_SBNS.values()) {
                Notification notification = sbn.getNotification();
                if (notification == null || notification.extras == null) continue;
                try {
                    MediaSession.Token token = notification.extras.getParcelable(
                            Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                    if (!selectedToken.equals(token)) continue;
                    Icon icon = notification.getLargeIcon();
                    if (icon == null) continue;
                    int type = icon.getType();
                    if (type == Icon.TYPE_BITMAP || type == Icon.TYPE_ADAPTIVE_BITMAP) {
                        Drawable drawable = icon.loadDrawable(context);
                        if (drawable instanceof BitmapDrawable) {
                            return ((BitmapDrawable) drawable).getBitmap();
                        }
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to read native media notification artwork", error);
                }
            }
        }
        return null;
    }

    /**
     * Notification keys already represented by visible native media sessions.
     * Only those notifications may be hidden from the redundant Silent list.
     */
    static Set<String> notificationKeysForMediaTokens(Set<MediaSession.Token> mediaTokens) {
        if (mediaTokens == null || mediaTokens.isEmpty()) return Collections.emptySet();
        synchronized (LOCK) {
            Set<String> keys = new LinkedHashSet<>();
            for (Map.Entry<String, StatusBarNotification> entry : ACTIVE_SBNS.entrySet()) {
                Notification notification = entry.getValue().getNotification();
                if (notification == null || notification.extras == null) continue;
                try {
                    MediaSession.Token token = notification.extras.getParcelable(
                            Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                    if (token != null && mediaTokens.contains(token)) {
                        keys.add(entry.getKey());
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to inspect media notification token", error);
                }
            }
            return Collections.unmodifiableSet(keys);
        }
    }

    /**
     * Read the real app-supplied PendingIntent from the currently active
     * Android notification. Do not maintain a second open-intent cache.
     */
    static PendingIntent getOpenAction(String notificationKey) {
        synchronized (LOCK) {
            StatusBarNotification sbn = ACTIVE_SBNS.get(notificationKey);
            Notification notification = sbn == null ? null : sbn.getNotification();
            return notification == null ? null
                    : (notification.contentIntent != null
                            ? notification.contentIntent : notification.fullScreenIntent);
        }
    }

    /** Get only a currently active, app-supplied Android notification action. */
    static Notification.Action getAction(String key, int index) {
        synchronized (LOCK) {
            StatusBarNotification sbn = ACTIVE_SBNS.get(key);
            Notification notification = sbn == null ? null : sbn.getNotification();
            Notification.Action[] actions = notification == null ? null : notification.actions;
            if (actions == null || index < 0 || index >= actions.length) return null;
            Notification.Action action = actions[index];
            return action != null && action.actionIntent != null ? action : null;
        }
    }

    /** Native NotificationListenerService cancellation: NMS owns removal and callbacks. */
    static boolean dismiss(String key) {
        NotificationListenerService service = listener.get();
        if (service == null) {
            Log.w(TAG, "Dismiss unavailable: native SystemUI listener not captured");
            return false;
        }
        synchronized (LOCK) {
            StatusBarNotification sbn = ACTIVE_SBNS.get(key);
            if (sbn == null || !sbn.isClearable()) {
                Log.i(TAG, "Dismiss unavailable: notification is not clearable/active");
                return false;
            }
        }
        try {
            service.cancelNotification(key);
            Log.i(TAG, "Native NotificationListenerService dismissal requested");
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Native notification dismissal failed", error);
            return false;
        }
    }

    /** Guard against stale notification cards during live notification updates. */
    static boolean isActive(String notificationKey) {
        synchronized (LOCK) {
            return NOTIFICATIONS.containsKey(notificationKey);
        }
    }

    /** Must hold LOCK. Publish on new, changed or removed notification data. */
    private static void publishLocked() {
        List<NotificationItem> items = Collections.unmodifiableList(
                new ArrayList<>(NOTIFICATIONS.values()));
        if (items.equals(activeItems)) return;
        List<String> packages = packageNames();
        boolean appsChanged = !packages.equals(activePackages);
        activeItems = items;
        activePackages = packages;
        if (appsChanged) {
            Log.i(TAG, "Notification app index updated: apps=" + packages.size());
        }
        Log.i(TAG, "Notification summaries updated: entries=" + items.size());
        PullDownHook.onNotificationItemsUpdated();
        BB6LockScreenHook.onNotificationsUpdated();
    }

    private static List<String> packageNames() {
        List<String> names = new ArrayList<>();
        for (NotificationItem item : NOTIFICATIONS.values()) {
            names.add(item.packageName);
        }
        return NotificationAppIndex.uniquePackages(names);
    }

    private static NotificationItem toItem(StatusBarNotification sbn,
            NotificationListenerService.RankingMap rankings) {
        Notification notification = sbn.getNotification();
        Bundle extras = notification == null ? null : notification.extras;
        CharSequence title = extras == null ? null
                : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = extras == null ? null
                : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null || body.length() == 0) {
            body = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if ((body == null || body.length() == 0) && extras != null) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                StringBuilder text = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null || line.length() == 0) continue;
                    if (text.length() != 0) text.append("\n");
                    text.append(line);
                    if (text.length() >= 700) break;
                }
                body = text;
            }
        }
        if ((body == null || body.length() == 0) && notification != null) {
            body = notification.tickerText;
        }
        List<NotificationItem.ActionItem> buttons = new ArrayList<>();
        if (notification != null && notification.actions != null) {
            for (int i = 0; i < notification.actions.length; i++) {
                Notification.Action action = notification.actions[i];
                if (action == null || action.actionIntent == null) continue;
                RemoteInput[] inputs = action.getRemoteInputs();
                boolean freeFormReply = false;
                if (inputs != null) {
                    for (RemoteInput input : inputs) {
                        if (input != null && input.getAllowFreeFormInput()) {
                            freeFormReply = true;
                            break;
                        }
                    }
                }
                buttons.add(new NotificationItem.ActionItem(i, plain(action.title, 60),
                        freeFormReply));
            }
        }
        boolean summary = notification != null
                && (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        // Importance is Android's authoritative channel-level interruptiveness.
        // Missing rankings remain unclassified (displayed with alerting items).
        boolean silent = false;
        LinkedHashSet<String> conversationIds = new LinkedHashSet<>();
        String conversationTitle = plain(extras == null ? null
                : extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), 140);
        try {
            if (notification != null && notification.getShortcutId() != null
                    && !notification.getShortcutId().isEmpty()) {
                conversationIds.add(notification.getShortcutId());
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Notification shortcut metadata unavailable", error);
        }
        if (rankings != null) {
            NotificationListenerService.Ranking ranking =
                    new NotificationListenerService.Ranking();
            if (rankings.getRanking(sbn.getKey(), ranking)) {
                silent = ranking.getImportance() < NotificationManager.IMPORTANCE_DEFAULT;
                try {
                    NotificationChannel channel = ranking.getChannel();
                    if (channel != null && channel.getConversationId() != null
                            && !channel.getConversationId().isEmpty()) {
                        conversationIds.add(channel.getConversationId());
                    }
                    ShortcutInfo shortcut = ranking.getConversationShortcutInfo();
                    if (shortcut != null) {
                        if (shortcut.getId() != null && !shortcut.getId().isEmpty()) {
                            conversationIds.add(shortcut.getId());
                        }
                        if (conversationTitle.isEmpty()) {
                            conversationTitle = plain(shortcut.getShortLabel(), 140);
                        }
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Native conversation ranking metadata unavailable", error);
                }
            }
        }
        return new NotificationItem(sbn.getKey(), sbn.getPackageName(),
                plain(title, 140), plain(body, 700), sbn.getPostTime(),
                sbn.isClearable(), buttons, sbn.getGroupKey(), summary, silent,
                new ArrayList<>(conversationIds), conversationTitle);
    }

    /** Drop app-supplied formatting before displaying text in the panel. */
    private static String plain(CharSequence text, int max) {
        if (text == null) return "";
        String value = text.toString().trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private SystemUiNotificationObserver() {}
}
