package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatusBarColorIconPolicyTest {
    @Test public void matchedNativeStatusIconUsesFullColourApplicationArt() {
        assertTrue(StatusBarColorIconPolicy.eligible("key1", "key1",
                "org.example.messages", false));
    }

    @Test public void iconBelongingToAnotherNotificationIsNotReplaced() {
        assertFalse(StatusBarColorIconPolicy.eligible("key1", "key2",
                "org.example.messages", false));
        assertFalse(StatusBarColorIconPolicy.eligible(null, null,
                "org.example.messages", false));
    }

    @Test public void importantConversationAvatarStaysNative() {
        assertFalse(StatusBarColorIconPolicy.eligible("key1", "key1",
                "org.example.messages", true));
    }

    @Test public void missingOrBlankPackageKeepsNativeArt() {
        assertFalse(StatusBarColorIconPolicy.eligible("key1", "key1", null, false));
        assertFalse(StatusBarColorIconPolicy.eligible("key1", "key1", "", false));
        assertFalse(StatusBarColorIconPolicy.eligible("key1", "key1", "  ", false));
    }
}
