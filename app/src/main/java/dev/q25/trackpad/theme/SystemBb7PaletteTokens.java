package dev.q25.trackpad.theme;

import java.util.Locale;

/**
 * Pure, VM-testable semantic colour name mapper.
 * Values correspond to the shared presentation roles in
 * SystemBb7ResourceColors; no Android or Xposed dependencies.
 */
final class SystemBb7PaletteTokens {
    static final int NONE = 0;
    static final int CANVAS = 1;
    static final int CARD = 2;
    static final int ACTION = 3;
    static final int TITLE = 4;
    static final int BODY = 5;
    static final int MUTED = 6;
    static final int DIVIDER = 7;
    static final int BLUE = 8;
    static final int ON_BLUE = 9;

    private SystemBb7PaletteTokens() {}

    static int classify(String entry) {
        if (entry == null) return NONE;
        String n = entry.toLowerCase(Locale.ROOT).replace("_", "");
        if ("settingslibtextcolorprimary".equals(n)) return TITLE;
        if ("settingslibtextcolorsecondary".equals(n)) return BODY;
        // Only designated shared theme tokens. No app-specific colours.
        if (!(n.startsWith("materialcolor")
                || n.startsWith("settingslibmaterialcolor")
                || n.startsWith("colorsurface")
                || n.startsWith("coloronsurface")
                || n.equals("colorbackground")
                || n.equals("colorprimary")
                || n.equals("coloraccent")
                || n.equals("coloroutline"))) return NONE;
        if (n.contains("error") || n.contains("warning")
                || n.contains("success") || n.contains("tertiary")) return NONE;
        if (n.contains("onsurfacevariant")) return BODY;
        if (n.contains("onbackground") || n.contains("onsurface"))
            return TITLE;
        if (n.contains("onprimary") || n.contains("onsecondary")) return ON_BLUE;
        if (n.contains("outline") || n.contains("divider")) return DIVIDER;
        if (n.contains("primarycontainer")) return CARD;
        if (n.contains("secondarycontainer")) return ACTION;
        if (n.contains("primary") || n.contains("accent")) return BLUE;
        if (n.contains("secondary")) return BODY;
        if (n.contains("background")) return CANVAS;
        if (n.contains("surfacecontainerhigh") || n.contains("surfacebright"))
            return ACTION;
        if (n.contains("surfacecontainerlowest") || n.contains("surfacedim"))
            return CANVAS;
        if (n.contains("surface")) return CARD;
        return NONE;
    }
}
