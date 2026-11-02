package dev.q25.trackpad.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SystemBb7PackagePolicyTest {
    @Test public void stylesPlatformSystemApps() {
        assertTrue(SystemBb7PackagePolicy.allows("com.android.settings", true));
        assertTrue(SystemBb7PackagePolicy.allows("com.android.systemui", true));
        assertTrue(SystemBb7PackagePolicy.allows("com.android.launcher3", true));
        assertTrue(SystemBb7PackagePolicy.allows("org.lineageos.recorder", true));
        assertTrue(SystemBb7PackagePolicy.allows(
                "com.google.android.permissioncontroller", true));
        assertTrue(SystemBb7PackagePolicy.allows("android", true));
    }

    @Test public void neverStylesNonSystemInstalledApps() {
        assertFalse(SystemBb7PackagePolicy.allows("com.android.settings", false));
        assertFalse(SystemBb7PackagePolicy.allows("org.lineageos.recorder", false));
    }

    @Test public void excludesThirdPartyUisEvenWhenPreinstalled() {
        assertFalse(SystemBb7PackagePolicy.allows("com.google.android.apps.messaging", true));
        assertFalse(SystemBb7PackagePolicy.allows("com.whatsapp", true));
        assertFalse(SystemBb7PackagePolicy.allows("com.discord", true));
        assertFalse(SystemBb7PackagePolicy.allows("com.microsoft.office.outlook", true));
        assertFalse(SystemBb7PackagePolicy.allows("dev.q25.trackpad", true));
        assertFalse(SystemBb7PackagePolicy.allows(null, true));
    }

    @Test public void cannotBeFooledByNamePrefixWithoutDot() {
        assertFalse(SystemBb7PackagePolicy.allows("com.androidfake.settings", true));
        assertFalse(SystemBb7PackagePolicy.allows("org.lineageosfake.app", true));
    }
}
