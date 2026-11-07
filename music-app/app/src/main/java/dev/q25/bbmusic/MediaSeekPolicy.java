package dev.q25.bbmusic;

import java.util.Locale;

/** Pure position and progress calculations for the native media seek surface. */
final class MediaSeekPolicy {
    static final int PROGRESS_MAX = 1000;
    static final long KEY_SEEK_STEP_MS = 5000L;

    private MediaSeekPolicy() {}

    /** Seekbar highlighting is not equivalent to entering trackpad seek mode. */
    static boolean toggleTrackpadEditing(boolean highlighted, boolean editing,
            boolean canSeek) {
        return highlighted && canSeek && !editing;
    }

    static boolean canTrackpadSeek(boolean highlighted, boolean editing,
            boolean canSeek) {
        return highlighted && editing && canSeek;
    }

    static long clamp(long positionMs, long durationMs) {
        return Math.min(Math.max(0L, positionMs), Math.max(0L, durationMs));
    }

    static long positionAt(long positionMs, long durationMs, long updatedAtMs,
            float speed, boolean advancing, long nowMs) {
        long position = Math.max(0L, positionMs);
        if (advancing && updatedAtMs > 0 && nowMs > updatedAtMs
                && !Float.isNaN(speed) && !Float.isInfinite(speed)) {
            double calculated = position + (nowMs - updatedAtMs) * (double) speed;
            position = calculated >= Long.MAX_VALUE ? Long.MAX_VALUE
                    : calculated <= 0 ? 0L : (long) calculated;
        }
        return durationMs > 0 ? clamp(position, durationMs) : position;
    }

    static int toProgress(long positionMs, long durationMs) {
        if (durationMs <= 0) return 0;
        return (int) Math.round(clamp(positionMs, durationMs)
                * (double) PROGRESS_MAX / durationMs);
    }

    static long fromProgress(int progress, long durationMs) {
        if (durationMs <= 0) return 0L;
        int bounded = Math.max(0, Math.min(PROGRESS_MAX, progress));
        return Math.round(durationMs * ((double) bounded / PROGRESS_MAX));
    }

    static long seekStep(long positionMs, long durationMs, int direction) {
        if (durationMs <= 0) return 0L;
        long bounded = clamp(positionMs, durationMs);
        return direction < 0 ? Math.max(0L, bounded - KEY_SEEK_STEP_MS)
                : bounded >= durationMs - KEY_SEEK_STEP_MS
                ? durationMs : bounded + KEY_SEEK_STEP_MS;
    }

    static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long minutes = seconds / 60;
        if (minutes >= 60) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    minutes / 60, minutes % 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds % 60);
    }
}
