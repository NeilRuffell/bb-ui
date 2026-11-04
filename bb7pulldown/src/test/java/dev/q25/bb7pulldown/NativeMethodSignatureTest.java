package dev.q25.bb7pulldown;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import org.junit.Test;

public class NativeMethodSignatureTest {
    // Actual signatures extracted from Q25-SystemUI.apk:
    // classes2.dex: StatusBarKeyguardViewManager.showPrimaryBouncer(String,Z)
    // classes.dex: PrimaryBouncerInteractor.show(String,Z)
    static final class Q25Manager {
        public void showPrimaryBouncer(String reason, boolean scrimmed) {}
    }

    static final class Q25Primary {
        public boolean show(String reason, boolean scrimmed) { return true; }
    }

    static final class UpstreamManager {
        public void showPrimaryBouncer(boolean scrimmed, String reason) {}
    }

    @Test public void q25ManagerRequiresReasonBeforeBoolean() throws Exception {
        Method method = Q25Manager.class.getMethod(
                "showPrimaryBouncer", String.class, boolean.class);
        assertTrue(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {"BB UI physical Space", true}));
        assertFalse(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {true, "BB UI physical Space"}));
    }

    @Test public void q25PrimaryRequiresReasonBeforeBoolean() throws Exception {
        Method method = Q25Primary.class.getMethod(
                "show", String.class, boolean.class);
        assertTrue(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {"BB UI physical Space", true}));
        assertFalse(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {true, "BB UI physical Space"}));
    }

    @Test public void sourceOrderFallbackRemainsCompatible() throws Exception {
        Method method = UpstreamManager.class.getMethod(
                "showPrimaryBouncer", boolean.class, String.class);
        assertTrue(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {true, "BB UI physical Space"}));
        assertFalse(NativeMethodSignature.matches(method.getParameterTypes(),
                new Object[] {"BB UI physical Space", true}));
    }

    @Test public void primitiveParametersCannotReceiveNull() {
        assertFalse(NativeMethodSignature.matches(
                new Class<?>[] {String.class, boolean.class},
                new Object[] {"Reason", null}));
    }
}
