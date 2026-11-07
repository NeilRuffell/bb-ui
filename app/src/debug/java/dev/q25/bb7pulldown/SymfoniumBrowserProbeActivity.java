package dev.q25.bb7pulldown;

import android.app.Activity;
import android.content.ComponentName;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Debug-build-only, read-only inspection of Symfonium's public MediaBrowserService.
 *
 * It never starts playback, queries OneDrive, or touches SystemUI. This activity
 * runs under BB UI's normal app UID, specifically to test whether a separate
 * Music Activity can browse Symfonium without privileged SystemUI access.
 */
public final class SymfoniumBrowserProbeActivity extends Activity {
    private static final String TAG = "BBUI-BrowserProbe";
    private static final String PACKAGE = "app.symfonik.music.player";
    private static final String SERVICE =
            "app.symfonik.core.playback.service.PlayerService";

    private static final int PAGE_SIZE = 25;
    private static final int MAX_NODES = 38;
    private static final int MAX_DEPTH = 4;
    private static final int MAX_DISPLAY_ITEMS = 12;
    private static final long NODE_TIMEOUT_MS = 7000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Node> pending = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();

    private MediaBrowser browser;
    private TextView output;
    private ScrollView scrollView;
    private Node active;
    private int requests;
    private boolean disposed;

    private static final class Node {
        final String id;
        final String label;
        final int depth;
        final int page;

        Node(String id, String label, int depth, int page) {
            this.id = id;
            this.label = label;
            this.depth = depth;
            this.page = page;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scrollView = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int inset = Math.round(16 * getResources().getDisplayMetrics().density);
        output.setPadding(inset, inset, inset, inset);
        output.setTextIsSelectable(true);
        scrollView.addView(output, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        report("Symfonium MediaBrowser probe (read-only)");
        report("Caller: " + getPackageName() + " / service: " + PACKAGE);
        report("Connecting...");
        try {
            browser = new MediaBrowser(this, new ComponentName(PACKAGE, SERVICE),
                    new MediaBrowser.ConnectionCallback() {
                        @Override public void onConnected() {
                            if (disposed) return;
                            String root = browser.getRoot();
                            Bundle extras = browser.getExtras();
                            report("CONNECTED; root=" + clip(root));
                            report("Root extras keys=" + (extras == null
                                    ? "(none)" : extras.keySet().toString()));
                            if (extras != null) {
                                report("Search supported=" + extras.getBoolean(
                                        "android.media.browse.SEARCH_SUPPORTED", false));
                            }
                            if (root == null || root.isEmpty()) {
                                report("No browseable root provided.");
                                return;
                            }
                            enqueue(root, "ROOT", 0);
                            next();
                        }

                        @Override public void onConnectionFailed() {
                            report("CONNECTION_FAILED: service refused or could not connect.");
                        }

                        @Override public void onConnectionSuspended() {
                            report("CONNECTION_SUSPENDED: media service disconnected.");
                        }
                    }, null);
            browser.connect();
        } catch (SecurityException error) {
            report("SECURITY_EXCEPTION: " + error);
        } catch (RuntimeException error) {
            report("CONNECT_ERROR: " + error);
        }
    }

    private void enqueue(String id, String label, int depth) {
        enqueuePage(id, label, depth, 0);
    }

    private void enqueuePage(String id, String label, int depth, int page) {
        if (id == null || id.isEmpty() || depth > MAX_DEPTH || page < 0
                || !seen.add(id + "#" + page)) return;
        Node node = new Node(id, label, depth, page);
        // The library is the subject of this investigation; visit it before
        // optional home/recommendations categories.
        if (depth == 1 && "Library".equalsIgnoreCase(label)) {
            pending.addFirst(node);
        } else {
            pending.addLast(node);
        }
    }

    private void next() {
        if (disposed || active != null || browser == null || !browser.isConnected()) return;
        if (requests >= MAX_NODES || pending.isEmpty()) {
            report("PROBE_DONE: nodes queried=" + requests + ", pending=" + pending.size());
            return;
        }

        Node node = pending.removeFirst();
        active = node;
        requests++;
        report("REQUEST depth=" + node.depth + " page=" + node.page
                + " label=" + clip(node.label) + " id=" + clip(node.id));

        Bundle page = new Bundle();
        page.putInt(MediaBrowser.EXTRA_PAGE, node.page);
        page.putInt(MediaBrowser.EXTRA_PAGE_SIZE, PAGE_SIZE);
        MediaBrowser.SubscriptionCallback callback = new MediaBrowser.SubscriptionCallback() {
            private boolean responded;

            @Override public void onChildrenLoaded(String parentId,
                    List<MediaBrowser.MediaItem> children) {
                received(children, false);
            }

            @Override public void onChildrenLoaded(String parentId,
                    List<MediaBrowser.MediaItem> children, Bundle options) {
                received(children, true);
            }

            private void received(List<MediaBrowser.MediaItem> children, boolean paged) {
                if (responded || disposed || active != node) return;
                responded = true;
                int count = children == null ? -1 : children.size();
                report("CHILDREN depth=" + node.depth + " page=" + node.page
                        + " label=" + clip(node.label)
                        + " count=" + count + " paged=" + paged);

                if (children != null) {
                    int printed = 0;
                    int enqueued = 0;
                    for (MediaBrowser.MediaItem item : children) {
                        if (item == null) continue;
                        MediaDescription description = item.getDescription();
                        String title = description == null || description.getTitle() == null
                                ? "(untitled)" : description.getTitle().toString();
                        if (printed < MAX_DISPLAY_ITEMS) {
                            String subtitle = description == null || description.getSubtitle() == null
                                    ? "" : description.getSubtitle().toString();
                            String art = description == null || description.getIconUri() == null
                                    ? "" : description.getIconUri().toString();
                            report("  " + (item.isBrowsable() ? "[BROWSE] " : "[PLAY] ")
                                    + clip(title) + " | id=" + clip(item.getMediaId())
                                    + (subtitle.isEmpty() ? "" : " | subtitle=" + clip(subtitle))
                                    + (art.isEmpty() ? "" : " | art=" + clip(art)));
                            printed++;
                        }
                        if (node.page == 0 && item.isBrowsable() && node.depth < MAX_DEPTH
                                && enqueued < (node.depth <= 1 ? 9 : node.depth == 2 ? 2 : 1)
                                && seen.size() < 60) {
                            int before = seen.size();
                            enqueue(item.getMediaId(), title, node.depth + 1);
                            if (seen.size() > before) enqueued++;
                        }
                    }
                    if (count > printed) report("  ... " + (count - printed)
                            + " additional items omitted from log");
                    // Check that BB UI can retrieve entries beyond the first page.
                    if (node.page == 0 && node.depth == 2
                            && "Albums".equalsIgnoreCase(node.label)
                            && count == PAGE_SIZE) {
                        enqueuePage(node.id, "Albums page two", node.depth, 1);
                    }
                }
                finish(node, this);
            }

            @Override public void onError(String parentId) {
                failed();
            }

            @Override public void onError(String parentId, Bundle options) {
                failed();
            }

            private void failed() {
                if (responded || disposed || active != node) return;
                responded = true;
                report("SUBSCRIPTION_ERROR depth=" + node.depth
                        + " label=" + clip(node.label));
                finish(node, this);
            }
        };

        try {
            browser.subscribe(node.id, page, callback);
            handler.postDelayed(() -> {
                if (disposed || active != node) return;
                report("TIMEOUT depth=" + node.depth + " label=" + clip(node.label));
                finish(node, callback);
            }, NODE_TIMEOUT_MS);
        } catch (SecurityException error) {
            report("SUBSCRIPTION_SECURITY_EXCEPTION: " + error);
            finish(node, callback);
        } catch (RuntimeException error) {
            report("SUBSCRIPTION_EXCEPTION: " + error);
            finish(node, callback);
        }
    }

    private void finish(Node node, MediaBrowser.SubscriptionCallback callback) {
        if (active != node) return;
        active = null;
        handler.post(() -> {
            if (disposed) return;
            try {
                if (browser != null && browser.isConnected()) {
                    browser.unsubscribe(node.id, callback);
                }
            } catch (RuntimeException error) {
                report("UNSUBSCRIBE_ERROR: " + error);
            }
            next();
        });
    }

    private void report(String message) {
        Log.i(TAG, message);
        if (output == null) return;
        output.append(message + "\n");
        if (scrollView != null) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String clip(String value) {
        if (value == null) return "(null)";
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.length() > 110 ? clean.substring(0, 110) + "..." : clean;
    }

    @Override
    protected void onDestroy() {
        disposed = true;
        handler.removeCallbacksAndMessages(null);
        if (browser != null) {
            try {
                browser.disconnect();
            } catch (RuntimeException error) {
                Log.w(TAG, "Browser disconnect failed", error);
            }
        }
        super.onDestroy();
    }
}
