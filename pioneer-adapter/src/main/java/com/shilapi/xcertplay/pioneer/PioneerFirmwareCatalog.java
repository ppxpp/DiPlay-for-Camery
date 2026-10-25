package com.shilapi.xcertplay.pioneer;

import android.os.Build;
import android.system.Os;
import java.io.IOException;

/** Shared privileged-operation allowlist. Does not load or depend on kernel modules. */
public final class PioneerFirmwareCatalog {
    private PioneerFirmwareCatalog() { }
    public static void requireSupportedFirmware() throws Exception {
        String version = String.valueOf(Class.forName("android.os.SystemProperties")
            .getMethod("get", String.class).invoke(null, "ro.product.version"));
        requireSupported(version, Build.FINGERPRINT, Os.uname().release, Build.VERSION.SDK_INT, Os.uname().machine);
    }
    static void requireSupported(String version, String fingerprint, String kernel, int sdk, String machine) throws IOException {
        String expected;
        if (version.equals("1.000062_1")) expected = "4.9.17-g2b63fe51";
        else if (version.equals("1.000132_1") || version.equals("1.000132_5") || version.equals("1.000152_1")) expected = "4.9.17-gbd3a8122";
        else throw new IOException("UNSUPPORTED_NCM_FIRMWARE version=" + safe(version) +
            " fingerprint=" + safe(fingerprint) + " kernel=" + safe(kernel) + " sdk=" + sdk + " machine=" + safe(machine));
        String revision = version.equals("1.000132_5") ? "5" : "1";
        if (sdk != 25 || !machine.equals("armv7l") || !kernel.equals(expected) ||
            !fingerprint.equals("Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/" + revision + ":user/release-keys"))
            throw new IOException("UNSUPPORTED_NCM_KERNEL version=" + safe(version) +
                " fingerprint=" + safe(fingerprint) + " kernel=" + safe(kernel) + " sdk=" + sdk + " machine=" + safe(machine));
    }
    private static String safe(String value) {
        String clean = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }
}
