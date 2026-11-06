package com.shilapi.xcertplay.pioneer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Fixed, user-scoped PackageManager operations inside the shared root service. No subprocess/shell. */
public final class PioneerRootApplications {
    interface Packages {
        ApplicationInfo application(String name, int user) throws Exception;
        int enabledSetting(String name, int user) throws Exception;
        void setEnabled(String name, int state, int flags, int user, String caller) throws Exception;
    }
    private static final class AndroidPackages implements Packages {
        private final Object manager;
        private final Method application, enabled, set;
        AndroidPackages() throws Exception {
            Class<?> type = Class.forName("android.content.pm.IPackageManager");
            manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            application = type.getMethod("getApplicationInfo", String.class, int.class, int.class);
            enabled = type.getMethod("getApplicationEnabledSetting", String.class, int.class);
            set = type.getMethod("setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
        }
        @Override public ApplicationInfo application(String name, int user) throws Exception {
            // Include disabled applications, but not packages uninstalled for this Android user.
            return (ApplicationInfo) invoke(application, manager, name,
                PackageManager.GET_DISABLED_COMPONENTS | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS, user);
        }
        @Override public int enabledSetting(String name, int user) throws Exception {
            return (Integer) invoke(enabled, manager, name, user);
        }
        @Override public void setEnabled(String name, int state, int flags, int user, String caller) throws Exception {
            invoke(set, manager, name, state, flags, user, caller);
        }
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw failure;
        }
    }
    static JSONObject handle(JSONObject payload, JSONObject owner) throws Exception {
        return handle(payload, owner, new AndroidPackages());
    }
    static JSONObject handle(JSONObject payload, JSONObject owner, Packages packages) throws Exception {
        PioneerRootSessionHelper.checkTask();
        int user = owner.getInt("uid") / 100000;
        String action = payload.getString("action");
        if (action.equals("STATUS") && payload.length() == 1) {
            JSONArray states = new JSONArray();
            for (PioneerManagedApplication app : PioneerManagedApplication.values()) {
                PioneerRootSessionHelper.checkTask();
                try { states.put(state(packages, app, user)); }
                catch (Exception unavailable) {
                    // One Binder/package race must not prevent re-enabling another application.
                    PioneerRootSessionHelper.checkTask();
                    states.put(new JSONObject().put("target", app.id).put("state", "UNKNOWN"));
                }
            }
            return new JSONObject().put("apps", states);
        }
        if (!action.equals("SET") || payload.length() != 3 || !(payload.get("disabled") instanceof Boolean))
            throw new IOException("APPLICATION_INVALID_OPERATION");
        PioneerManagedApplication target = PioneerManagedApplication.fromId(payload.getString("target"));
        boolean disabled = payload.getBoolean("disabled");
        JSONObject before = state(packages, target, user);
        if (before.getString("state").equals("NOT_INSTALLED")) return new JSONObject().put("app", before);
        String expected = disabled ? "DISABLED" : "ENABLED";
        if (before.getString("state").equals(expected)) return new JSONObject().put("app", before);
        PioneerRootSessionHelper.checkTask();
        try {
            // flags=0 lets PackageManager stop the application; DONT_KILL_APP would leave it running.
            packages.setEnabled(target.packageName, disabled ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, user, owner.getString("package"));
        } catch (SecurityException | IllegalArgumentException rejected) { throw rejected; }
        catch (Exception uncertain) { throw new PioneerRootIpc.CompletionUnknown("APPLICATION_SET_UNCONFIRMED", uncertain); }
        final JSONObject after;
        try { after = state(packages, target, user); }
        catch (Exception uncertain) { throw new PioneerRootIpc.CompletionUnknown("APPLICATION_READBACK_UNCONFIRMED", uncertain); }
        if (!after.getString("state").equals(expected) && !after.getString("state").equals("NOT_INSTALLED"))
            throw new IOException("APPLICATION_STATE_NOT_APPLIED");
        return new JSONObject().put("app", after);
    }
    private static JSONObject state(Packages packages, PioneerManagedApplication target, int user) throws Exception {
        JSONObject result = new JSONObject().put("target", target.id);
        ApplicationInfo info = packages.application(target.packageName, user);
        if (info == null || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0)
            return result.put("state", "NOT_INSTALLED");
        int setting = packages.enabledSetting(target.packageName, user);
        if (setting < 0 || setting > PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)
            throw new IOException("APPLICATION_UNKNOWN_STATE");
        boolean disabled = setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !info.enabled);
        return result.put("state", disabled ? "DISABLED" : "ENABLED");
    }
}
