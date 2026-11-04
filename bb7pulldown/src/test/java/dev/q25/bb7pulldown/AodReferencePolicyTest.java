package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** Regression for SystemUI crash in AodBurnInLayer.transform, 2026-11-03. */
public class AodReferencePolicyTest {
    @Test public void directAodLayerMemberRequiresUnregisterBeforeDetach() {
        assertEquals(AodReferencePolicy.UNREGISTER_THEN_DETACH,
                AodReferencePolicy.plan(42, new int[] {7, 42, 13}, false, false));
    }

    @Test public void independentViewCanBeDetached() {
        assertEquals(AodReferencePolicy.DETACH,
                AodReferencePolicy.plan(42, new int[] {7, 13}, false, false));
    }

    @Test public void unavailableBurnInLayerIsFailClosed() {
        assertEquals(AodReferencePolicy.BLOCK,
                AodReferencePolicy.plan(42, null, false, false));
    }

    @Test public void unresolvedNativeReferenceBlocksMutation() {
        assertEquals(AodReferencePolicy.BLOCK,
                AodReferencePolicy.plan(42, new int[] {7, 42}, true, false));
    }

    @Test public void referencedDescendantPreventsParentDetachment() {
        assertEquals(AodReferencePolicy.BLOCK,
                AodReferencePolicy.plan(42, new int[] {7, 13}, false, true));
    }

    @Test public void viewWithoutIdIsNotDetachable() {
        assertEquals(AodReferencePolicy.BLOCK,
                AodReferencePolicy.plan(-1, new int[] {7, 13}, false, false));
    }

    @Test public void emptyLayerReferenceListDoesNotBlockIndependentView() {
        assertEquals(AodReferencePolicy.DETACH,
                AodReferencePolicy.plan(42, new int[0], false, false));
    }
}
