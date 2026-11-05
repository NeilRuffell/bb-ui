package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class AppSelectionPolicyTest {
    @Test public void firstAppSelectedWhenInitialListAppears() {
        assertEquals(0, AppSelectionPolicy.afterUpdate(null, -1, Arrays.asList("a", "b")));
    }

    @Test public void updatesPreserveSelectedPackageWhenOrderChanges() {
        assertEquals(2, AppSelectionPolicy.afterUpdate("b", 0, Arrays.asList("c", "a", "b")));
    }

    @Test public void removalMovesToClosestRemainingIndex() {
        assertEquals(1, AppSelectionPolicy.afterUpdate("b", 1, Arrays.asList("a", "c")));
        assertEquals(0, AppSelectionPolicy.afterUpdate("c", 2, Collections.singletonList("a")));
    }

    @Test public void emptyListClearsSelection() {
        assertEquals(-1, AppSelectionPolicy.afterUpdate("a", 0, Collections.emptyList()));
    }

    @Test public void verticalNavigationStopsAtBounds() {
        assertEquals(0, AppSelectionPolicy.move(0, -1, 4));
        assertEquals(1, AppSelectionPolicy.move(0, 1, 4));
        assertEquals(3, AppSelectionPolicy.move(3, 1, 4));
        assertEquals(-1, AppSelectionPolicy.move(0, 1, 0));
    }
    @Test public void selectedIconHighlightsOnlyWhileItHasFocus() {
        assertTrue(AppSelectionPolicy.shouldHighlight("a", "a", true, false));
        assertFalse(AppSelectionPolicy.shouldHighlight("a", "a", false, false));
        assertFalse(AppSelectionPolicy.shouldHighlight("a", "a", true, true));
    }

    @Test public void selectionWithoutMatchingFocusedRowIsNotHighlighted() {
        assertFalse(AppSelectionPolicy.shouldHighlight("a", "b", true, false));
        assertFalse(AppSelectionPolicy.shouldHighlight("a", null, true, false));
    }

}
