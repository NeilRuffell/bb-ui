package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Presentation-only favorites. Rules cannot affect notification posting,
 * importance, delivery, actions, or dismissal.
 */
final class FavoritesRules {
    static final String TYPE_CONVERSATION = "conversation";
    static final String TYPE_TITLE_CONTAINS = "title_contains";

    boolean enabled;
    final List<AppRule> apps = new ArrayList<>();

    static final class Rule {
        final String type;
        final String value;

        Rule(String type, String value) {
            this.type = type == null ? "" : type;
            this.value = value == null ? "" : value.trim();
        }

        boolean matches(NotificationItem item) {
            if (item == null || value.isEmpty()) return false;
            if (TYPE_CONVERSATION.equals(type)) {
                return item.conversationIds.contains(value);
            }
            if (TYPE_TITLE_CONTAINS.equals(type)) {
                String expected = value.toLowerCase(Locale.ROOT);
                return item.title.toLowerCase(Locale.ROOT).contains(expected)
                        || item.conversationTitle.toLowerCase(Locale.ROOT).contains(expected);
            }
            return false;
        }
    }

    static final class AppRule {
        final String packageName;
        boolean onlyMatching;
        boolean matchAll;
        final List<Rule> rules = new ArrayList<>();

        AppRule(String packageName) {
            this.packageName = packageName == null ? "" : packageName;
        }

        boolean matches(NotificationItem item) {
            if (item == null || !packageName.equals(item.packageName)) return false;
            if (!onlyMatching) return true;
            if (rules.isEmpty()) return false;
            if (matchAll) {
                for (Rule rule : rules) {
                    if (!rule.matches(item)) return false;
                }
                return true;
            }
            for (Rule rule : rules) {
                if (rule.matches(item)) return true;
            }
            return false;
        }
    }

    AppRule find(String packageName) {
        for (AppRule app : apps) {
            if (app.packageName.equals(packageName)) return app;
        }
        return null;
    }

    boolean matches(NotificationItem item) {
        if (!enabled || item == null) return false;
        AppRule app = find(item.packageName);
        return app != null && app.matches(item);
    }

    /** Suppress group summaries only when a matching child is also visible. */
    List<NotificationItem> filter(List<NotificationItem> notifications) {
        if (!enabled || notifications == null || notifications.isEmpty())
            return Collections.emptyList();
        List<NotificationItem> matches = new ArrayList<>();
        for (NotificationItem item : notifications) {
            if (matches(item)) matches.add(item);
        }
        return NotificationSelectionPolicy.withoutRedundantGroupSummaries(matches);
    }
}
