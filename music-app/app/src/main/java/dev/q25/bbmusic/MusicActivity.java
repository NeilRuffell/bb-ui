package dev.q25.bbmusic;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent BB Music application.
 *
 * Symfonium remains the player, OneDrive indexer and audio engine.
 * This activity uses ordinary Android MediaBrowser/MediaController APIs.
 * No code is loaded from BB UI, Vector, LSPosed, SystemUI or a root service.
 */
public final class MusicActivity extends Activity {
    private static final String TAG = "BBMusic";
    private static final String SOURCE_PACKAGE = "app.symfonik.music.player";
    private static final String SOURCE_SERVICE =
            "app.symfonik.core.playback.service.PlayerService";
    private static final int PAGE_SIZE = 50;
    private static final int DARK = 0xff17191b;
    private static final int MUTED = 0xffaeb5ba;
    private static final int SILVER = 0xffeeeeee;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Page> navigation = new ArrayDeque<>();
    private final Map<Integer, MediaBrowser.SubscriptionCallback> subscriptions =
            new HashMap<>();

    private MediaBrowser browser;
    private MediaController controller;
    private TextView heading;
    private TextView status;
    private TextView nowPlaying;
    private TextView playPause;
    private ListView entries;
    private EntryAdapter adapter;
    private Page subscribedPage;
    private OnBackInvokedCallback backCallback;

    private static final class Page {
        final String id;
        final String name;
        final List<MediaBrowser.MediaItem> items = new ArrayList<>();
        final Set<Integer> receivedPages = new HashSet<>();
        int nextPage;
        boolean loading;
        boolean hasMore;

        Page(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final MediaController.Callback playbackCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            renderPlayback();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            renderPlayback();
        }

        @Override public void onSessionDestroyed() {
            releaseController();
            status.setText("Symfonium playback session ended");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        constructInterface();
        backCallback = this::navigateBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);

        browser = new MediaBrowser(this,
                new ComponentName(SOURCE_PACKAGE, SOURCE_SERVICE),
                new MediaBrowser.ConnectionCallback() {
                    @Override public void onConnected() {
                        if (isFinishing() || isDestroyed()) return;
                        String root = browser.getRoot();
                        if (root == null || root.isEmpty()) {
                            status.setText("Symfonium provided no browseable root");
                            return;
                        }
                        try {
                            releaseController();
                            controller = new MediaController(MusicActivity.this,
                                    browser.getSessionToken());
                            controller.registerCallback(playbackCallback, main);
                            renderPlayback();
                        } catch (RuntimeException error) {
                            Log.w(TAG, "Playback controller unavailable", error);
                            status.setText("Library connected; playback controller unavailable");
                        }
                        if (navigation.isEmpty()) {
                            navigation.push(new Page(root, "Music"));
                        }
                        showCurrentPage();
                        Log.i(TAG, "Connected to Symfonium MediaBrowser root");
                    }

                    @Override public void onConnectionFailed() {
                        status.setText("Cannot connect to Symfonium. Check installation.");
                        Log.w(TAG, "Symfonium denied or failed MediaBrowser connection");
                    }

                    @Override public void onConnectionSuspended() {
                        status.setText("Symfonium connection interrupted");
                        releaseController();
                    }
                }, null);

        status.setText("Connecting to Symfonium...");
    }

    @Override protected void onStart() {
        super.onStart();
        if (browser != null && !browser.isConnected()) {
            try {
                browser.connect();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to connect", error);
                status.setText("Unable to connect to Symfonium");
            }
        }
    }

    @Override protected void onStop() {
        clearSubscription();
        releaseController();
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to disconnect", error);
            }
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }

    private void constructInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(DARK);
        setContentView(root);

        heading = text("Music", 21, Color.WHITE);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), 0, dp(16), 0);
        heading.setBackground(gradient(0xff51575a, 0xff262b2d));
        heading.setOnClickListener(view -> navigateBack());
        root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        status = text("", 12, MUTED);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(status);

        entries = new ListView(this);
        entries.setCacheColorHint(Color.TRANSPARENT);
        entries.setBackgroundColor(DARK);
        entries.setDivider(new android.graphics.drawable.ColorDrawable(0xff454a4d));
        entries.setDividerHeight(dp(1));
        entries.setSelector(gradient(0xff3aace4, 0xff005d9e));
        entries.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        entries.setFocusable(true);
        entries.setItemsCanFocus(false);
        adapter = new EntryAdapter();
        entries.setAdapter(adapter);
        entries.setOnItemClickListener((parent, row, position, id) -> select(position));

        TextView empty = text("Connecting to music library...", 14, MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(entries, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        entries.setEmptyView(empty);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setBackground(gradient(0xff44484b, 0xff1f2224));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        TextView previous = control("◀◀", controls);
        nowPlaying = text("No active track", 13, SILVER);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(nowPlaying, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        playPause = control("▶", controls);
        TextView next = control("▶▶", controls);

        previous.setOnClickListener(view -> sendTransport(0));
        playPause.setOnClickListener(view -> sendTransport(1));
        next.setOnClickListener(view -> sendTransport(2));
    }

    private TextView control(String label, LinearLayout parent) {
        TextView button = text(label, 17, SILVER);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        parent.addView(button, new LinearLayout.LayoutParams(dp(44),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return button;
    }

    private void showCurrentPage() {
        Page page = navigation.peek();
        if (page == null || browser == null || !browser.isConnected()) return;
        clearSubscription();
        heading.setText(navigation.size() > 1 ? "‹  " + page.name : page.name);
        page.items.clear();
        page.receivedPages.clear();
        page.nextPage = 0;
        page.loading = false;
        page.hasMore = false;
        adapter.notifyDataSetChanged();
        entries.setSelection(0);
        requestPage(page);
    }

    private void requestPage(Page page) {
        if (page.loading || browser == null || !browser.isConnected()) return;
        if (navigation.peek() != page) return;
        final int requestedPage = page.nextPage;
        page.loading = true;
        subscribedPage = page;
        status.setText("Loading " + page.name + "...");
        Bundle options = new Bundle();
        options.putInt(MediaBrowser.EXTRA_PAGE, requestedPage);
        options.putInt(MediaBrowser.EXTRA_PAGE_SIZE, PAGE_SIZE);

        MediaBrowser.SubscriptionCallback callback = new MediaBrowser.SubscriptionCallback() {
            @Override public void onChildrenLoaded(String parentId,
                    List<MediaBrowser.MediaItem> children) {
                accept(children);
            }

            @Override public void onChildrenLoaded(String parentId,
                    List<MediaBrowser.MediaItem> children, Bundle opts) {
                accept(children);
            }

            private void accept(List<MediaBrowser.MediaItem> children) {
                if (navigation.peek() != page || !browser.isConnected()
                        || page.receivedPages.contains(requestedPage)) return;
                page.receivedPages.add(requestedPage);
                page.loading = false;
                if (children != null) {
                    for (MediaBrowser.MediaItem item : children) {
                        if (item == null || "empty".equals(item.getMediaId())) continue;
                        page.items.add(item);
                    }
                    page.hasMore = children.size() >= PAGE_SIZE;
                } else {
                    page.hasMore = false;
                }
                page.nextPage = requestedPage + 1;
                status.setText(page.items.isEmpty() ? "No entries in " + page.name
                        : page.items.size() + " entries loaded");
                adapter.notifyDataSetChanged();
                Log.i(TAG, "Browse " + page.id + " page=" + requestedPage
                        + " results=" + (children == null ? -1 : children.size()));
            }

            @Override public void onError(String parentId) {
                fail();
            }

            @Override public void onError(String parentId, Bundle opts) {
                fail();
            }

            private void fail() {
                if (navigation.peek() != page) return;
                page.loading = false;
                status.setText("Unable to browse " + page.name);
                adapter.notifyDataSetChanged();
                Log.w(TAG, "Browser subscription failed for " + page.id);
            }
        };
        subscriptions.put(requestedPage, callback);
        try {
            browser.subscribe(page.id, options, callback);
        } catch (RuntimeException error) {
            page.loading = false;
            subscriptions.remove(requestedPage);
            status.setText("Unable to load " + page.name);
            Log.w(TAG, "Browse request failed", error);
        }
    }

    private void clearSubscription() {
        Page page = subscribedPage;
        subscribedPage = null;
        if (page != null && browser != null && browser.isConnected()) {
            try {
                browser.unsubscribe(page.id);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unsubscribe failed", error);
            }
        }
        subscriptions.clear();
        if (page != null) page.loading = false;
    }

    private void select(int position) {
        Page page = navigation.peek();
        if (page == null || position < 0) return;
        if (position >= page.items.size()) {
            if (page.hasMore && !page.loading) requestPage(page);
            return;
        }
        MediaBrowser.MediaItem item = page.items.get(position);
        if (item.isBrowsable()) {
            navigation.push(new Page(item.getMediaId(),
                    item.getDescription().getTitle() == null ? "Music"
                            : item.getDescription().getTitle().toString()));
            showCurrentPage();
        } else if (item.isPlayable()) {
            requestPlayback(item);
        }
    }

    private void requestPlayback(MediaBrowser.MediaItem item) {
        if (controller == null) {
            status.setText("No playback controller available");
            return;
        }
        String title = item.getDescription().getTitle() == null
                ? "track" : item.getDescription().getTitle().toString();
        try {
            controller.getTransportControls().playFromMediaId(item.getMediaId(), null);
            status.setText("Playback requested: " + title);
            Log.i(TAG, "Sent playFromMediaId for " + item.getMediaId());
        } catch (RuntimeException error) {
            status.setText("Playback request failed");
            Log.w(TAG, "playFromMediaId failed", error);
        }
    }

    private void sendTransport(int action) {
        if (controller == null) {
            status.setText("No playback session available");
            return;
        }
        try {
            MediaController.TransportControls transport = controller.getTransportControls();
            PlaybackState state = controller.getPlaybackState();
            switch (action) {
                case 0:
                    transport.skipToPrevious();
                    break;
                case 1:
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        transport.pause();
                    } else {
                        transport.play();
                    }
                    break;
                case 2:
                    transport.skipToNext();
                    break;
                default:
                    return;
            }
        } catch (RuntimeException error) {
            status.setText("Playback control unavailable");
            Log.w(TAG, "Playback control failed", error);
        }
    }

    private void renderPlayback() {
        if (nowPlaying == null) return;
        MediaMetadata metadata = controller == null ? null : controller.getMetadata();
        String title = metadata == null
                ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null
                ? null : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        nowPlaying.setText(title == null || title.isEmpty()
                ? "No active track"
                : artist == null || artist.isEmpty() ? title : title + " · " + artist);
        PlaybackState playback = controller == null ? null : controller.getPlaybackState();
        playPause.setText(playback != null
                && playback.getState() == PlaybackState.STATE_PLAYING ? "Ⅱ" : "▶");
    }

    private void releaseController() {
        if (controller != null) {
            try {
                controller.unregisterCallback(playbackCallback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to unregister media callback", error);
            }
        }
        controller = null;
        renderPlayback();
    }

    private void navigateBack() {
        if (navigation.size() > 1) {
            navigation.pop();
            showCurrentPage();
        } else {
            finish();
        }
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() {
            Page page = navigation.peek();
            return page == null ? 0
                    : page.items.size() + (page.hasMore ? 1 : 0);
        }

        @Override public Object getItem(int position) {
            Page page = navigation.peek();
            if (page == null || position >= page.items.size()) return null;
            return page.items.get(position);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            TextView title;
            TextView subtitle;
            if (recycled instanceof LinearLayout) {
                row = (LinearLayout) recycled;
                title = (TextView) row.getChildAt(0);
                subtitle = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MusicActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(7), dp(12), dp(7));
                row.setMinimumHeight(dp(54));
                title = text("", 16, SILVER);
                subtitle = text("", 12, MUTED);
                row.addView(title);
                row.addView(subtitle);
            }
            Page page = navigation.peek();
            if (page == null) return row;
            if (position >= page.items.size()) {
                title.setText("Load more…");
                subtitle.setText("Next page");
                return row;
            }
            MediaBrowser.MediaItem item = page.items.get(position);
            CharSequence name = item.getDescription().getTitle();
            CharSequence detail = item.getDescription().getSubtitle();
            title.setText((item.isBrowsable() ? "›  " : "♪  ")
                    + (name == null ? "(untitled)" : name.toString()));
            subtitle.setText(detail == null ? "" : detail);
            subtitle.setVisibility(detail == null || detail.length() == 0
                    ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private TextView text(String value, float sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private GradientDrawable gradient(int top, int bottom) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {top, bottom});
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
