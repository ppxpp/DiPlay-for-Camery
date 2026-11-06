package com.shilapi.xcertplay.pioneer;

import org.json.JSONObject;

/** Fixed ADB operation inside the unified root service; never exits its owner process. */
public final class PioneerAdbHelper {
    static JSONObject runTask(boolean enabled) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Root service required");
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        Class<?> type = Class.forName("jp.pioneer.ceam.widget.DebugModeServiceProxy.DebugModeServiceProxy");
        Object instance = type.getMethod("getInstance").invoke(null);
        boolean accepted;
        PioneerRootSessionHelper.checkTask();
        try { accepted = Boolean.TRUE.equals(type.getMethod("setADBonUSB", int.class).invoke(instance, enabled ? 1 : 0)); }
        catch (java.lang.reflect.InvocationTargetException failure) {
            throw new PioneerRootIpc.CompletionUnknown("OEM_ADB_MUTATION_UNCONFIRMED", failure.getTargetException());
        }
        if (!accepted) throw new PioneerRootIpc.CompletionUnknown("OEM_ADB_REJECTED_STATE_UNCONFIRMED");
        return new JSONObject().put("accepted", true).put("enabled", enabled);
    }
}
