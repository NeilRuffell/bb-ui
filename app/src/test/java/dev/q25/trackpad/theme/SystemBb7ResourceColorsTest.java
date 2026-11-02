package dev.q25.trackpad.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SystemBb7ResourceColorsTest {
    @Test public void mapsSharedNativePaletteTokens() {
        assertEquals(4, SystemBb7ResourceColors.classify("settingslib_text_color_primary"));
        assertEquals(5, SystemBb7ResourceColors.classify("settingslib_text_color_secondary"));
        assertEquals(5, SystemBb7ResourceColors.classify("materialColorOnSurfaceVariant"));
        assertEquals(3, SystemBb7ResourceColors.classify("materialColorSurfaceContainerHighest"));
        assertEquals(2, SystemBb7ResourceColors.classify("materialColorSurfaceContainer"));
        assertEquals(8, SystemBb7ResourceColors.classify("settingslib_materialColorPrimary"));
        assertEquals(1, SystemBb7ResourceColors.classify("materialColorBackground"));
    }

    @Test public void leavesSemanticErrorsAndAppSpecificColorsUntouched() {
        assertEquals(0, SystemBb7ResourceColors.classify("materialColorError"));
        assertEquals(0, SystemBb7ResourceColors.classify("materialColorOnError"));
        assertEquals(0, SystemBb7ResourceColors.classify("materialColorTertiary"));
        assertEquals(0, SystemBb7ResourceColors.classify("my_profile_avatar_background"));
        assertEquals(0, SystemBb7ResourceColors.classify("conversation_primary"));
        assertEquals(0, SystemBb7ResourceColors.classify(null));
    }
}
