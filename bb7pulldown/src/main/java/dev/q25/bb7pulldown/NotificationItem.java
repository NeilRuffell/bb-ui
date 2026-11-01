package dev.q25.bb7pulldown;

import java.util.Objects;

/** Immutable, plain-text notification fields used by the custom panel. */
final class NotificationItem {
    final String key;
    final String packageName;
    final String title;
    final String text;
    final long postedAt;

    NotificationItem(String key, String packageName, String title, String text, long postedAt) {
        this.key = key;
        this.packageName = packageName;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.postedAt = postedAt;
    }

    @Override public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof NotificationItem)) return false;
        NotificationItem other = (NotificationItem) object;
        return postedAt == other.postedAt && Objects.equals(key, other.key)
                && Objects.equals(packageName, other.packageName)
                && Objects.equals(title, other.title) && Objects.equals(text, other.text);
    }

    @Override public int hashCode() {
        return Objects.hash(key, packageName, title, text, postedAt);
    }
}
