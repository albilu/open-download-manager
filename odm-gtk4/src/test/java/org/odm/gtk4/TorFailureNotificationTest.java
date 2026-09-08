package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TorFailureNotificationTest {
    @Test
    void statusShowsCountryFlagAndIpAndToleratesUnknownCountry() {
        assertEquals("Tor verified — 🇫🇷 192.0.2.1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "192.0.2.1", "fr", 0, false)));
        assertEquals("Tor verified — 2001:db8::1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "2001:db8::1", null, 0, false)));
        assertEquals("Tor verified — 192.0.2.1", MainWindow.torCheckStatus(
                new org.tor.TorCircuitMonitor.Result(true, "verified", "192.0.2.1", "??", 0, false)));
        assertTrue(MainWindow.torCheckStatus(new org.tor.TorCircuitMonitor.Result(
                false, "Unable to verify the Tor circuit", null, null, 0, true)).contains("Offline Mode enabled"));
        assertEquals("Tor check failed — Unable to verify the Tor circuit",
                MainWindow.torCheckStatus(new org.tor.TorCircuitMonitor.Result(
                        false, "Unable to verify the Tor circuit", null, null, 0, false)));
    }
}
