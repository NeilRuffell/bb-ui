package dev.q25.bb7pulldown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ConnectionControlPolicyTest {
    @Test public void fixedBbos6Order() {
        assertEquals(3, ConnectionControlPolicy.COUNT);
        assertEquals("Mobile Network", ConnectionControlPolicy.label(0));
        assertEquals("Wi-Fi", ConnectionControlPolicy.label(1));
        assertEquals("Bluetooth", ConnectionControlPolicy.label(2));
    }

    @Test public void androidInternetTileOwnsBothMobileAndWifiControls() {
        assertEquals("internet", ConnectionControlPolicy.tileSpec(
                ConnectionControlPolicy.MOBILE));
        assertEquals("internet", ConnectionControlPolicy.tileSpec(
                ConnectionControlPolicy.WIFI));
        assertEquals("bt", ConnectionControlPolicy.tileSpec(
                ConnectionControlPolicy.BLUETOOTH));
    }

    @Test public void mobileDataIsNotReportedAsRadioPower() {
        assertEquals("Data on", ConnectionControlPolicy.stateLabel(
                ConnectionControlPolicy.MOBILE, true));
        assertEquals("Data off", ConnectionControlPolicy.stateLabel(
                ConnectionControlPolicy.MOBILE, false));
        assertEquals("Unknown", ConnectionControlPolicy.stateLabel(
                ConnectionControlPolicy.MOBILE, null));
    }

    @Test public void unknownStateNeverShowsChecked() {
        assertFalse(ConnectionControlPolicy.checked(null));
        assertFalse(ConnectionControlPolicy.checked(false));
        assertTrue(ConnectionControlPolicy.checked(true));
    }
}
