package dev.q25.bb7pulldown;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native BB UI settings: shade organization and Favorites filtering. */
public final class FavoritesSettingsActivity extends Activity {
    private FavoritesRules settings;
    private String organizationMode = NotificationShadeModePolicy.APPS;
    private LinearLayout content;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("BB UI Settings");
        settings = FavoritesSettingsProvider.loadLocal(this);
        organizationMode = FavoritesSettingsProvider.loadModeLocal(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(26));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null) {
            settings = FavoritesSettingsProvider.loadLocal(this);
            organizationMode = FavoritesSettingsProvider.loadModeLocal(this);
            render();
        }
    }

    private void save() {
        FavoritesSettingsProvider.saveLocal(this, settings);
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        if (bold) result.setTypeface(null, Typeface.BOLD);
        result.setPadding(0, dp(5), 0, dp(5));
        return result;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button result = new Button(this);
        result.setText(label);
        result.setAllCaps(false);
        result.setOnClickListener(listener);
        return result;
    }

    private void render() {
        content.removeAllViews();
        content.addView(text("Notification shade", 22, true));
        content.addView(text(
                "Choose how notifications are organized in the left column.", 13, false));

        RadioGroup organization = new RadioGroup(this);
        organization.setOrientation(LinearLayout.VERTICAL);
        RadioButton byApplication = new RadioButton(this);
        byApplication.setId(View.generateViewId());
        byApplication.setText("By application");
        RadioButton byType = new RadioButton(this);
        byType.setId(View.generateViewId());
        byType.setText("By notification type (Hub)");
        organization.addView(byApplication);
        organization.addView(byType);
        organization.check(NotificationShadeModePolicy.TYPES.equals(organizationMode)
                ? byType.getId() : byApplication.getId());
        organization.setOnCheckedChangeListener((group, checkedId) -> {
            String next = checkedId == byType.getId()
                    ? NotificationShadeModePolicy.TYPES : NotificationShadeModePolicy.APPS;
            if (next.equals(organizationMode)) return;
            organizationMode = next;
            FavoritesSettingsProvider.saveModeLocal(this, next);
        });
        content.addView(organization);
        content.addView(text(
                "Hub mode shows persistent Messages, Email, Calls, Calendar, Reminders, "
                        + "Alarms and Other icons. Empty categories stay visible.",
                12, false));

        TextView favoritesHeader = text("Favorite notifications", 20, true);
        favoritesHeader.setPadding(0, dp(26), 0, dp(5));
        content.addView(favoritesHeader);
        content.addView(text(
                "Pin apps or specific conversations to a dedicated Favorites icon in the BB UI shade.",
                13, false));

        Switch enable = new Switch(this);
        enable.setText("Show Favorites in the notification shade");
        enable.setChecked(settings.enabled);
        enable.setPadding(0, dp(14), 0, dp(14));
        enable.setOnCheckedChangeListener((view, checked) -> {
            settings.enabled = checked;
            save();
        });
        content.addView(enable);

        content.addView(button("Add application", view -> showAppPicker()));

        if (settings.apps.isEmpty()) {
            content.addView(text(
                    "No applications configured. Add an app to include its notifications.",
                    13, false));
        }
        // Copy prevents UI rebuilding from invalidating the iteration.
        for (FavoritesRules.AppRule app : new ArrayList<>(settings.apps)) {
            renderApp(app);
        }
        TextView footer = text(
                "Favorites only changes how notifications are displayed. It does not block, silence, "
                        + "duplicate, or dismiss Android notifications.", 12, false);
        footer.setPadding(0, dp(18), 0, 0);
        content.addView(footer);
    }

    private void renderApp(FavoritesRules.AppRule app) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(10), dp(12), dp(10), dp(14));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionParams.topMargin = dp(10);
        content.addView(section, sectionParams);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        ImageView appIcon = new ImageView(this);
        try {
            appIcon.setImageDrawable(getPackageManager().getApplicationIcon(app.packageName));
        } catch (PackageManager.NameNotFoundException error) {
            appIcon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
        }
        header.addView(appIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView title = text(appLabel(app.packageName), 16, true);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        section.addView(header);

        Switch matching = new Switch(this);
        matching.setText("Only notifications matching filters");
        matching.setChecked(app.onlyMatching);
        matching.setOnCheckedChangeListener((view, checked) -> {
            app.onlyMatching = checked;
            save();
            render();
        });
        section.addView(matching);

        if (app.onlyMatching) {
            section.addView(text(
                    "Rules use native conversation IDs where available, or a case-insensitive title match.",
                    12, false));

            if (app.rules.size() > 1) {
                Switch matchAll = new Switch(this);
                matchAll.setText("Require ALL filters (otherwise match ANY)");
                matchAll.setChecked(app.matchAll);
                matchAll.setOnCheckedChangeListener((view, checked) -> {
                    app.matchAll = checked;
                    save();
                });
                section.addView(matchAll);
            }

            if (app.rules.isEmpty()) {
                section.addView(text("No rules yet — this app currently matches nothing.", 12, false));
            }
            for (int i = 0; i < app.rules.size(); i++) {
                final int index = i;
                FavoritesRules.Rule rule = app.rules.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                String type = FavoritesRules.TYPE_CONVERSATION.equals(rule.type)
                        ? "Conversation" : "Title contains";
                TextView label = text(type + ": " + displayRuleValue(app.packageName, rule),
                        13, false);
                row.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(button("Remove", view -> {
                    app.rules.remove(index);
                    save();
                    render();
                }));
                section.addView(row);
            }
        }

        section.addView(button("Add filter", view -> showFilterPicker(app)));
        section.addView(button("Remove application", view -> {
            settings.apps.remove(app);
            save();
            render();
        }));

        View divider = new View(this);
        divider.setBackgroundColor(0x556e8498);
        section.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private String displayRuleValue(String pkg, FavoritesRules.Rule rule) {
        if (!FavoritesRules.TYPE_CONVERSATION.equals(rule.type)) return rule.value;
        JSONArray observed = FavoritesSettingsProvider.observedLocal(this);
        for (int i = 0; i < observed.length(); i++) {
            JSONObject value = observed.optJSONObject(i);
            if (value != null && pkg.equals(value.optString("package"))
                    && rule.value.equals(value.optString("id"))) {
                String label = value.optString("label", "").trim();
                if (!label.isEmpty()) return label + " (" + rule.value + ")";
            }
        }
        return rule.value;
    }

    private String appLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException error) {
            return packageName;
        }
    }

    private void showFilterPicker(FavoritesRules.AppRule app) {
        new AlertDialog.Builder(this)
                .setTitle("Add filter for " + appLabel(app.packageName))
                .setItems(new String[]{"Select native conversation", "Notification title contains text"},
                        (dialog, choice) -> {
                            if (choice == 0) showConversationPicker(app);
                            else showTitleRuleEditor(app);
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addRule(FavoritesRules.AppRule app, String type, String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) return;
        for (FavoritesRules.Rule existing : app.rules) {
            if (existing.type.equals(type) && existing.value.equals(trimmed)) return;
        }
        app.rules.add(new FavoritesRules.Rule(type, trimmed));
        // Adding a rule immediately limits this app to configured matches.
        app.onlyMatching = true;
        save();
        render();
    }

    private void showTitleRuleEditor(FavoritesRules.AppRule app) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("e.g. Weekend group chat");
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(16), 0, dp(16), 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Title contains")
                .setView(wrapper)
                .setPositiveButton("Add", (dialog, which) -> addRule(app,
                        FavoritesRules.TYPE_TITLE_CONTAINS, input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConversationPicker(FavoritesRules.AppRule app) {
        JSONArray observed = FavoritesSettingsProvider.observedLocal(this);
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < observed.length(); i++) {
            JSONObject item = observed.optJSONObject(i);
            if (item == null || !app.packageName.equals(item.optString("package"))) continue;
            String id = item.optString("id", "").trim();
            if (id.isEmpty() || ids.contains(id)) continue;
            ids.add(id);
            String label = item.optString("label", "").trim();
            labels.add((label.isEmpty() ? "Conversation" : label) + " — " + id);
        }
        if (ids.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No native conversations detected")
                    .setMessage("Open the BB UI notification shade while this app has an "
                            + "active conversation notification, then return here. "
                            + "If this app does not publish conversation IDs, use the "
                            + "notification-title filter instead.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose conversation")
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        addRule(app, FavoritesRules.TYPE_CONVERSATION, ids.get(index)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(launcher, 0);
        Map<String, String> available = new LinkedHashMap<>();
        for (ResolveInfo info : activities) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (settings.find(pkg) != null || available.containsKey(pkg)) continue;
            CharSequence label = info.loadLabel(pm);
            available.put(pkg, label == null ? pkg : label.toString());
        }
        List<String> packages = new ArrayList<>(available.keySet());
        Collections.sort(packages, Comparator.comparing(
                pkg -> available.get(pkg).toLowerCase(Locale.ROOT)));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search installed apps");
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        layout.addView(search);
        ListView list = new ListView(this);
        layout.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));

        List<String> filtered = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        Runnable applyFilter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            filtered.clear();
            adapter.clear();
            for (String pkg : packages) {
                String label = available.get(pkg);
                if (label.toLowerCase(Locale.ROOT).contains(query)
                        || pkg.toLowerCase(Locale.ROOT).contains(query)) {
                    filtered.add(pkg);
                    adapter.add(label + " (" + pkg + ")");
                }
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        applyFilter.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add favorite application")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= filtered.size()) return;
            String pkg = filtered.get(position);
            if (settings.find(pkg) != null) return;
            settings.apps.add(new FavoritesRules.AppRule(pkg));
            save();
            dialog.dismiss();
            render();
        });
        dialog.show();
    }
}
