package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KeyguardSpacePolicyTest {
    private final KeyguardSpacePolicy policy = new KeyguardSpacePolicy();

    @Test public void lockedSpaceOpensBouncerAndConsumesBothEvents() {
        assertEquals(KeyguardSpacePolicy.REQUEST_BOUNCER,
                policy.route(0, 100, 0, true, false, false, true, false));
        // Focus and bouncer visibility can change before the hardware UP.
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 100, 0, true, true, false, true, true));
        // Subsequent Space is handled by the real credential editor.
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 200, 0, true, true, false, true, true));
    }

    @Test public void lockedSpaceNeverReachesFocusedQsDuringUnavailableManager() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 200, 0, true, false, false, false, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 200, 0, true, false, false, false, false));
    }

    @Test public void bouncerTransitionConsumesSpaceWithoutRepeatingRequest() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 300, 0, true, false, true, true, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 300, 0, true, false, true, true, false));
    }

    @Test public void repeatedPressCannotRequestBouncerTwice() {
        assertEquals(KeyguardSpacePolicy.REQUEST_BOUNCER,
                policy.route(0, 400, 0, true, false, false, true, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(0, 400, 1, true, false, false, true, false));
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 400, 0, true, false, false, true, false));
    }

    @Test public void unlockedSpacePreservesOriginalDispatch() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 500, 0, false, false, false, true, false));
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(1, 500, 0, false, false, false, true, false));
    }

    @Test public void knownPasswordEditorPreservedIfManagerUnavailable() {
        assertEquals(KeyguardSpacePolicy.PASS,
                policy.route(0, 600, 0, true, false, false, false, true));
    }

    @Test public void orphanedUpDoesNotClickLockedQuickSetting() {
        assertEquals(KeyguardSpacePolicy.CONSUME,
                policy.route(1, 700, 0, true, false, false, true, false));
    }
}
