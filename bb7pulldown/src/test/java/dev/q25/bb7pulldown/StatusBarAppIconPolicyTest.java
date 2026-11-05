package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatusBarAppIconPolicyTest {
    @Test public void realStatusBarNotificationUsesColorAppIcon() {
        assertTrue(StatusBarAppIconPolicy.shouldReplace(true, "com.example.chat"));
    }

    @Test public void otherIconSurfacesRemainNative() {
        assertFalse(StatusBarAppIconPolicy.shouldReplace(false, "com.example.chat"));
    }

    @Test public void missingPackageDoesNotReplaceNativeIcon() {
        assertFalse(StatusBarAppIconPolicy.shouldReplace(true, null));
        assertFalse(StatusBarAppIconPolicy.shouldReplace(true, ""));
        assertFalse(StatusBarAppIconPolicy.shouldReplace(true, "   "));
    }
}
