package com.shilapi.xcertplay.pioneer;

import android.content.pm.ApplicationInfo;
import android.os.DeadObjectException;
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
public class PioneerApplicationsReleaseTest {
    private static class Packages implements PioneerRootApplications.Packages {
        int setting, writes; boolean manifestEnabled = true, installed = true, nullInfo;
        String broken; boolean dieOnWrite, dieOnReadback, reject, ignoreWrite;
        public ApplicationInfo application(String name, int user) throws Exception {
            assertEquals(10, user);
            if (name.equals(broken) || (dieOnReadback && writes > 0)) throw new DeadObjectException();
            if (nullInfo) return null;
            ApplicationInfo info = new ApplicationInfo();
            info.flags = installed ? ApplicationInfo.FLAG_INSTALLED : 0;
            info.enabled = manifestEnabled;
            return info;
        }
        public int enabledSetting(String name, int user) { return setting; }
        public void setEnabled(String name, int value, int flags, int user, String caller) throws Exception {
            writes++;
            assertEquals(0, flags); assertEquals(10, user); assertEquals("owner", caller);
            if (reject) throw new SecurityException("denied");
            if (!ignoreWrite) setting = value;
            if (dieOnWrite) throw new DeadObjectException();
        }
    }
    private JSONObject owner() throws Exception { return new JSONObject().put("uid", 1010001).put("package", "owner"); }
    private JSONObject set(String target, boolean disabled) throws Exception {
        return new JSONObject().put("action", "SET").put("target", target).put("disabled", disabled);
    }
    private String status(Packages p, boolean disabled) throws Exception {
        return PioneerRootApplications.handle(set("amap", disabled), owner(), p).getJSONObject("app").getString("state");
    }
    @Test public void binderFailureIsIsolatedAndAnotherAppCanBeReenabled() throws Exception {
        Packages p = new Packages(); p.broken = PioneerManagedApplication.AMAP.packageName; p.setting = 3;
        org.json.JSONArray apps = PioneerRootApplications.handle(new JSONObject().put("action", "STATUS"), owner(), p).getJSONArray("apps");
        assertEquals("UNKNOWN", apps.getJSONObject(0).getString("state"));
        assertEquals("DISABLED", apps.getJSONObject(1).getString("state"));
        assertEquals("ENABLED", PioneerRootApplications.handle(set("tencent_map", false), owner(), p).getJSONObject("app").getString("state"));
        assertEquals(1, p.writes);
    }
    @Test public void allDisabledModesAndManifestDefaultCanBeReenabled() throws Exception {
        for (int setting : new int[]{0, 2, 3, 4}) {
            Packages p = new Packages(); p.setting = setting; p.manifestEnabled = false;
            assertEquals("ENABLED", status(p, false)); assertEquals(1, p.setting); assertEquals(1, p.writes);
            assertEquals("DISABLED", status(p, true)); assertEquals(3, p.setting); assertEquals(2, p.writes);
        }
    }
    @Test public void reinstallAndMissingPackagesHaveNoRememberedDesiredState() throws Exception {
        Packages p = new Packages(); status(p, true);
        p.nullInfo = true; assertEquals("NOT_INSTALLED", status(p, true)); assertEquals(1, p.writes);
        p.nullInfo = false; p.installed = false; assertEquals("NOT_INSTALLED", status(p, false));
        p.installed = true; p.setting = 0;
        assertEquals("ENABLED", PioneerRootApplications.handle(new JSONObject().put("action", "STATUS"), owner(), p)
            .getJSONArray("apps").getJSONObject(0).getString("state")); assertEquals(1, p.writes);
    }
    @Test public void binderWriteAndReadbackFailuresNeverReplay() throws Exception {
        for (boolean writeFailure : new boolean[]{true, false}) {
            Packages p = new Packages(); p.dieOnWrite = writeFailure; p.dieOnReadback = !writeFailure;
            assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> status(p, true)); assertEquals(1, p.writes);
            p.dieOnWrite = false; p.dieOnReadback = false;
            assertEquals("DISABLED", status(p, true)); assertEquals(1, p.writes);
        }
    }
    @Test public void rejectsBadStateAndSecurityErrorsWithoutRetry() throws Exception {
        Packages p = new Packages(); p.setting = 99;
        assertThrows(java.io.IOException.class, () -> status(p, true)); assertEquals(0, p.writes);
        p.setting = 0; p.reject = true;
        assertThrows(SecurityException.class, () -> status(p, true)); assertEquals(1, p.writes);
        p.reject = false; p.ignoreWrite = true;
        assertThrows(java.io.IOException.class, () -> status(p, true)); assertEquals(2, p.writes);
    }
    @Test public void cancellationAfterPreflightPreventsMutationAndIsNotHiddenAsUnknown() throws Exception {
        Class<?> type = Class.forName("com.shilapi.xcertplay.pioneer.PioneerRootSessionHelper$Task");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(String.class, long.class, int.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance("test", Long.MAX_VALUE, 1010001);
        java.lang.reflect.Field flag = type.getDeclaredField("cancelled"); flag.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean cancelled = (java.util.concurrent.atomic.AtomicBoolean) flag.get(task);
        java.lang.reflect.Field field = PioneerRootSessionHelper.class.getDeclaredField("currentTask"); field.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Object> current = (ThreadLocal<Object>) field.get(null);
        boolean[] failAfterCancel = {false};
        Packages packages = new Packages() {
            @Override public ApplicationInfo application(String name, int user) throws Exception {
                ApplicationInfo info = super.application(name, user);
                cancelled.set(true);
                if (failAfterCancel[0]) throw new DeadObjectException();
                return info;
            }
        };
        current.set(task);
        try {
            assertThrows(java.io.IOException.class, () -> status(packages, true));
            assertEquals(0, packages.writes);
            cancelled.set(false);
            failAfterCancel[0] = true;
            assertThrows(java.io.IOException.class, () -> PioneerRootApplications.handle(
                new JSONObject().put("action", "STATUS"), owner(), packages));
            assertEquals(0, packages.writes);
        } finally { current.remove(); }
    }
    @Test public void allowlistIsExactAndRequestsCannotOverridePackageOrUser() throws Exception {
        assertEquals(3, PioneerManagedApplication.values().length);
        assertEquals("com.autonavi.amapauto", PioneerManagedApplication.fromId("amap").packageName);
        assertEquals("com.tencent.wecarnavi", PioneerManagedApplication.fromId("tencent_map").packageName);
        assertEquals("com.tencent.wecarspeech", PioneerManagedApplication.fromId("tencent_speech").packageName);
        for (JSONObject payload : new JSONObject[]{set("AMAP", true), set("amap;reboot", true),
                set("android", true), set("amap", true).put("package", "android"),
                set("amap", true).put("user", 0), set("amap", true).put("disabled", 1),
                new JSONObject().put("action", "STATUS").put("target", "amap")}) {
            Packages p = new Packages();
            assertThrows(Exception.class, () -> PioneerRootApplications.handle(payload, owner(), p));
            assertEquals(0, p.writes);
        }
    }
}
