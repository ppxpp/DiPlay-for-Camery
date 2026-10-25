package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

public class PioneerNativeNcmHelperTest {
    @Test public void parsesExactInterfaceOwnershipWithoutSubstringMatches() throws Exception {
        String dump = "NetworkController\n  51 DUMMY\n  99 LOCAL usb0,wlan0\n  100 PHYSICAL usb01\n";
        assertEquals(Collections.singleton("LOCAL"), PioneerNativeNcmHelper.networkOwners(dump, "usb0"));
        assertEquals(Collections.singleton("PHYSICAL"), PioneerNativeNcmHelper.networkOwners(dump, "usb01"));
        assertTrue(PioneerNativeNcmHelper.networkOwners(dump, "usb").isEmpty());
    }
    @Test public void processIdentityUsesStartTimeNotOnlyRecycledPid() throws Exception {
        String[] fields = new String[20]; java.util.Arrays.fill(fields, "0"); fields[0] = "S"; fields[19] = "987654";
        String stat = "123 (netd (worker)) " + String.join(" ", fields);
        assertEquals(987654L, PioneerNativeNcmHelper.processStartTime(stat, 123));
        assertThrows(IOException.class, () -> PioneerNativeNcmHelper.processStartTime(stat, 124));
    }
    @Test(expected = IOException.class) public void refusesUnreadableNetdState() throws Exception {
        PioneerNativeNcmHelper.networkOwners("permission denied", "usb0");
    }
    @Test(expected = IOException.class) public void refusesAmbiguousLocalNetwork() throws Exception {
        PioneerNativeNcmHelper.networkOwners("NetworkController\n99 LOCAL\n100 LOCAL usb0", "usb0");
    }
}
