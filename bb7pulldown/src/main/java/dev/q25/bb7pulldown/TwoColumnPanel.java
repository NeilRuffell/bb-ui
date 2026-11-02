package dev.q25.bb7pulldown;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Independent 15/85 notification panel. The left side is a selectable
 * presentation-only list; the right side displays selected-app summaries.
 * Android owns notification lifecycle and keyguard privacy as before.
 */
final class TwoColumnPanel extends FrameLayout {
    interface CloseListener { void onClose(); }
    interface OpenNotificationListener { void onOpen(String notificationKey); }

    private static final String TAG = "BB7-PullDown";
    private final CloseListener onClose;
    private final OpenNotificationListener onOpenNotification;
    private final List<LinearLayout> notificationRows = new ArrayList<>();
    private String selectedNotificationKey;
    private boolean notificationPaneActive;
    private final LinearLayout iconList;
    private final LinearLayout notificationList;
    private final ScrollView notificationScroller;
    private final TextView selectedAppHeading;
    private final List<FrameLayout> iconRows = new ArrayList<>();
    private List<String> renderedPackages = Collections.emptyList();
    private List<NotificationItem> notifications = Collections.emptyList();
    private List<NotificationItem> renderedCards = Collections.emptyList();
    private String renderedCardPackage;
    private String selectedPackage;
    private float touchStartY;
    private boolean trackingClose;

    TwoColumnPanel(Context context, CloseListener close,
            OpenNotificationListener openNotification) {
        super(context);
        onClose = close;
        onOpenNotification = openNotification;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(Color.rgb(16, 20, 26));
        setContentDescription("BB7 two-column pull-down");

        LinearLayout columns = new LinearLayout(context);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setWeightSum(PanelRatio.TOTAL_WEIGHT);
        addView(columns, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        FrameLayout left = new FrameLayout(context);
        left.setBackgroundColor(Color.rgb(34, 42, 52));
        columns.addView(left, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.LEFT_WEIGHT));

        ScrollView iconScroller = new ScrollView(context);
        iconScroller.setFillViewport(false);
        iconScroller.setVerticalScrollBarEnabled(false);
        iconScroller.setClipToPadding(false);
        iconScroller.setPadding(0, dp(10), 0, dp(10));
        addSwipeUpDismiss(iconScroller);
        left.addView(iconScroller, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        iconList = new LinearLayout(context);
        iconList.setOrientation(LinearLayout.VERTICAL);
        iconList.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        iconScroller.addView(iconList, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View divider = new View(context);
        divider.setBackgroundColor(Color.rgb(71, 111, 143));
        left.addView(divider, new FrameLayout.LayoutParams(
                dp(1), LayoutParams.MATCH_PARENT, Gravity.END));

        FrameLayout right = new FrameLayout(context);
        right.setBackgroundColor(Color.rgb(22, 28, 36));
        columns.addView(right, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, PanelRatio.RIGHT_WEIGHT));

        LinearLayout rightContent = new LinearLayout(context);
        rightContent.setOrientation(LinearLayout.VERTICAL);
        right.addView(rightContent, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        selectedAppHeading = new TextView(context);
        selectedAppHeading.setTextColor(Color.rgb(215, 231, 244));
        selectedAppHeading.setTextSize(14);
        selectedAppHeading.setGravity(Gravity.CENTER_VERTICAL);
        selectedAppHeading.setPadding(dp(12), dp(7), dp(12), dp(7));
        selectedAppHeading.setBackgroundColor(Color.rgb(28, 37, 48));
        rightContent.addView(selectedAppHeading, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(44)));

        notificationScroller = new ScrollView(context);
        notificationScroller.setFillViewport(true);
        notificationScroller.setClipToPadding(false);
        notificationScroller.setPadding(dp(10), dp(10), dp(10), dp(10));
        addSwipeUpDismiss(notificationScroller);
        rightContent.addView(notificationScroller, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        notificationList = new LinearLayout(context);
        notificationList.setOrientation(LinearLayout.VERTICAL);
        notificationScroller.addView(notificationList, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Update the two columns from the same notification snapshot. */
    void setNotifications(List<NotificationItem> items) {
        notifications = items == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        List<String> packages = new ArrayList<>();
        for (NotificationItem item : notifications) {
            if (item != null) packages.add(item.packageName);
        }
        setNotificationPackages(packages);
        renderSelectedNotifications();
    }

    /**
     * Called on the SystemUI UI thread after a notification app list update.
     * Preserve selection by package, and restore focus if a row was focused.
     */
    void setNotificationPackages(List<String> packages) {
        List<String> distinct = NotificationAppIndex.uniquePackages(packages);
        if (renderedPackages.equals(distinct)) return;

        int previousIndex = renderedPackages.indexOf(selectedPackage);
        String previousPackage = selectedPackage;
        boolean hadRowFocus = false;
        for (FrameLayout row : iconRows) {
            if (row.isFocused()) {
                hadRowFocus = true;
                break;
            }
        }
        int newIndex = AppSelectionPolicy.afterUpdate(selectedPackage, previousIndex, distinct);
        renderedPackages = distinct;
        selectedPackage = newIndex < 0 ? null : distinct.get(newIndex);
        boolean previousAppVanished = notificationPaneActive
                && !Objects.equals(previousPackage, selectedPackage);
        if (previousAppVanished) {
            notificationPaneActive = false;
            selectedNotificationKey = null;
        }

        iconList.removeAllViews();
        iconRows.clear();
        PackageManager pm = getContext().getPackageManager();
        for (int i = 0; i < distinct.size(); i++) {
            final String pkg = distinct.get(i);
            FrameLayout row = new FrameLayout(getContext());
            row.setFocusable(!notificationPaneActive);
            row.setFocusableInTouchMode(!notificationPaneActive);
            row.setClickable(true);
            row.setOnFocusChangeListener((view, focused) -> {
                if (focused && !notificationPaneActive) select(pkg);
            });
            row.setOnClickListener(view -> {
                select(pkg);
                if (!enterNotificationPane()) view.requestFocus();
            });
            row.setOnKeyListener((view, keyCode, event) -> onIconKey(view, keyCode, event));

            ImageView icon = new ImageView(getContext());
            Drawable drawable;
            CharSequence label;
            try {
                drawable = pm.getApplicationIcon(pkg);
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException | RuntimeException error) {
                drawable = pm.getDefaultActivityIcon();
                label = pkg;
            }
            row.setContentDescription(label);
            icon.setImageDrawable(drawable);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setFocusable(false);
            icon.setClickable(false);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            icon.setPadding(dp(5), dp(8), dp(5), dp(8));
            row.addView(icon, new FrameLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            iconList.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
            iconRows.add(row);
        }

        updateSelectedHighlight();
        if ((hadRowFocus && !notificationPaneActive) || previousAppVanished) {
            // Removing a focused row may move focus to the root. Restore it
            // explicitly so keyboard/trackpad navigation continues smoothly.
            focusSelectedAppIcon();
        }
        Log.i(TAG, "App icon selector updated: count=" + distinct.size()
                + " selectedIndex=" + newIndex);
    }

    /** Called once the pull-down entrance animation finishes. */
    void focusSelectedAppIcon() {
        int index = renderedPackages.indexOf(selectedPackage);
        if (index >= 0 && index < iconRows.size()) {
            iconRows.get(index).requestFocus();
        } else {
            requestFocus();
        }
    }

    private void select(String pkg) {
        if (pkg.equals(selectedPackage)) return;
        selectedPackage = pkg;
        updateSelectedHighlight();
        renderSelectedNotifications();
        Log.i(TAG, "App icon selected: index="
                + (renderedPackages.indexOf(pkg) + 1)
                + " of=" + renderedPackages.size());
    }

    private void renderSelectedNotifications() {
        List<NotificationItem> selected = NotificationSelectionPolicy.forPackage(
                notifications, selectedPackage);
        if (Objects.equals(renderedCardPackage, selectedPackage)
                && renderedCards.equals(selected)) return;

        int oldCardIndex = NotificationFocusPolicy.indexOf(
                renderedCards, selectedNotificationKey);
        boolean sameApp = Objects.equals(renderedCardPackage, selectedPackage);
        selectedNotificationKey = NotificationFocusPolicy.retainedKey(
                selected, sameApp ? selectedNotificationKey : null,
                sameApp ? oldCardIndex : -1);
        renderedCardPackage = selectedPackage;
        renderedCards = selected;
        notificationList.removeAllViews();
        notificationRows.clear();

        if (selectedPackage == null) {
            selectedAppHeading.setText("Notifications");
            addPlaceholder("No active notifications");
            return;
        }

        CharSequence appName = selectedPackage;
        try {
            PackageManager pm = getContext().getPackageManager();
            appName = pm.getApplicationLabel(pm.getApplicationInfo(selectedPackage, 0));
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        selectedAppHeading.setText(appName + " (" + selected.size() + ")");

        if (selected.isEmpty()) {
            addPlaceholder("No notifications for this app");
        } else {
            for (NotificationItem item : selected) {
                LinearLayout card = createNotificationCard(item);
                notificationList.addView(card);
                notificationRows.add(card);
            }
        }
        if (notificationPaneActive && notificationRows.isEmpty()) {
            returnToAppIcons();
        } else if (notificationPaneActive) {
            focusSelectedNotification();
        } else {
            updateNotificationHighlight();
            notificationScroller.scrollTo(0, 0);
        }
        Log.i(TAG, "Selected app notifications displayed: count=" + selected.size());
    }

    private void addPlaceholder(String message) {
        TextView placeholder = text(message, 13, Color.rgb(151, 168, 182));
        placeholder.setPadding(dp(8), dp(20), dp(8), dp(16));
        notificationList.addView(placeholder);
    }

    private LinearLayout createNotificationCard(NotificationItem item) {
        LinearLayout card = new LinearLayout(getContext());
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                notificationPaneActive = true;
                selectNotification(item.key);
            }
        });
        card.setOnClickListener(view -> {
            notificationPaneActive = true;
            selectNotification(item.key);
            view.requestFocus();
            onOpenNotification.onOpen(item.key);
        });
        card.setOnKeyListener((view, keyCode, event) -> onNotificationKey(
                view, keyCode, event));
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(11));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(40, 52, 66));
        background.setCornerRadius(dp(5));
        card.setBackground(background);

        TextView title = text(item.title.isEmpty() ? "Notification" : item.title,
                14, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        if (!item.text.isEmpty()) {
            TextView body = text(item.text, 12, Color.rgb(213, 221, 230));
            body.setMaxLines(8);
            body.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = dp(5);
            card.addView(body, bodyParams);
        }

        if (item.postedAt > 0) {
            TextView time = text(DateFormat.getTimeFormat(getContext())
                    .format(new Date(item.postedAt)),
                    10, Color.rgb(153, 170, 184));
            LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            timeParams.topMargin = dp(6);
            card.addView(time, timeParams);
        }

        card.setContentDescription((item.title.isEmpty() ? "Notification" : item.title)
                + (item.text.isEmpty() ? "" : ", " + item.text));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(9);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void enterNotificationPaneFocus() {
        notificationPaneActive = true;
        for (FrameLayout icon : iconRows) {
            icon.setFocusable(false);
            icon.setFocusableInTouchMode(false);
        }
        focusSelectedNotification();
        Log.i(TAG, "Navigation entered notification pane");
    }

    /** Trackpad click/Enter enters, directional navigation never switches panes. */
    private boolean enterNotificationPane() {
        if (notificationRows.isEmpty()) return false;
        enterNotificationPaneFocus();
        return true;
    }

    /** Back restores the previously selected app icon. */
    private void returnToAppIcons() {
        notificationPaneActive = false;
        for (FrameLayout icon : iconRows) {
            icon.setFocusable(true);
            icon.setFocusableInTouchMode(true);
        }
        updateNotificationHighlight();
        focusSelectedAppIcon();
        Log.i(TAG, "Navigation returned to app icons");
    }

    private void focusSelectedNotification() {
        int index = NotificationFocusPolicy.indexOf(renderedCards, selectedNotificationKey);
        if (index < 0 || index >= notificationRows.size()) return;
        LinearLayout target = notificationRows.get(index);
        target.requestFocus();
        updateNotificationHighlight();
    }

    private void selectNotification(String key) {
        if (Objects.equals(selectedNotificationKey, key)) {
            updateNotificationHighlight();
            return;
        }
        selectedNotificationKey = key;
        updateNotificationHighlight();
        Log.i(TAG, "Selected notification index="
                + NotificationFocusPolicy.indexOf(renderedCards, key)
                + " of " + renderedCards.size());
    }

    private void updateNotificationHighlight() {
        for (int i = 0; i < notificationRows.size(); i++) {
            LinearLayout row = notificationRows.get(i);
            boolean selected = notificationPaneActive
                    && Objects.equals(renderedCards.get(i).key, selectedNotificationKey);
            row.setSelected(selected);
            GradientDrawable background;
            if (selected) {
                background = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.rgb(91, 180, 231), Color.rgb(20, 95, 169)});
                background.setStroke(dp(1), Color.rgb(180, 226, 251));
            } else {
                background = new GradientDrawable();
                background.setColor(Color.rgb(40, 52, 66));
            }
            background.setCornerRadius(dp(5));
            row.setBackground(background);
        }
    }

    private boolean onNotificationKey(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int current = NotificationFocusPolicy.indexOf(
                            renderedCards, selectedNotificationKey);
                    int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = AppSelectionPolicy.move(current, direction, notificationRows.size());
                    if (next >= 0) notificationRows.get(next).requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) view.performClick();
                return true;
            default:
                return false;
        }
    }

    private TextView text(String value, float size, int color) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    /**
     * Swipe-up dismiss remains available when the column has no more
     * content below. If it can scroll further, ordinary scrolling wins.
     * A gesture that begins while scrollable cannot dismiss mid-scroll.
     */
    private void addSwipeUpDismiss(ScrollView scroller) {
        scroller.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            private boolean canDismiss;
            private boolean dismissed;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        canDismiss = !view.canScrollVertically(1);
                        dismissed = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (canDismiss && !dismissed
                                && startY - event.getRawY() > dp(65)) {
                            dismissed = true;
                            onClose.onClose();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    private void updateSelectedHighlight() {
        for (int i = 0; i < iconRows.size(); i++) {
            FrameLayout row = iconRows.get(i);
            boolean selected = renderedPackages.get(i).equals(selectedPackage);
            row.setSelected(selected);
            if (selected) {
                GradientDrawable blue = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{Color.rgb(91, 180, 231), Color.rgb(20, 95, 169)});
                blue.setCornerRadius(dp(3));
                blue.setStroke(dp(1), Color.rgb(180, 226, 251));
                row.setBackground(blue);
            } else {
                row.setBackground(null);
            }
        }
    }

    private boolean onIconKey(View view, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int selectedIndex = renderedPackages.indexOf(selectedPackage);
                    int direction = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                    int next = AppSelectionPolicy.move(selectedIndex,
                            direction, iconRows.size());
                    if (next >= 0) iconRows.get(next).requestFocus();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Back, not Left/Right, changes navigation levels.
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    view.performClick();
                }
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackingClose = true;
                touchStartY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (trackingClose && touchStartY - event.getRawY() >= dp(65)) {
                    trackingClose = false;
                    onClose.onClose();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackingClose = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (notificationPaneActive) returnToAppIcons();
                else onClose.onClose();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
