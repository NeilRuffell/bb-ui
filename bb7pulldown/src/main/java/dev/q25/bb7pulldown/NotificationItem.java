package dev.q25.bb7pulldown;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable notification summary and the actual Android action metadata. */
final class NotificationItem {
    static final class ActionItem {
        final int index;
        final String title;
        final boolean freeFormReply;

        ActionItem(int index, String title, boolean freeFormReply) {
            this.index = index;
            this.title = title == null ? "" : title;
            this.freeFormReply = freeFormReply;
        }

        @Override public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof ActionItem)) return false;
            ActionItem other = (ActionItem) object;
            return index == other.index && freeFormReply == other.freeFormReply
                    && Objects.equals(title, other.title);
        }

        @Override public int hashCode() {
            return Objects.hash(index, title, freeFormReply);
        }
    }

    final String key;
    final String packageName;
    final String title;
    final String text;
    final long postedAt;
    final boolean clearable;
    final List<ActionItem> actions;

    NotificationItem(String key, String packageName, String title, String text, long postedAt) {
        this(key, packageName, title, text, postedAt, false, Collections.emptyList());
    }

    NotificationItem(String key, String packageName, String title, String text,
            long postedAt, boolean clearable, List<ActionItem> actions) {
        this.key = key;
        this.packageName = packageName;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.postedAt = postedAt;
        this.clearable = clearable;
        this.actions = Collections.unmodifiableList(
                actions == null ? Collections.emptyList() : new ArrayList<>(actions));
    }

    @Override public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof NotificationItem)) return false;
        NotificationItem other = (NotificationItem) object;
        return postedAt == other.postedAt && clearable == other.clearable
                && Objects.equals(key, other.key)
                && Objects.equals(packageName, other.packageName)
                && Objects.equals(title, other.title) && Objects.equals(text, other.text)
                && Objects.equals(actions, other.actions);
    }

    @Override public int hashCode() {
        return Objects.hash(key, packageName, title, text, postedAt, clearable, actions);
    }
}
