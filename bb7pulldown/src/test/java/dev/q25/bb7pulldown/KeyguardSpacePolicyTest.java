package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Keyboard events must never leak to a previously focused locked QS tile. */
public class KeyguardSpacePolicyTest {
    private final KeyguardSpacePolicy policy = new KeyguardSpacePolicy();

    @Test public void oneLockedPressRequestsNativeBouncerOnRelease() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 100, true, false, false));
        assertEquals(KeyguardSpacePolicy.REQUEST_BOUNCER,
                policy.route(1, 100, true, false, false));
    }

    @Test public void keyRepeatCannotRequestBouncerRepeatedly() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 200, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 200, true, false, false));
        assertEquals(KeyguardSpacePolicy.REQUEST_BOUNCER,
                policy.route(1, 200, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 200, true, false, false));
    }

    @Test public void postFocusChangeUpCannotActivateNotification() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 300, true, false, false));
        // Bouncer appears between DOWN and UP. Matching UP stays consumed.
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 300, true, true, true));
    }

    @Test public void passwordSpaceIsNotIntercepted() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 400, true, true, true));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 400, true, true, true));
    }

    @Test public void passwordEditorProtectsSpaceEvenIfVisibilityApiUnavailable() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 450, true, false, true));
    }

    @Test public void pinBouncerRetainsKeyboardNavigation() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 500, true, true, false));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 500, true, true, false));
    }

    @Test public void unlockedKeyboardRetainsNativeBehavior() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 600, false, false, false));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 600, false, false, false));
    }

    @Test public void lockedOrphanUpMustNotClickQs() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 700, true, false, false));
    }

    @Test public void unlockBetweenDownAndUpPreventsClickThrough() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 800, true, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 800, false, false, false));
    }
}
