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

    /**
     * The opening DOWN was delivered to native SystemUI before the BB host
     * mounted. Do not forward its later MOVE/UP to BB without a matching DOWN.
     */
    static boolean swallowOpeningGestureTail(boolean mounted,
            boolean startedNative, boolean isNewDown) {
        return mounted && startedNative && !isNewDown;
    }
}
