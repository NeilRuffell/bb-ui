package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KeyguardSpacePolicyTest {
    private final KeyguardSpacePolicy policy = new KeyguardSpacePolicy();

    @Test public void lockedSpaceRequestsNativeEntryImmediatelyOnDown() {
        assertEquals(KeyguardSpacePolicy.REQUEST_NATIVE_ENTRY,
                policy.route(0, 100, 0, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 100, 0, true, false, false));
    }

    @Test public void repeatAndReleaseDoNotReopenBouncer() {
        assertEquals(KeyguardSpacePolicy.REQUEST_NATIVE_ENTRY,
                policy.route(0, 200, 0, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 200, 1, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 200, 0, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 200, 0, true, false, false));
    }

    @Test public void newlyFocusedEditorDoesNotReceivePreviouslyInterceptedUp() {
        assertEquals(KeyguardSpacePolicy.REQUEST_NATIVE_ENTRY,
                policy.route(0, 300, 0, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 300, 0, true, true, true));
    }

    @Test public void passwordSpaceIsPreserved() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 400, 0, true, true, true));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 400, 0, true, true, true));
    }

    @Test public void unknownBouncerVisibilityNeverStealsEditorSpace() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 450, 0, true, false, true));
    }

    @Test public void visiblePinBouncerKeepsKeyHandling() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 500, 0, true, true, false));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 500, 0, true, true, false));
    }

    @Test public void unlockedSpaceIsUnchanged() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 600, 0, false, false, false));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 600, 0, false, false, false));
    }

    @Test public void orphanSpaceUpCannotClickNotificationWhenLocked() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 700, 0, true, false, false));
    }

    @Test public void unlockingBetweenDownAndUpCannotClickThrough() {
        assertEquals(KeyguardSpacePolicy.REQUEST_NATIVE_ENTRY,
                policy.route(0, 800, 0, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 800, 0, false, false, false));
    }
}
