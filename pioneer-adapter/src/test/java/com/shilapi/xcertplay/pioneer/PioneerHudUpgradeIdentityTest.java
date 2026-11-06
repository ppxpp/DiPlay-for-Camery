package com.shilapi.xcertplay.pioneer;

import java.io.File;
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
public class PioneerHudUpgradeIdentityTest {
    private final File area = new File("/data/local/tmp/diplay-hud-filter/10098");
    private JSONObject owner() throws Exception {
        return new JSONObject().put("package", "com.gtmc.app.fengyunyx.b819").put("uid", 10098).put("boot", "boot");
    }
    private String[] args() throws Exception {
        JSONObject old = owner().put("appPid", 123).put("appStart", "456")
            .put("helperPid", 789).put("helperStart", "1000")
            .put("control", area + "/control/lease.json").put("status", area + "/feedback/status.json");
        return new String[]{area + "/injector", "-p", "1154", "-s", area + "/filter.js", "-P", old.toString(), "-R", "qjs"};
    }
    @Test public void acceptsOnlyTheFixedOwnedInvocation() throws Exception {
        assertNotNull(PioneerHudFilterHelper.legacyRuntimeOwner(args(), area, owner()));
        for (int index : new int[]{0, 1, 2, 3, 4, 5, 7, 8}) {
            String[] changed = args(); changed[index] = "unrelated";
            assertNull(PioneerHudFilterHelper.legacyRuntimeOwner(changed, area, owner()));
        }
    }
    @Test public void refusesOtherAppsBootsPathsAndUnverifiableLifetimes() throws Exception {
        for (String field : new String[]{"package", "uid", "boot", "control", "status", "appPid", "appStart", "helperPid", "helperStart"}) {
            String[] changed = args(); JSONObject old = new JSONObject(changed[6]);
            old.put(field, field.equals("uid") ? 999 : field.endsWith("Pid") ? 0 : "invalid"); changed[6] = old.toString();
            assertNull(field, PioneerHudFilterHelper.legacyRuntimeOwner(changed, area, owner()));
        }
        assertNull(PioneerHudFilterHelper.legacyRuntimeOwner(new String[0], area, owner()));
        String[] changed = args(); changed[6] = "not json";
        assertNull(PioneerHudFilterHelper.legacyRuntimeOwner(changed, area, owner()));
    }
}
