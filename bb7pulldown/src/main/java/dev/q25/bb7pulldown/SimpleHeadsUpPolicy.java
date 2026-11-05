package dev.q25.bb7pulldown;

/** Popup-only view IDs; expanded/contracted notification views are not touched. */
final class SimpleHeadsUpPolicy {
    private SimpleHeadsUpPolicy() {}

    // Android framework notification template IDs, not BB UI shade widgets.
    static final String[] HIDDEN_VIEW_IDS = {
        "actions",
        "actions_container",
        "actions_container_layout",
        "smart_reply_container",
        "notification_material_reply_container",
        "bubble_button",
        "snooze_button",
        "expand_button",
        "alternate_expand_target",
        "close_button"
    };

    // Keep the header's text and icon visible; remove only its expand click.
    static final String[] PASS_THROUGH_VIEW_IDS = {
        "notification_header",
        "notification_top_line",
        "conversation_icon_container"
    };
}
