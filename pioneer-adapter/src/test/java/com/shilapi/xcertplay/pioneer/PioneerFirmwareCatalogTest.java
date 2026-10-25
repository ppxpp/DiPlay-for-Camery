package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertThrows;

public class PioneerFirmwareCatalogTest {
    private static final String FP = "Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/1:user/release-keys";
    @Test public void preservesVerifiedFirmwareCombinationsWithoutModuleDependency() throws Exception {
        PioneerFirmwareCatalog.requireSupported("1.000062_1", FP, "4.9.17-g2b63fe51", 25, "armv7l");
        for (String version : new String[]{"1.000132_1", "1.000152_1"})
            PioneerFirmwareCatalog.requireSupported(version, FP, "4.9.17-gbd3a8122", 25, "armv7l");
        PioneerFirmwareCatalog.requireSupported("1.000132_5", FP.replace("/1:user", "/5:user"), "4.9.17-gbd3a8122", 25, "armv7l");
    }
    @Test public void privilegedToolsStillRejectUnknownOrMismatchedFirmware() {
        assertThrows(IOException.class, () -> PioneerFirmwareCatalog.requireSupported("1.000145_1", FP, "4.9.17-gbd3a8122", 25, "armv7l"));
        assertThrows(IOException.class, () -> PioneerFirmwareCatalog.requireSupported("1.000132_1", FP, "4.9.17-g2b63fe51", 25, "armv7l"));
        assertThrows(IOException.class, () -> PioneerFirmwareCatalog.requireSupported("1.000132_1", FP, "4.9.17-gbd3a8122", 26, "armv7l"));
        assertThrows(IOException.class, () -> PioneerFirmwareCatalog.requireSupported("1.000132_1", FP + "invalid", "4.9.17-gbd3a8122", 25, "armv7l"));
    }
}
