package dev.q25.bb7pulldown;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Verified public AOD helper contract. Missing or different ROM APIs mean
 * no reference-list mutations; do not guess private fields or signatures.
 */
final class AodLayerReferenceAccess {
    private final Method getter;
    private final Method setter;

    private AodLayerReferenceAccess(Method getter, Method setter) {
        this.getter = getter;
        this.setter = setter;
    }

    static AodLayerReferenceAccess inspect(Class<?> type) {
        if (type == null) return null;
        try {
            // getMethod also resolves PUBLIC methods inherited from a superclass.
            Method get = type.getMethod("getReferencedIds");
            Method set = type.getMethod("setReferencedIds", int[].class);
            if (get.getReturnType() != int[].class || set.getReturnType() != void.class) {
                return null;
            }
            return new AodLayerReferenceAccess(get, set);
        } catch (NoSuchMethodException | SecurityException error) {
            return null;
        }
    }

    int[] read(Object layer) throws ReflectiveOperationException {
        Object value = getter.invoke(layer);
        if (!(value instanceof int[])) {
            throw new ReflectiveOperationException("Unexpected AOD reference return type");
        }
        return ((int[]) value).clone();
    }

    /**
     * Refuse empty lists, check that nothing else changed the references,
     * and verify that the native setter applied precisely the requested IDs.
     * Roll back if the setter has unexpected semantics.
     */
    boolean replaceIfUnchanged(Object layer, int[] before, int[] after)
            throws ReflectiveOperationException {
        if (before == null || after == null || after.length == 0
                || !Arrays.equals(read(layer), before)) return false;
        setter.invoke(layer, (Object) after.clone());
        if (Arrays.equals(read(layer), after)) return true;
        setter.invoke(layer, (Object) before.clone());
        if (!Arrays.equals(read(layer), before)) {
            throw new ReflectiveOperationException("AOD helper rollback failed");
        }
        return false;
    }
}
