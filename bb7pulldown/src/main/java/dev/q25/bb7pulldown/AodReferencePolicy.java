package dev.q25.bb7pulldown;

/**
 * Validates native AOD Layer references before any keyguard View is detached.
 *
 * An unresolved or descendant reference makes the removal unsafe: a
 * ConstraintHelper can subsequently dereference a null View during layout.
 * The AodBurnInSection's required empty view is never touched.
 */
final class AodReferencePolicy {
    static final int BLOCK = 0;
    static final int DETACH = 1;
    static final int UNREGISTER_THEN_DETACH = 2;

    private AodReferencePolicy() {}

    static int plan(int targetId, int[] referencedIds,
            boolean unresolvedReference, boolean referencedDescendant) {
        if (targetId == -1 || referencedIds == null
                || unresolvedReference || referencedDescendant) return BLOCK;
        for (int id : referencedIds) {
            if (id == targetId) return UNREGISTER_THEN_DETACH;
        }
        return DETACH;
    }
}
