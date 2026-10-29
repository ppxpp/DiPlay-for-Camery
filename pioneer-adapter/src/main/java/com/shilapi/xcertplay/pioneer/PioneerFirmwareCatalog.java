package com.shilapi.xcertplay.pioneer;

import java.io.IOException;

/** General helpers use the common vehicle adaptation policy. */
public final class PioneerFirmwareCatalog {
    private PioneerFirmwareCatalog() { }
    public static void requireSupportedFirmware() throws Exception {
        if (!CamryAdaptation.INSTANCE.enabled()) throw new IOException("CAMRY_ADAPTATION_DISABLED");
    }
}
