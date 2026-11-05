package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** App-owned standalone injection host. No map package, TPS service or firmware file is modified. */
public final class PioneerHudFilterHelper {
    static final String TPS = "jp.pioneer.ceam.thirdpartyservice";
    static final String ROOT = "/data/local/tmp/diplay-hud-filter";
    private static final String INJECTOR_SHA = "7caeb6014c15bc5df22cbb64ee8b639ba824a9a05a6cd90962d8877c7c303e8e";
    private static final String[][] MODULES = {
        {"/system/lib/libmeterservice.so", "20d3521ff1294d3a56579e1b0c649be581a94a9a0276a362ad79e130271dc660"},
        {"/system/lib/libmeterservice_jni.so", "e880f91b1932645d3ae2a83b739aa24c1fb0209cbc3f393b5f0539ef7981dbbc"},
        {"/system/lib/libNPMeterHandler.so", "937c951c9594057fed65be0bd41163379cca4a48c9ccd2f9b91929cdee2b49a6"}
    };

    public static void main(String[] args) {
        if (args.length != 1 || Process.myUid() != 0) return;
        JSONObject request = null;
        File descriptor = null;
        java.lang.Process child = null;
        try {
            descriptor = new File(args[0]).getCanonicalFile();
            if (!descriptor.getPath().matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/hud-filter/[0-9a-f-]{36}\\.json"))
                throw new IOException("HUD_FILTER_REQUEST_PATH");
            int uid = Os.lstat(descriptor.getPath()).st_uid;
            PioneerVpnPermissionHelper.requireFile(Os.lstat(descriptor.getPath()), false, uid);
            request = new JSONObject(read(descriptor, 8192));
            if (!descriptor.getName().equals(request.getString("token") + ".json") || uid != request.getInt("uid"))
                throw new IOException("HUD_FILTER_REQUEST_OWNER");
            Context context = PioneerVpnPermissionHelper.systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), request.getString("package"), 0, uid / 100000);
            PioneerVpnPermissionHelper.requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), descriptor.getParentFile().getParentFile(), new File(app.dataDir, "files"));
            CamryAdaptation.INSTANCE.useRequestMode(request);
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("HUD_FILTER_UNSUPPORTED_DEVICE");
            long age = SystemClock.elapsedRealtime() - request.getLong("requestedMs");
            if (age < 0 || age > 30000) throw new IOException("HUD_FILTER_REQUEST_EXPIRED");
            requireOwner(request);
            for (String[] module : MODULES)
                if (!sha(new File(module[0])).equals(module[1])) throw new IOException("HUD_FILTER_FIRMWARE_MISMATCH " + module[0]);

            File root = new File(ROOT);
            directory(root, 0, 0755);
            File area = new File(root, String.valueOf(uid));
            directory(area, 0, 0755);
            try (FileOutputStream lockStream = PioneerRootFiles.output(new File(area, "lock").getPath(),
                    OsConstants.O_CREAT | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600)) {
                FileLock lock = null;
                long until = SystemClock.elapsedRealtime() + 3000;
                while (lock == null && SystemClock.elapsedRealtime() < until) {
                    lock = lockStream.getChannel().tryLock();
                    if (lock == null) Thread.sleep(100);
                }
                if (lock == null) throw new IOException("HUD_FILTER_HOST_ALREADY_RUNNING");
                try {
                    requireOwner(request);
                    File controlDir = new File(area, "control"), feedbackDir = new File(area, "feedback");
                    directory(controlDir, uid, 0755);
                    directory(feedbackDir, 1000, 0755);
                    File control = new File(controlDir, "lease.json"), status = new File(feedbackDir, "status.json");
                    // A new host never inherits an old owner's active lease/acknowledgement.
                    write(control, "{}", uid, 0644);
                    write(status, "{}", 1000, 0644);
                    File injector = new File(area, "injector"), script = new File(area, "filter.js"), runtimeHost = new File(area, "runtime-host");
                    try (ZipFile apk = new ZipFile(app.sourceDir)) {
                        extract(apk, "pioneer-hud-injector", injector, 0755, 32 * 1024 * 1024);
                        extract(apk, "pioneer-hud-filter.js", script, 0644, 65536);
                        extract(apk, "pioneer-hud-runtime-host", runtimeHost, 0755, 131072);
                    }
                    if (!sha(injector).equals(INJECTOR_SHA)) throw new IOException("HUD_FILTER_RUNTIME_MISMATCH");
                    request.put("state", "RUNNING").put("helperPid", Process.myPid()).put("helperStart", start(Process.myPid()));
                    publish(descriptor, request, uid);
                    int target = 0;
                    String targetStart = "";
                    long retryAt = 0;
                    while (true) {
                        requireOwner(request);
                        int pid = isTps(target, targetStart) ? target : findTps();
                        boolean ended = child != null && exited(child);
                        if (child != null && !ended && SystemClock.elapsedRealtime() >= retryAt) {
                            JSONObject acknowledgement = new JSONObject(read(status, 4096));
                            if (!acknowledgement.optBoolean("installed") ||
                                !request.getString("token").equals(acknowledgement.optString("token"))) {
                                request.put("runtimeError", "HUD_FILTER_ACK_TIMEOUT"); publish(descriptor, request, uid);
                                stop(child); child = null;
                                write(status, "{}", 1000, 0644);
                                retryAt = SystemClock.elapsedRealtime() + 15000;
                            } else if (request.has("runtimeError")) {
                                request.remove("runtimeError"); publish(descriptor, request, uid);
                            }
                        }
                        if (child != null && (pid != target || ended || !start(target).equals(targetStart))) {
                            stop(child); child = null;
                            write(status, "{}", 1000, 0644);
                            retryAt = SystemClock.elapsedRealtime() + 5000;
                        }
                        if (child == null && pid > 1 && SystemClock.elapsedRealtime() >= retryAt) {
                            target = pid; targetStart = start(pid);
                            JSONObject config = new JSONObject(request.toString())
                                .put("control", control.getPath()).put("status", status.getPath())
                                .put("tpsStart", targetStart).put("helperName", command(Process.myPid()));
                            child = new ProcessBuilder(runtimeHost.getPath(), String.valueOf(Process.myPid()), injector.getPath(), "-p", String.valueOf(pid),
                                "-s", script.getPath(), "-P", config.toString(), "-R", "qjs")
                                .redirectErrorStream(true).start();
                            child.getOutputStream().close();
                            final java.lang.Process observed = child;
                            Thread drain = new Thread(() -> {
                                try (InputStream in = observed.getInputStream()) {
                                    byte[] bytes = new byte[1024]; int count, logged = 0;
                                    while ((count = in.read(bytes)) >= 0) {
                                        if (logged < 4096) android.util.Log.w("DiPlay-HudFilter", new String(bytes, 0, Math.min(count, 4096 - logged), "UTF-8"));
                                        logged = Math.min(4096, logged + count);
                                    }
                                } catch (IOException ignored) { }
                            }, "hud-filter-runtime-output");
                            drain.setDaemon(true); drain.start();
                            retryAt = SystemClock.elapsedRealtime() + 15000;
                        }
                        // Owner death is separate from the five-second navigation heartbeat.
                        Thread.sleep(500);
                    }
                } finally { stop(child); child = null; lock.release(); }
            }
        } catch (Throwable error) {
            android.util.Log.w("DiPlay-HudFilter", "Filter host ended", error);
            if (request != null && descriptor != null) try {
                request.put("state", "CLOSED").put("error", error.toString());
                publish(descriptor, request, request.getInt("uid"));
            } catch (Exception ignored) { }
        } finally { stop(child); }
        System.exit(0);
    }

    private static void requireOwner(JSONObject r) throws Exception {
        int pid = r.getInt("appPid");
        if (Os.stat("/proc/" + pid).st_uid != r.getInt("uid") || !start(pid).equals(r.getString("appStart")) ||
            !command(pid).equals(r.getString("package")) ||
            !read(new File("/proc/sys/kernel/random/boot_id"), 128).trim().equals(r.getString("boot")))
            throw new IOException("HUD_FILTER_OWNER_ENDED");
    }
    private static int findTps() {
        File[] entries = new File("/proc").listFiles();
        if (entries != null) for (File entry : entries) try {
            int pid = Integer.parseInt(entry.getName());
            if (Os.stat(entry.getPath()).st_uid == 1000 && command(pid).equals(TPS)) return pid;
        } catch (Exception ignored) { }
        return 0;
    }
    private static boolean isTps(int pid, String expectedStart) {
        try { return pid > 1 && Os.stat("/proc/" + pid).st_uid == 1000 && start(pid).equals(expectedStart) && command(pid).equals(TPS); }
        catch (Exception ignored) { return false; }
    }
    static String start(int pid) throws Exception {
        String stat = read(new File("/proc/" + pid + "/stat"), 8192);
        String[] fields = stat.substring(stat.lastIndexOf(") ") + 2).split(" ");
        if (fields[0].equals("Z")) throw new IOException("HUD_FILTER_PROCESS_ZOMBIE");
        return fields[19];
    }
    private static String command(int pid) throws Exception {
        return read(new File("/proc/" + pid + "/cmdline"), 4096).split("\u0000", 2)[0];
    }
    private static void directory(File dir, int uid, int mode) throws Exception {
        if (!dir.exists() && !dir.mkdir()) throw new IOException("HUD_FILTER_DIRECTORY");
        android.system.StructStat st = Os.lstat(dir.getPath());
        if (!OsConstants.S_ISDIR(st.st_mode) || st.st_uid != uid && st.st_uid != 0)
            throw new IOException("HUD_FILTER_DIRECTORY_OWNER");
        Os.chown(dir.getPath(), uid, uid); Os.chmod(dir.getPath(), mode);
    }
    private static void extract(ZipFile apk, String name, File dest, int mode, int limit) throws Exception {
        try (InputStream in = apk.getInputStream(apk.getEntry("assets/" + name));
             FileOutputStream out = PioneerRootFiles.output(dest.getPath(),
                 OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, mode)) {
            byte[] bytes = new byte[8192]; int count, total = 0;
            while ((count = in.read(bytes)) >= 0) { total += count; if (total > limit) throw new IOException("HUD_FILTER_ASSET_SIZE"); out.write(bytes, 0, count); }
            Os.fchown(out.getFD(), 0, 0); Os.fchmod(out.getFD(), mode);
        }
    }
    private static String sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[8192]; int count; while ((count = in.read(bytes)) >= 0) digest.update(bytes, 0, count);
        }
        StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    static String read(File file, int max) throws Exception { return PioneerVpnPermissionHelper.readFile(file, max); }
    private static void write(File file, String data, int uid, int mode) throws Exception {
        try (FileOutputStream out = PioneerRootFiles.output(file.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, mode)) {
            out.write(data.getBytes("UTF-8")); Os.fchown(out.getFD(), uid, uid); Os.fchmod(out.getFD(), mode);
        }
    }
    private static void publish(File file, JSONObject data, int uid) throws Exception {
        File temp = new File(file.getPath() + ".tmp"); write(temp, data.toString(), uid, 0600); Os.rename(temp.getPath(), file.getPath());
    }
    private static boolean exited(java.lang.Process p) { try { p.exitValue(); return true; } catch (IllegalThreadStateException running) { return false; } }
    private static void stop(java.lang.Process p) {
        if (p == null || exited(p)) return;
        p.destroy();
        for (int n = 0; n < 50 && !exited(p); n++) try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        if (!exited(p)) try {
            java.lang.reflect.Field pid = p.getClass().getDeclaredField("pid"); pid.setAccessible(true);
            Process.killProcess(pid.getInt(p));
        } catch (Exception error) { android.util.Log.w("DiPlay-HudFilter", "Runtime exit unconfirmed", error); }
    }
}
