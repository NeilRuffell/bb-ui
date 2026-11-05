package dev.q25.bb7pulldown;

/** Pure routing constraints for native-shade migration and safe rollback. */
final class NativeShadeModePolicy {
    private NativeShadeModePolicy() {}

    static boolean useLegacyWindow(boolean requested) { return !requested; }

    static boolean mayMount(boolean requested, boolean ready, boolean locked) {
        return requested && ready && !locked;
    }

    static boolean openFromNative(boolean requested, boolean expanded,
            boolean alreadyOpen, boolean locked) {
        return requested && expanded && !alreadyOpen && !locked;
    }

    static boolean detachOnCollapse(boolean requested, boolean expanded, boolean mounted) {
        return requested && !expanded && mounted;
    }
}
