package com.shilapi.xcertplay.pioneer;
import org.junit.Test;
import java.io.IOException;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class NativeNcmModuleTest {
    private static final String FP = "Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/1:user/release-keys";
    @Test public void selectsOnlyExactFirmwareAndKernel() throws Exception {
        assertEquals("0062", NativeNcmModule.select("1.000062_1", FP, "4.9.17-g2b63fe51", 25, "armv7l").key);
        assertEquals("0132", NativeNcmModule.select("1.000132_1", FP, "4.9.17-gbd3a8122", 25, "armv7l").key);
        assertEquals("0132", NativeNcmModule.select("1.000132_5", FP.replace("/1:user", "/5:user"), "4.9.17-gbd3a8122", 25, "armv7l").key);
        assertEquals("0132", NativeNcmModule.select("1.000152_1", FP, "4.9.17-gbd3a8122", 25, "armv7l").key);
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000132_1", FP, "4.9.17-g2b63fe51", 25, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000132_1", FP, "4.9.17-gbd3a8122", 26, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000145_1", FP, "4.9.17-gbd3a8122", 25, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000152_5", FP, "4.9.17-gbd3a8122", 25, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000152_1", FP, "4.9.17-g2b63fe51", 25, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000132_1", FP + "changed", "4.9.17-gbd3a8122", 25, "armv7l"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("1.000132_1", FP, "4.9.17-gbd3a8122", 25, "aarch64"));
        assertThrows(IOException.class, () -> NativeNcmModule.select("unknown", FP, "4.9.17-gbd3a8122", 25, "armv7l"));
    }
    @Test public void uncertainPrewarmMustNotReleaseUserspaceBackendFence() {
        assertEquals("UNCERTAIN", PioneerNativeNcmPrewarm.failureState(new PioneerInterfaceFirewall.CompletionUnknown(false, "running")));
        assertEquals("FAILED", PioneerNativeNcmPrewarm.failureState(new PioneerInterfaceFirewall.CompletionUnknown(true, "exited")));
        assertEquals("UNCERTAIN", PioneerNativeNcmPrewarm.failureState(new IOException("NCM_MODULE_LOAD_UNCONFIRMED; loader child is still running")));
        assertEquals("FAILED", PioneerNativeNcmPrewarm.failureState(new IOException("UNSUPPORTED_NCM_FIRMWARE")));
    }
    @Test public void sessionAddressesAreLocalAndVaryAcrossLeases() throws Exception {
        String first = PioneerNativeNcmHelper.sessionAddress("00:11:22:33:44:55", "001122");
        assertTrue(InetAddress.getByName(first).isLinkLocalAddress());
        assertEquals(first, PioneerNativeNcmHelper.sessionAddress("00:11:22:33:44:55", "001122"));
        assertNotEquals(first, PioneerNativeNcmHelper.sessionAddress("00:11:22:33:44:55", "001123"));
    }
    @Test public void ignoresTentativeAndFailedExistingAddresses() throws Exception {
        byte[] address = InetAddress.getByName("fe80::1234").getAddress();
        assertTrue(PioneerNativeNcmHelper.addressReady("inet6 fe80::1234/64 scope link", address));
        for (String flag : new String[]{"tentative", "dadfailed", "deprecated"})
            assertFalse(PioneerNativeNcmHelper.addressReady("inet6 fe80::1234/64 scope link " + flag, address));
        assertFalse(PioneerNativeNcmHelper.addressReady("inet6 fe80::1235/64 scope link", address));
    }
}
