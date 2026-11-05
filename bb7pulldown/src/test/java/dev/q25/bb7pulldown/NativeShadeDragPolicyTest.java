package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeShadeDragPolicyTest {
    @Test public void fingerFractionClampsWithinWindow() {
        assertEquals(0f, NativeShadeDragPolicy.fraction(-20f, 720f), 0f);
        assertEquals(0.5f, NativeShadeDragPolicy.fraction(360f, 720f), 0f);
        assertEquals(1f, NativeShadeDragPolicy.fraction(900f, 720f), 0f);
        assertEquals(0f, NativeShadeDragPolicy.fraction(10f, 0f), 0f);
    }

    @Test public void onlyUpwardDominantMotionClaimsShade() {
        assertTrue(NativeShadeDragPolicy.isUpwardDrag(2f, -24f, 8f));
        assertFalse(NativeShadeDragPolicy.isUpwardDrag(30f, -24f, 8f));
        assertFalse(NativeShadeDragPolicy.isUpwardDrag(0f, -6f, 8f));
        assertFalse(NativeShadeDragPolicy.isUpwardDrag(0f, 22f, 8f));
    }

    @Test public void releasedOpenGestureUsesFractionOrVelocity() {
        assertTrue(NativeShadeDragPolicy.shouldOpen(.45f, 0f, false));
        assertFalse(NativeShadeDragPolicy.shouldOpen(.1f, 0f, false));
        assertTrue(NativeShadeDragPolicy.shouldOpen(.1f, 700f, false));
        assertFalse(NativeShadeDragPolicy.shouldOpen(.8f, -700f, false));
        assertFalse(NativeShadeDragPolicy.shouldOpen(.9f, 900f, true));
    }

    @Test public void releasedCloseGestureUsesFractionOrVelocity() {
        assertTrue(NativeShadeDragPolicy.shouldClose(.45f, 0f, false));
        assertFalse(NativeShadeDragPolicy.shouldClose(.1f, 0f, false));
        assertTrue(NativeShadeDragPolicy.shouldClose(.1f, -700f, false));
        assertFalse(NativeShadeDragPolicy.shouldClose(.8f, 700f, false));
        assertFalse(NativeShadeDragPolicy.shouldClose(.9f, -900f, true));
    }
}
