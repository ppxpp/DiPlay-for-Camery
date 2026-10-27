package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import com.shilapi.xcertplay.pioneer.PioneerRootDispatcher;

/** Fixed-purpose Java facade reusing DiPlay's existing Kotlin root dispatcher.
 * Kotlin's internal visibility is compile-module-only; no duplicate Binder protocol.
 */
public final class PioneerWirelessRootLauncher {
    private PioneerWirelessRootLauncher() {}
    public static boolean callbackAllowed(String mode, boolean enabled, boolean cleanupAuthorized) {
        return (mode.equals("--audit") || mode.equals("--repair") || mode.equals("--clear-idle")) &&
            (enabled || (cleanupAuthorized && !mode.equals("--repair")));
    }
    public static void launchCallback(Context context, File binary, File result, String mode, boolean cleanupAuthorized) throws Exception {
        if (!callbackAllowed(mode,CamryAdaptation.INSTANCE.enabled(),cleanupAuthorized) || Process.is64Bit())
            throw new IllegalStateException("Camry ARM32 adaptation is required");
        if (!mode.equals("--audit") && !mode.equals("--repair") && !mode.equals("--clear-idle"))
            throw new IllegalArgumentException("Unknown callback task");
        if (!binary.getCanonicalFile().getParentFile().equals(context.getCodeCacheDir().getCanonicalFile()) ||
                !result.getCanonicalPath().startsWith(new File(context.getFilesDir(), "blueforce-callback").getCanonicalPath() + "/"))
            throw new SecurityException("Callback task outside app directories");
        for (File file : new File[]{binary, result}) {
            android.system.StructStat info = Os.lstat(file.getPath());
            if (info.st_uid != Process.myUid() || !OsConstants.S_ISREG(info.st_mode) || info.st_nlink != 1)
                throw new SecurityException("Callback task owner/type mismatch");
        }
        String command = PioneerRootDispatcher.INSTANCE.quote(binary.getCanonicalPath()) + " " + mode + " " +
                Process.myUid() + " " + PioneerRootDispatcher.INSTANCE.quote(result.getCanonicalPath()) +
                " </dev/null >/dev/null 2>&1 &";
        PioneerRootDispatcher.INSTANCE.dispatch(command);
    }
    public static void launchHotspot(Context context, String classpath, File result, String action, String token, File gate, boolean deferred, boolean adaptationAuthorized) throws Exception {
        boolean cleanup=action.equals("STOP_OWNED") || action.equals("RELEASE");
        if (!adaptationAuthorized || Process.is64Bit() || (!cleanup && !CamryAdaptation.INSTANCE.enabled()))
            throw new IllegalStateException("Camry ARM32 adaptation is required");
        if (!java.util.Arrays.asList("PREPARE","PREPARE_LOCAL_ONLY","START","FIVE_GHZ","FIREWALL","STATUS","STOP","STOP_OWNED","RELEASE").contains(action) || !token.matches("[0-9a-f]{32}"))
            throw new SecurityException("Unknown vehicle hotspot action");
        if(action.equals("PREPARE_LOCAL_ONLY") &&
            (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)
            throw new SecurityException("Local-only experiment requires debug build");
        if(deferred && !action.equals("PREPARE") && !action.equals("PREPARE_LOCAL_ONLY"))
            throw new SecurityException("Only hotspot preparation can be deferred");
        String files=context.getFilesDir().getCanonicalPath()+"/";
        if (!result.getCanonicalFile().getParentFile().equals(context.getFilesDir().getCanonicalFile()) ||
            !result.getName().startsWith("pioneer-hotspot-"+token+"-"))
            throw new SecurityException("Invalid result path");
        verifyOwnedFile(result);
        if (classpath.isEmpty()) throw new SecurityException("Empty helper classpath");
        for (String path:classpath.split(":")) {
            File dex=new File(path).getCanonicalFile();
            if (!dex.getPath().startsWith(files+"pioneer-wireless-dex-") ||
                !dex.getName().matches("classes[0-9]*\\.dex"))
                throw new SecurityException("Invalid helper classpath");
            verifyOwnedFile(new File(path));
        }
        if(gate!=null) {
            if((!action.equals("PREPARE") && !action.equals("PREPARE_LOCAL_ONLY")) ||
                (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0 ||
                !gate.getCanonicalFile().getParentFile().equals(context.getCodeCacheDir().getCanonicalFile()) ||
                !gate.getName().equals("pioneer-ap-frequency-gate"))
                throw new SecurityException("Invalid AP gate");
            verifyOwnedFile(gate);
        }
        String command="CLASSPATH="+PioneerRootDispatcher.INSTANCE.quote(classpath)+" /system/bin/app_process -Xcompiler-option --compiler-filter=interpret-only /system/bin "+
            "com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeHelper "+action+" "+
            PioneerRootDispatcher.INSTANCE.quote(context.getPackageName())+" "+PioneerRootDispatcher.INSTANCE.quote(result.getCanonicalPath())+" "+token+(gate==null ? " -" : " "+PioneerRootDispatcher.INSTANCE.quote(gate.getCanonicalPath()))+(deferred ? " deferred" : " immediate")+" camry-enabled"+
            " </dev/null >/dev/null 2>&1 &";
        PioneerRootDispatcher.INSTANCE.dispatch(command);
    }
    private static void verifyOwnedFile(File file) throws Exception {
        android.system.StructStat info=Os.lstat(file.getPath());
        if (info.st_uid!=Process.myUid() || !OsConstants.S_ISREG(info.st_mode) || info.st_nlink!=1)
            throw new SecurityException("Helper file owner/type mismatch");
    }
}
