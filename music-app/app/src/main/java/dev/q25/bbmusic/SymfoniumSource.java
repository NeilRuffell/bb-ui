package dev.q25.bbmusic;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plain Android media adapter, independent from the BB Music UI and BB UI
 * SystemUI module. Symfonium owns catalog indexing, audio playback and queue.
 *
 * Requests use public MediaBrowser/MediaController APIs as an ordinary app.
 * The caller chooses navigation, presentation, and when to request playback.
 */
final class SymfoniumSource {
    private static final String TAG = "BBMusic-Source";
    private static final String PACKAGE = "app.symfonik.music.player";
    private static final String SERVICE =
            "app.symfonik.core.playback.service.PlayerService";

    interface Listener {
        void onConnectionChanged(boolean connected, String rootId);
        void onItems(String parentId, int page, List<MediaBrowser.MediaItem> items);
        void onBrowseError(String parentId, int page);
        void onPlaybackChanged(MediaMetadata metadata, PlaybackState state);
    }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Subscription> subscriptions = new ArrayList<>();
    private MediaBrowser browser;
    private MediaController controller;
    private boolean started;

    private static final class Subscription {
        final String parentId;
        final MediaBrowser.SubscriptionCallback callback;

        Subscription(String parentId, MediaBrowser.SubscriptionCallback callback) {
            this.parentId = parentId;
            this.callback = callback;
        }
    }

    private final MediaController.Callback controllerCallback =
            new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata metadata) {
                    publishPlayback();
                }
                @Override public void onPlaybackStateChanged(PlaybackState state) {
                    publishPlayback();
                }
                @Override public void onSessionDestroyed() {
                    disconnectController();
                    if (started) listener.onPlaybackChanged(null, null);
                }
            };

    SymfoniumSource(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void connect() {
        if (started) return;
        started = true;
        browser = new MediaBrowser(context,
                new ComponentName(PACKAGE, SERVICE),
                new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        if (!started || browser == null) return;
                        try {
                            disconnectController();
                            controller = new MediaController(context, browser.getSessionToken());
                            controller.registerCallback(controllerCallback, main);
                        } catch (RuntimeException error) {
                            Log.w(TAG, "Playback controller unavailable", error);
                        }
                        String root = browser.getRoot();
                        Log.i(TAG, "Connected: root=" + root);
                        listener.onConnectionChanged(true, root);
                        publishPlayback();
                    }

                    @Override public void onConnectionFailed() {
                        if (!started) return;
                        Log.w(TAG, "Browser connection failed");
                        listener.onConnectionChanged(false, null);
                    }

                    @Override public void onConnectionSuspended() {
                        if (!started) return;
                        Log.w(TAG, "Browser connection suspended");
                        disconnectController();
                        listener.onConnectionChanged(false, null);
                    }
                }, null);
        try {
            browser.connect();
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to connect to media service", error);
            listener.onConnectionChanged(false, null);
        }
    }

    boolean isConnected() {
        return browser != null && browser.isConnected();
    }

    /**
     * Subscribes to one page and forwards exactly one callback. No UI-specific
     * navigation or loading policy is imposed by this source class.
     */
    boolean browse(String parentId, int page, int pageSize) {
        if (!started || !isConnected() || parentId == null || page < 0
                || pageSize <= 0) return false;
        final MediaBrowser currentBrowser = browser;
        Bundle options = new Bundle();
        options.putInt(MediaBrowser.EXTRA_PAGE, page);
        options.putInt(MediaBrowser.EXTRA_PAGE_SIZE, pageSize);
        MediaBrowser.SubscriptionCallback callback = new MediaBrowser.SubscriptionCallback() {
            private boolean completed;

            @Override public void onChildrenLoaded(String id,
                    List<MediaBrowser.MediaItem> children) {
                complete(children);
            }

            @Override public void onChildrenLoaded(String id,
                    List<MediaBrowser.MediaItem> children, Bundle extras) {
                complete(children);
            }

            @Override public void onError(String id) {
                error();
            }

            @Override public void onError(String id, Bundle extras) {
                error();
            }

            private void complete(List<MediaBrowser.MediaItem> children) {
                if (completed) return;
                completed = true;
                releaseSubscription(currentBrowser, parentId, this);
                if (started) {
                    listener.onItems(parentId, page,
                            children == null ? new ArrayList<>() : children);
                }
            }

            private void error() {
                if (completed) return;
                completed = true;
                releaseSubscription(currentBrowser, parentId, this);
                if (started) listener.onBrowseError(parentId, page);
            }
        };
        subscriptions.add(new Subscription(parentId, callback));
        try {
            currentBrowser.subscribe(parentId, options, callback);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Browse request failed for " + parentId, error);
            releaseSubscription(currentBrowser, parentId, callback);
            listener.onBrowseError(parentId, page);
            return false;
        }
    }

    /** These are requests, not proof that Symfonium performed the action. */
    boolean playFromMediaId(String mediaId) {
        if (controller == null || mediaId == null) return false;
        try {
            controller.getTransportControls().playFromMediaId(mediaId, null);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Playback request failed", error);
            return false;
        }
    }

    boolean play() {
        if (controller == null) return false;
        try {
            controller.getTransportControls().play();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Play unavailable", error);
            return false;
        }
    }

    boolean pause() {
        if (controller == null) return false;
        try {
            controller.getTransportControls().pause();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Pause unavailable", error);
            return false;
        }
    }

    boolean skipToNext() {
        if (controller == null) return false;
        try {
            controller.getTransportControls().skipToNext();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Next unavailable", error);
            return false;
        }
    }

    boolean skipToPrevious() {
        if (controller == null) return false;
        try {
            controller.getTransportControls().skipToPrevious();
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Previous unavailable", error);
            return false;
        }
    }

    private void publishPlayback() {
        if (!started || controller == null) return;
        try {
            listener.onPlaybackChanged(controller.getMetadata(),
                    controller.getPlaybackState());
        } catch (RuntimeException error) {
            Log.w(TAG, "Playback state unavailable", error);
        }
    }

    private void releaseSubscription(MediaBrowser owner, String parentId,
            MediaBrowser.SubscriptionCallback callback) {
        subscriptions.removeIf(item -> item.callback == callback);
        if (owner == null || !owner.isConnected()) return;
        try {
            owner.unsubscribe(parentId, callback);
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to unsubscribe " + parentId, error);
        }
    }

    private void disconnectController() {
        if (controller == null) return;
        try {
            controller.unregisterCallback(controllerCallback);
        } catch (RuntimeException error) {
            Log.w(TAG, "Media callback cleanup failed", error);
        }
        controller = null;
    }

    void disconnect() {
        if (!started) return;
        started = false;
        MediaBrowser oldBrowser = browser;
        for (Subscription sub : new ArrayList<>(subscriptions)) {
            releaseSubscription(oldBrowser, sub.parentId, sub.callback);
        }
        subscriptions.clear();
        disconnectController();
        browser = null;
        if (oldBrowser != null) {
            try {
                oldBrowser.disconnect();
            } catch (RuntimeException error) {
                Log.w(TAG, "Browser disconnect failed", error);
            }
        }
    }
}
