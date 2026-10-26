package com.shilapi.xcertplay.pioneer;

import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;

/**
 * One-shot root module prewarm through the Pioneer service, as in b819. It loads/verifies the
 * selected module and caches tool identities, without configuring an interface, route or firewall.
 */
public final class PioneerNativeNcmPrewarm {
    public static void main(String[] args) {
        if (Process.myUid() != 0 || (args.length != 6 && args.length != 7 && args.length != 8)) return;
        File result = null;
        String state = "FAILED";
        String error = "";
        String firmware = "";
        String fingerprint = "";
        String kernel = "";
        String machine = "";
        int sdk = 0;
        JSONObject usbPermission = new JSONObject();
        JSONObject warmWorker = new JSONObject();
        PioneerNativeNcmWarmWorker worker = null;
        try {
            File candidate = new File(args[0]).getCanonicalFile();
            File directory = candidate.getParentFile();
            if (directory == null || !candidate.getPath().matches(
                "/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/native-ncm/prewarm-[0-9a-f-]{36}\\.json"))
                throw new IllegalArgumentException("Invalid prewarm path");
            StructStat directoryStat = Os.lstat(directory.getPath());
            if (!OsConstants.S_ISDIR(directoryStat.st_mode) || directoryStat.st_uid < 10000)
                throw new IllegalArgumentException("Invalid prewarm directory");
            result = candidate;
            String apk = args[1], boot = args[2], appStart = args[5];
            int appPid = Integer.parseInt(args[3]);
            long deadline = Long.parseLong(args[4]);
            File cancel = new File(candidate.getPath() + ".cancel");
            PioneerRootGuard owner = () -> {
                if (cancel.exists()) throw new IllegalStateException("NCM_PREWARM_CANCELLED_OR_EXPIRED");
                String currentBoot = read(new File("/proc/sys/kernel/random/boot_id")).trim();
                if (!boot.equals(currentBoot) || !boot.matches("[0-9a-f-]{36}") ||
                    Os.stat("/proc/" + appPid).st_uid != directoryStat.st_uid ||
                    !processStart(appPid).equals(appStart) || !apk.equals(System.getenv("CLASSPATH")))
                    throw new IllegalStateException("NCM_PREWARM_OWNER_CHANGED");
            };
            PioneerRootGuard guard = () -> {
                if (android.os.SystemClock.elapsedRealtime() >= deadline)
                    throw new IllegalStateException("NCM_PREWARM_CANCELLED_OR_EXPIRED");
                owner.check();
            };
            guard.check();
            CamryAdaptation.INSTANCE.useRequestMode(new JSONObject().put("camryAdaptation", args.length == 8 ? args[7] : "AUTO"));
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IllegalStateException("UNSUPPORTED_PIONEER_FIRMWARE");
            firmware = String.valueOf(Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class).invoke(null, "ro.product.version"));
            fingerprint = Build.FINGERPRINT;
            kernel = Os.uname().release;
            machine = Os.uname().machine;
            sdk = Build.VERSION.SDK_INT;
            // Persist before the first module mutation; survives the ordinary app process dying.
            write(result, "LOADING", "", firmware, fingerprint, kernel, machine, sdk, boot, usbPermission, warmWorker);
            NativeNcmModule.ensureLoaded(apk, boot, guard);
            guard.check();
            PioneerNativeNcmHelper.verifyFirewallTools();
            if (args.length >= 7 && !args[6].equals("USB_GRANT") && !args[6].equals("NO_USB_GRANT"))
                throw new IllegalArgumentException("Invalid prewarm action");
            if (args.length >= 7 && args[6].equals("USB_GRANT")) {
                try { usbPermission = PioneerUsbPermissionHelper.grantDuringPrewarm(apk, directoryStat.st_uid, guard); }
                catch (Exception unavailable) { usbPermission.put("code", "FAILED").put("error", unavailable.getClass().getSimpleName()); }
            }
            guard.check();
            // Failure to open the optional task socket must not invalidate successful module loading.
            try {
                worker = new PioneerNativeNcmWarmWorker(directory, directoryStat.st_uid, appPid,
                    appStart, apk, boot, owner);
                warmWorker = worker.descriptor();
            } catch (Exception unavailable) { if (worker != null) worker.close(); worker = null; }
            state = "READY";
        } catch (Throwable failure) {
            state = failureState(failure);
            error = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        }
        if (result != null) try { write(result, state, error, firmware, fingerprint, kernel, machine, sdk, args[2], usbPermission, warmWorker); }
        catch (Throwable ignored) { if (worker != null) { worker.close(); worker = null; } }
        if (worker != null) {
            try { if (state.equals("READY")) worker.serve(); }
            catch (Throwable ignored) { }
            finally { worker.close(); }
        }
        System.exit(0);
    }

    static String failureState(Throwable failure) {
        if (failure instanceof PioneerInterfaceFirewall.CompletionUnknown &&
            !((PioneerInterfaceFirewall.CompletionUnknown) failure).childStopped) return "UNCERTAIN";
        String message = String.valueOf(failure.getMessage());
        if (message.startsWith("NCM_MODULE_LOAD_UNCONFIRMED") || message.equals("NCM_MODULE_LOADER_BUSY"))
            return "UNCERTAIN";
        return "FAILED";
    }

    private static String read(File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int size;
            while ((size = in.read(buffer)) >= 0) {
                if (out.size() + size > 8192) throw new IllegalStateException("Prewarm identity too large");
                out.write(buffer, 0, size);
            }
            return out.toString("UTF-8");
        }
    }

    private static String processStart(int pid) throws Exception {
        String stat = read(new File("/proc/" + pid + "/stat"));
        return stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19];
    }

    private static void write(File result, String state, String error, String firmware, String fingerprint,
                              String kernel, String machine, int sdk, String boot, JSONObject usbPermission,
                              JSONObject warmWorker) throws Exception {
        File directory = result.getParentFile();
        StructStat directoryStat = Os.lstat(directory.getPath());
        File temp = new File(directory, result.getName() + ".tmp");
        JSONObject value = new JSONObject().put("state", state).put("error", error)
            .put("pid", Process.myPid()).put("updated", android.os.SystemClock.elapsedRealtime())
            .put("boot", boot).put("firmware", firmware).put("fingerprint", fingerprint).put("kernel", kernel)
            .put("machine", machine).put("sdk", sdk).put("usbPermission", usbPermission)
            .put("warmWorker", warmWorker);
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
            out.write(value.toString().getBytes("UTF-8"));
            out.getFD().sync();
        }
        Os.chown(temp.getPath(), directoryStat.st_uid, directoryStat.st_gid);
        Os.chmod(temp.getPath(), 0600);
        Os.rename(temp.getPath(), result.getPath());
    }
}
