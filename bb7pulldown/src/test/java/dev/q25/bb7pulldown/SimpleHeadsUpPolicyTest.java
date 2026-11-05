package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class SimpleHeadsUpPolicyTest {
    @Test public void replyAndActionControlsArePopupOnlyHideTargets() {
        assertTrue(Arrays.asList(SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS)
                .containsAll(Arrays.asList("actions", "actions_container",
                        "smart_reply_container", "bubble_button", "snooze_button")));
    }

    @Test public void expandAndCloseButtonsAreHidden() {
        assertTrue(Arrays.asList(SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS)
                .containsAll(Arrays.asList("expand_button", "close_button")));
    }

    @Test public void HeaderTextAndAppIconRemainPresent() {
        assertFalse(Arrays.asList(SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS)
                .contains("notification_header"));
        assertFalse(Arrays.asList(SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS)
                .contains("icon"));
        assertTrue(Arrays.asList(SimpleHeadsUpPolicy.PASS_THROUGH_VIEW_IDS)
                .contains("notification_header"));
    }

    @Test public void interactionTargetsAndHiddenIdsDoNotOverlap() {
        for (String id : SimpleHeadsUpPolicy.PASS_THROUGH_VIEW_IDS) {
            assertFalse(Arrays.asList(SimpleHeadsUpPolicy.HIDDEN_VIEW_IDS).contains(id));
        }
    }
}
