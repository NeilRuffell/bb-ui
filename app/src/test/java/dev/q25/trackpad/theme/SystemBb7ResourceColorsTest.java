package dev.q25.trackpad.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SystemBb7ResourceColorsTest {
    @Test public void mapsSharedNativePaletteTokens() {
        assertEquals(4, SystemBb7PaletteTokens.classify("settingslib_text_color_primary"));
        assertEquals(5, SystemBb7PaletteTokens.classify("settingslib_text_color_secondary"));
        assertEquals(5, SystemBb7PaletteTokens.classify("materialColorOnSurfaceVariant"));
        assertEquals(3, SystemBb7PaletteTokens.classify("materialColorSurfaceContainerHighest"));
        assertEquals(2, SystemBb7PaletteTokens.classify("materialColorSurfaceContainer"));
        assertEquals(8, SystemBb7PaletteTokens.classify("settingslib_materialColorPrimary"));
        assertEquals(1, SystemBb7PaletteTokens.classify("materialColorBackground"));
    }

    @Test public void leavesSemanticErrorsAndAppSpecificColorsUntouched() {
        assertEquals(0, SystemBb7PaletteTokens.classify("materialColorError"));
        assertEquals(0, SystemBb7PaletteTokens.classify("materialColorOnError"));
        assertEquals(0, SystemBb7PaletteTokens.classify("materialColorTertiary"));
        assertEquals(0, SystemBb7PaletteTokens.classify("my_profile_avatar_background"));
        assertEquals(0, SystemBb7PaletteTokens.classify("conversation_primary"));
        assertEquals(0, SystemBb7PaletteTokens.classify(null));
    }
}
