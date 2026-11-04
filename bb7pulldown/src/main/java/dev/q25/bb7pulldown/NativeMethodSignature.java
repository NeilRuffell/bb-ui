package dev.q25.bb7pulldown;

/**
 * Matches actual reflected method parameter types, including primitive
 * boolean arguments. The Q25's optimized SystemUI reverses the argument
 * order of several keyguard methods compared to upstream Java/Kotlin.
 */
final class NativeMethodSignature {
    private NativeMethodSignature() {}

    static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                if (types[i].isPrimitive()) return false;
            } else if (types[i] == boolean.class) {
                if (!(args[i] instanceof Boolean)) return false;
            } else if (types[i] == int.class) {
                if (!(args[i] instanceof Integer)) return false;
            } else if (!types[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }
}
