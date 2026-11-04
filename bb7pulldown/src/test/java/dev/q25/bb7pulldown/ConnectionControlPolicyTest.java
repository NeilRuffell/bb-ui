package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ConnectionControlPolicyTest {
    @Test public void fixedBbos6LockedPresentationOrder() {
        assertEquals(3, ConnectionControlPolicy.COUNT);
        assertEquals("Mobile Network", ConnectionControlPolicy.label(0));
        assertEquals("Wi-Fi", ConnectionControlPolicy.label(1));
        assertEquals("Bluetooth", ConnectionControlPolicy.label(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidLockedKindDoesNotMapToAnotherRadio() {
        ConnectionControlPolicy.label(3);
    }
}
