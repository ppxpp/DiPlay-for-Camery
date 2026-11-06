package com.shilapi.xcertplay.pioneer;

import android.content.pm.ApplicationInfo;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PioneerRootApplicationsTest {
    private static class Packages implements PioneerRootApplications.Packages {
        int setting, writes, user, flags; boolean installed = true, uncertain;
        String name, caller;
        public ApplicationInfo application(String name, int user) {
            ApplicationInfo info = new ApplicationInfo();
            info.flags = installed ? ApplicationInfo.FLAG_INSTALLED : 0; info.enabled = true;
            return info;
        }
        public int enabledSetting(String name, int user) { return setting; }
        public void setEnabled(String name, int state, int flags, int user, String caller) throws Exception {
            writes++; this.name = name; setting = state; this.flags = flags; this.user = user; this.caller = caller;
            if (uncertain) throw new IOException("lost reply");
        }
    }
    private JSONObject owner() throws Exception {
        return new JSONObject().put("uid", 1010001).put("package", "com.gtmc.app.fengyunyx.b819");
    }
    private JSONObject set(boolean disabled) throws Exception {
        return new JSONObject().put("action", "SET").put("target", "amap").put("disabled", disabled);
    }
    @Test public void scopesMutationAndReadsActualState() throws Exception {
        Packages packages = new Packages();
        assertEquals("DISABLED", PioneerRootApplications.handle(set(true), owner(), packages).getJSONObject("app").getString("state"));
        assertEquals(10, packages.user); assertEquals(0, packages.flags); assertEquals(3, packages.setting);
        assertEquals("com.autonavi.amapauto", packages.name);
        assertEquals(owner().getString("package"), packages.caller);
        PioneerRootApplications.handle(set(true), owner(), packages);
        assertEquals(1, packages.writes);
        assertEquals("ENABLED", PioneerRootApplications.handle(set(false), owner(), packages).getJSONObject("app").getString("state"));
        assertEquals(1, packages.setting);
    }
    @Test public void absentApplicationNeverMutates() throws Exception {
        Packages packages = new Packages(); packages.installed = false;
        assertEquals("NOT_INSTALLED", PioneerRootApplications.handle(set(true), owner(), packages).getJSONObject("app").getString("state"));
        assertEquals(0, packages.writes);
    }
    @Test public void rejectsInjectedTargetUserAndStringBoolean() throws Exception {
        for (JSONObject request : new JSONObject[] {set(true).put("target", "android"),
            set(true).put("user", 0), set(true).put("disabled", "true")}) {
            Packages packages = new Packages();
            assertThrows(IOException.class, () -> PioneerRootApplications.handle(request, owner(), packages));
            assertEquals(0, packages.writes);
        }
    }
    @Test public void uncertainMutationIsNotReplayed() throws Exception {
        Packages packages = new Packages(); packages.uncertain = true;
        assertThrows(PioneerRootIpc.CompletionUnknown.class,
            () -> PioneerRootApplications.handle(set(true), owner(), packages));
        assertEquals(1, packages.writes); assertEquals(3, packages.setting);
        assertEquals("DISABLED", PioneerRootApplications.handle(new JSONObject().put("action", "STATUS"), owner(), packages)
            .getJSONArray("apps").getJSONObject(0).getString("state"));
        assertEquals(1, packages.writes);
    }
}
