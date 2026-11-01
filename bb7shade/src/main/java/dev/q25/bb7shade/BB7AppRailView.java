package dev.q25.bb7shade;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BlackBerry OS 7-style notification application rail.
 *
 * This is a reusable native View, not an overlay or another shade window.
 * It is NOT installed in the SystemUI view hierarchy yet. The future
 * single-surface host will place it to the left of Android's existing
 * NotificationStackScrollLayout and keep the original notification actions.
 *
 * Items use Android's normal focus system. No DPAD, touch or trackpad key
 * events are intercepted, and the native notification list is not mutated.
 */
final class BB7AppRailView extends ScrollView {
    interface OnApplicationSelectedListener {
        /** packageName=null selects all notifications. */
        void onApplicationSelected(String packageName);
    }

    private static final String ALL = "";
    private static final int TOP = 0xff459fd9;
    private static final int MIDDLE = 0xff3285c5;
    private static final int BOTTOM = 0xff2368aa;
    private static final int PLAIN = 0x00232835;
    private static final int BACKGROUND = 0xff202832;
    private static final int FOREGROUND = Color.WHITE;
    private static final int BADGE = 0xff2d3c4c;

    private final LinearLayout items;
    private final ShadeNavigationState navigationState;
    private final Map<String, RailItem> byPackage = new LinkedHashMap<>();
    private OnApplicationSelectedListener selectionListener;

    BB7AppRailView(Context context, ShadeNavigationState state) {
        super(context);
        navigationState = state;
        setFillViewport(false);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(BACKGROUND);
        setFocusable(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setFocusable(false);
        addView(items, new ScrollView.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        byPackage.put(ALL, createItem(ALL, null));
        items.addView(byPackage.get(ALL));
    }

    void setOnApplicationSelectedListener(OnApplicationSelectedListener listener) {
        selectionListener = listener;
    }

    /** Returns the recommended narrow width; caller owns actual constraints. */
    int preferredWidthPx() {
        return dp(58);
    }

    /**
     * Reconcile updates in place so a notification arrival does not destroy
     * the focused icon or reset trackpad selection.
     *
     * This view only consumes the immutable derived snapshot. All Android
     * notification rows remain under SystemUI ownership.
     */
    void update(ShadeNotificationIndex.Snapshot snapshot) {
        String previousSelection = navigationState.selectedPackage();
        navigationState.updatePackages(snapshot.appCounts.keySet());
        if (!same(previousSelection, navigationState.selectedPackage())
                && selectionListener != null) {
            selectionListener.onApplicationSelected(navigationState.selectedPackage());
        }

        List<String> desired = new ArrayList<>();
        desired.add(ALL);
        desired.addAll(snapshot.appCounts.keySet());

        for (int i = items.getChildCount() - 1; i >= 0; i--) {
            RailItem item = (RailItem) items.getChildAt(i);
            if (!desired.contains(item.packageName)) {
                items.removeViewAt(i);
                byPackage.remove(item.packageName);
            }
        }

        for (int i = 0; i < desired.size(); i++) {
            String packageName = desired.get(i);
            RailItem item = byPackage.get(packageName);
            if (item == null) {
                item = createItem(packageName, packageName);
                byPackage.put(packageName, item);
            }
            if (item.getParent() == null) {
                items.addView(item, Math.min(i, items.getChildCount()));
            } else if (items.indexOfChild(item) != i) {
                // Reordering the actual view is rare; preserve focus if
                // SystemUI changed app order when notifications updated.
                boolean focused = item.isFocused();
                items.removeView(item);
                items.addView(item, Math.min(i, items.getChildCount()));
                if (focused) item.requestFocus();
            }
            int count = ALL.equals(packageName)
                    ? snapshot.notificationCount
                    : snapshot.appCounts.get(packageName);
            item.setCount(count);
        }
        updateSelectedBackgrounds();
    }

    private RailItem createItem(String key, String packageName) {
        final RailItem item = new RailItem(getContext(), key,
                loadIcon(packageName), getAppLabel(packageName));
        item.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) select(item.packageName);
        });
        item.setOnClickListener(view -> select(item.packageName));
        return item;
    }

    private void select(String key) {
        String packageName = ALL.equals(key) ? null : key;
        if (same(navigationState.selectedPackage(), packageName)) return;
        navigationState.selectPackage(packageName);
        updateSelectedBackgrounds();
        if (selectionListener != null) {
            selectionListener.onApplicationSelected(packageName);
        }
    }

    private void updateSelectedBackgrounds() {
        String selected = navigationState.selectedPackage() == null
                ? ALL : navigationState.selectedPackage();
        for (RailItem item : byPackage.values()) {
            item.setChosen(item.packageName.equals(selected));
        }
    }

    private Drawable loadIcon(String packageName) {
        if (packageName == null) {
            return getContext().getDrawable(android.R.drawable.ic_dialog_info);
        }
        try {
            return getContext().getPackageManager().getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return getContext().getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private String getAppLabel(String packageName) {
        if (packageName == null) return "All notifications";
        PackageManager manager = getContext().getPackageManager();
        try {
            return manager.getApplicationLabel(
                    manager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException notInstalled) {
            return packageName;
        }
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private int dp(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private final class RailItem extends FrameLayout {
        final String packageName;
        final String label;
        final TextView countView;

        RailItem(Context context, String packageName, Drawable icon, String label) {
            super(context);
            this.packageName = packageName;
            this.label = label;
            setFocusable(true);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setBackgroundColor(PLAIN);

            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(icon);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LayoutParams iconParams = new LayoutParams(dp(31), dp(31), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            iconParams.topMargin = dp(5);
            addView(iconView, iconParams);
            iconView.setFocusable(false);

            countView = new TextView(context);
            countView.setGravity(Gravity.CENTER);
            countView.setTextSize(11);
            countView.setTextColor(FOREGROUND);
            GradientDrawable countBackground = new GradientDrawable();
            countBackground.setColor(BADGE);
            countBackground.setCornerRadius(dp(7));
            countView.setBackground(countBackground);
            LayoutParams countParams = new LayoutParams(dp(31), dp(17),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            countParams.bottomMargin = dp(4);
            addView(countView, countParams);
            countView.setFocusable(false);

            setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        }

        void setCount(int count) {
            countView.setText(String.valueOf(count));
            setContentDescription(label + ", " + count + " notifications");
        }

        void setChosen(boolean chosen) {
            GradientDrawable bg;
            if (chosen) {
                bg = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{TOP, MIDDLE, BOTTOM});
            } else {
                bg = new GradientDrawable();
                bg.setColor(PLAIN);
            }
            bg.setCornerRadius(dp(4));
            setBackground(bg);
            countView.setTextColor(FOREGROUND);
            invalidate();
        }
    }
}
