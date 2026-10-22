package com.shilapi.xcertplay.pioneer;

import java.util.regex.*;

/** API25 USB service dump readback, explicitly matching device node and target app UID. */
final class PioneerUsbPermissionReadback {
    static boolean granted(String dump, String node, int uid) {
        if (!node.matches("/dev/bus/usb/[0-9]+/[0-9]+") || uid < 10000) return false;
        boolean permissions = false;
        for (String line : dump.split("\\n")) {
            String text = line.trim();
            if (text.equals("Device permissions:")) { permissions = true; continue; }
            if (text.equals("Accessory permissions:") || text.startsWith("Device preferences:") || text.startsWith("Accessory preferences:")) permissions = false;
            if (!permissions || !text.startsWith(node + ":")) continue;
            String values = text.substring(node.length()+1).trim();
            if (!values.matches("[0-9,\\s]*")) continue;
            for (String value : values.split("[,\\s]+")) if (String.valueOf(uid).equals(value)) return true;
        }
        return false;
    }
}
