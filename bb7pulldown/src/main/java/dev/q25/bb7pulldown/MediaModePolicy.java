package dev.q25.bb7pulldown;

/**
 * Explicitly advertised MediaSessionCompat controls only.
 * A missing, unready or invalid session never exposes a repeat/shuffle action.
 */
final class MediaModePolicy {
    static final int SHUFFLE = 0;
    static final int REPEAT = 1;
    static final int REPEAT_NONE = 0;
    static final int REPEAT_ONE = 1;
    static final int REPEAT_ALL = 2;
    static final int SHUFFLE_NONE = 0;
    static final int SHUFFLE_ALL = 1;
    static final int INVALID = -1;

    private MediaModePolicy() {}

    static boolean available(boolean ready, long actions, long requiredAction, int mode) {
        return ready && mode >= 0 && (actions & requiredAction) != 0;
    }

    static int nextShuffle(int current) {
        return current == SHUFFLE_NONE ? SHUFFLE_ALL : SHUFFLE_NONE;
    }

    static int nextRepeat(int current) {
        if (current == REPEAT_NONE) return REPEAT_ALL;
        if (current == REPEAT_ALL) return REPEAT_ONE;
        return REPEAT_NONE;
    }

    /** Show only enabled modes that the currently active session advertises. */
    static boolean showShuffleStatus(boolean canShuffle, int mode) {
        return canShuffle && mode > SHUFFLE_NONE;
    }

    static boolean showRepeatStatus(boolean canRepeat, int mode) {
        return canRepeat && mode > REPEAT_NONE;
    }

    static int firstAvailable(boolean canShuffle, boolean canRepeat) {
        if (canShuffle) return SHUFFLE;
        if (canRepeat) return REPEAT;
        return -1;
    }

    static int stepMode(int current, int direction, boolean canShuffle, boolean canRepeat) {
        if (!canShuffle && !canRepeat) return -1;
        if (current == SHUFFLE && direction > 0 && canRepeat) return REPEAT;
        if (current == REPEAT && direction < 0 && canShuffle) return SHUFFLE;
        if (current == SHUFFLE && canShuffle || current == REPEAT && canRepeat) {
            return current;
        }
        return firstAvailable(canShuffle, canRepeat);
    }
}
