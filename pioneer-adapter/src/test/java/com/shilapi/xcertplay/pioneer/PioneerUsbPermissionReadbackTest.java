package com.shilapi.xcertplay.pioneer;
import org.junit.Test;
import static org.junit.Assert.*;
public class PioneerUsbPermissionReadbackTest {
    @Test public void checksTargetUidAndExactDeviceWithinPermissionSection() {
        String dump = "Device permissions:\n    /dev/bus/usb/001/002: 10018, 10019, \nAccessory permissions:\n";
        assertTrue(PioneerUsbPermissionReadback.granted(dump,"/dev/bus/usb/001/002",10018));
        assertFalse(PioneerUsbPermissionReadback.granted(dump,"/dev/bus/usb/001/002",1001));
        assertFalse(PioneerUsbPermissionReadback.granted(dump,"/dev/bus/usb/001/003",10018));
        assertFalse(PioneerUsbPermissionReadback.granted(dump,"/dev/bus/usb/001/002",100180));
    }
    @Test public void api25SpaceSeparatedUidDumpIsAccepted() {
        assertTrue(PioneerUsbPermissionReadback.granted("Device permissions:\n  /dev/bus/usb/001/002: 10018 10019 \nAccessory permissions:\n", "/dev/bus/usb/001/002",10019));
    }
    @Test public void defaultsAndRootPermissionDoNotProveAppGrant() {
        String dump = "Device permissions:\n    /dev/bus/usb/001/002: 0, \nDevice preferences:\n    /dev/bus/usb/001/002: 10018\n";
        assertFalse(PioneerUsbPermissionReadback.granted(dump,"/dev/bus/usb/001/002",10018));
    }
}
