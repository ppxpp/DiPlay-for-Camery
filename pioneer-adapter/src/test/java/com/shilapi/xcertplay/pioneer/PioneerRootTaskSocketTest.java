package com.shilapi.xcertplay.pioneer;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PioneerRootTaskSocketTest {
    private JSONObject owner() throws Exception {
        return new JSONObject().put("package", "com.test.diplay").put("apk", "/data/app/base.apk")
            .put("boot", "boot").put("uid", 10123).put("appPid", 42).put("appStart", "123");
    }
    private JSONObject payload(String kind) throws Exception {
        return new JSONObject().put("kind", kind).put("request", owner()
            .put("token", kind.equals("vpn-firewall") ? "0123456789abcdef0123456789abcdef" :
                "01234567-89ab-cdef-0123-456789abcdef")
            .put("action", "INSPECT").put("requestedAtMs", 1000).put("deadline", 15000));
    }
    @Test public void acceptsOnlyFixedSocketTasksAndCopiesTheirInputs() throws Exception {
        for (String kind : new String[]{"vpn-permission", "usb-permission-probe", "vpn-firewall"}) {
            JSONObject wire = payload(kind);
            JSONObject validated = PioneerRootSessionHelper.validateTaskRequest(wire, owner(), 2000, 16000);
            assertEquals("INSPECT", validated.getString("action"));
            wire.getJSONObject("request").put("action", "changed");
            assertEquals("INSPECT", validated.getString("action"));
        }
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(
            new JSONObject().put("directory", "/data/data/com.test.diplay/files/vpn-permission/token"), owner(), 2000, 16000));
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(payload("arbitrary"), owner(), 2000, 16000));
    }
    @Test public void rejectsChangedOwnersExpiredRequestsAndPathTokensBeforeFilesystemAccess() throws Exception {
        for (String key : new String[]{"package", "apk", "boot", "appStart", "uid", "appPid"}) {
            JSONObject wire = payload("vpn-permission");
            wire.getJSONObject("request").put(key, key.equals("uid") || key.equals("appPid") ? 999 : "other");
            assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(wire, owner(), 2000, 16000));
        }
        JSONObject wire = payload("vpn-permission");
        wire.getJSONObject("request").put("token", "../other");
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(wire, owner(), 2000, 16000));
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(payload("vpn-permission"), owner(), 15000, 16000));
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(payload("vpn-permission"), owner(), 2000, 14000));
        assertThrows(Exception.class, () -> PioneerRootSessionHelper.validateTaskRequest(payload("vpn-permission"), owner(), 999, 16000));
    }
}
