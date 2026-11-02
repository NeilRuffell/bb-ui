package dev.q25.trackpad.theme;

/** Pure package-name policy; Android package flags are checked separately. */
final class SystemBb7PackagePolicy {
    private SystemBb7PackagePolicy() {}

    static boolean isPlatformUiPackage(String packageName) {
        return packageName != null
                && ("android".equals(packageName)
                    || packageName.startsWith("com.android.")
                    || packageName.startsWith("org.lineageos.")
                    || "com.google.android.permissioncontroller".equals(packageName)
                    || "com.google.android.packageinstaller".equals(packageName));
    }

    static boolean allows(String packageName, boolean installedAsSystem) {
        return installedAsSystem && isPlatformUiPackage(packageName);
    }
}
