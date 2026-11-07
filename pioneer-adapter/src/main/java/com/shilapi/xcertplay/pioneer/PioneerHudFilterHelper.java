package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.ErrnoException;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.zip.ZipFile;

/** HUD injector supervisor hosted by PioneerRootHudService; never starts a Java root owner. */
public final class PioneerHudFilterHelper {
    static final String TPS = "jp.pioneer.ceam.thirdpartyservice";
    static final String ROOT = "/data/local/tmp/diplay-hud-filter";
    private static final String INJECTOR_SHA = "7caeb6014c15bc5df22cbb64ee8b639ba824a9a05a6cd90962d8877c7c303e8e";


    static void run(JSONObject request, File files, PioneerRootHudService host) throws Exception {
        RuntimeOwnership runtime = new RuntimeOwnership();
        int uid = request.getInt("uid");
        try {
            if (Process.myUid() != 0) throw new IOException("HUD_FILTER_NOT_ROOT");
            Context context = PioneerVpnPermissionHelper.systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), request.getString("package"), 0, uid / 100000);
            PioneerVpnPermissionHelper.requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), files, new File(app.dataDir, "files"));
            if (Os.stat(files.getPath()).st_uid != uid) throw new IOException("HUD_FILTER_FILES_OWNER");
            CamryAdaptation.INSTANCE.useRequestMode(request);
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("HUD_FILTER_UNSUPPORTED_DEVICE");
            requireOwner(request);
            JSONObject firmware = PioneerHudFirmware.inspect("startup_enforced", path -> sha(new File(path)));
            host.firmwareVerification(firmware);
            PioneerHudFirmware.requirePassed(firmware);
            File root = new File(ROOT);
            directory(root, 0, 0755);
            File area = new File(root, String.valueOf(uid));
            directory(area, 0, 0755);
            runtime.lockStream = PioneerRootFiles.output(new File(area, "lock").getPath(),
                OsConstants.O_CREAT | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600);
            long until = SystemClock.elapsedRealtime() + 3000;
            while (runtime.lock == null && SystemClock.elapsedRealtime() < until) {
                runtime.lock = runtime.lockStream.getChannel().tryLock();
                if (runtime.lock == null) Thread.sleep(100);
            }
            if (runtime.lock == null) throw new IOException("HUD_FILTER_HOST_ALREADY_RUNNING");
            requireOwner(request);
            retireOrphanedRuntimes(area, request);
            File controlDir = new File(area, "control"), feedbackDir = new File(area, "feedback");
            directory(controlDir, 0, 0755, uid); // Migrate legacy app-owned control to root-only writes.
            directory(feedbackDir, 1000, 0755);
            File control = new File(controlDir, "lease.json"), status = new File(feedbackDir, "status.json");
            // A new host never inherits an old owner's active lease/acknowledgement.
            write(control, "{}", 0, 0644);
            write(status, "{}", 1000, 0644);
            File injector = new File(area, "injector"), script = new File(area, "filter.js"), runtimeHost = new File(area, "runtime-host");
            try (ZipFile apk = new ZipFile(request.getString("apk"))) {
                extract(apk, "pioneer-hud-injector", injector, 0755, 32 * 1024 * 1024);
                extract(apk, "pioneer-hud-filter.js", script, 0644, 65536);
                extract(apk, "pioneer-hud-runtime-host", runtimeHost, 0755, 131072);
            }
            if (!sha(injector).equals(INJECTOR_SHA)) throw new IOException("HUD_FILTER_RUNTIME_MISMATCH");
            request.put("state", "RUNNING").put("helperPid", Process.myPid()).put("helperStart", start(Process.myPid()));
            host.prepared(control, status);
            int target = 0;
            String targetStart = "";
            long retryAt = 0;
            while (!host.stopped()) {
                requireOwner(request);
                int pid = isTps(target, targetStart) ? target : findTps();
                boolean ended = runtime.child != null && exited(runtime.child);
                if (runtime.child != null && !ended && SystemClock.elapsedRealtime() >= retryAt) {
                    JSONObject acknowledgement = host.acknowledgement();
                    if (!acknowledgement.optBoolean("installed") ||
                        !request.getString("token").equals(acknowledgement.optString("token"))) {
                        host.failed("HUD_FILTER_ACK_TIMEOUT");
                        if (!runtime.stopChild()) throw new IOException("HUD_FILTER_RUNTIME_CLEANUP_UNCONFIRMED");
                        write(status, "{}", 1000, 0644);
                        retryAt = SystemClock.elapsedRealtime() + 15000;
                    }
                }
                if (runtime.child != null && (pid != target || ended)) {
                    if (!runtime.stopChild()) throw new IOException("HUD_FILTER_RUNTIME_CLEANUP_UNCONFIRMED");
                    write(status, "{}", 1000, 0644);
                    retryAt = SystemClock.elapsedRealtime() + 5000;
                }
                if (runtime.child == null && pid > 1 && SystemClock.elapsedRealtime() >= retryAt) {
                    target = pid; targetStart = start(pid);
                    JSONObject config = new JSONObject(request.toString())
                        .put("control", control.getPath()).put("status", status.getPath())
                        .put("tpsStart", targetStart).put("helperName", command(Process.myPid()));
                    runtime.attach(new ProcessBuilder(runtimeHost.getPath(), String.valueOf(Process.myPid()), injector.getPath(), "-p", String.valueOf(pid),
                        "-s", script.getPath(), "-P", config.toString(), "-R", "qjs")
                        .redirectErrorStream(true).start());
                    retryAt = SystemClock.elapsedRealtime() + 15000;
                }
                // No fast death watchdog: the injected lease expires independently after twenty seconds.
                // This low-frequency supervision only reclaims helpers and recovers TPS/injector restarts.
                host.refresh();
                Thread.sleep(10000);
            }
        } finally {
            // Transfer the actual lock/channel/child/drain, not merely an error flag. Closing the
            // channel on an uncertain exit would allow another owner to inject into the same TPS.
            if (!runtime.closeConfirmed()) host.cleanupUnconfirmed(runtime);
        }
    }

    static void requireOwner(JSONObject r) throws Exception {
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
    static boolean isTps(int pid, String expectedStart) {
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
        directory(dir, uid, mode, uid);
    }
    private static void directory(File dir, int uid, int mode, int legacyUid) throws Exception {
        if (!dir.exists() && !dir.mkdir()) throw new IOException("HUD_FILTER_DIRECTORY");
        android.system.StructStat st = Os.lstat(dir.getPath());
        if (!OsConstants.S_ISDIR(st.st_mode) || st.st_uid != uid && st.st_uid != 0 && st.st_uid != legacyUid)
            throw new IOException("HUD_FILTER_DIRECTORY_OWNER");
        Os.chown(dir.getPath(), uid, uid); Os.chmod(dir.getPath(), mode);
    }
    private static void extract(ZipFile apk, String name, File dest, int mode, int limit) throws Exception {
        File stage = new File(dest.getPath() + ".stage");
        try {
            try (InputStream in = apk.getInputStream(apk.getEntry("assets/" + name));
                 FileOutputStream out = PioneerRootFiles.output(stage.getPath(),
                     OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, mode)) {
                byte[] bytes = new byte[8192]; int count, total = 0;
                while ((count = in.read(bytes)) >= 0) {
                    total += count;
                    if (total > limit) throw new IOException("HUD_FILTER_ASSET_SIZE");
                    out.write(bytes, 0, count);
                }
                Os.fchown(out.getFD(), 0, 0); Os.fchmod(out.getFD(), mode); out.getFD().sync();
            }
            // Never truncate an executable inode that a legacy process may still have mapped.
            Os.rename(stage.getPath(), dest.getPath());
        } finally { stage.delete(); }
    }

    /** Recognize only this application's fixed injector invocation and private lease paths. */
    static JSONObject legacyRuntimeOwner(String[] args, File area, JSONObject current) {
        try {
            if (args.length != 9 || !args[0].equals(new File(area, "injector").getPath()) ||
                !args[1].equals("-p") || Integer.parseInt(args[2]) <= 1 ||
                !args[3].equals("-s") || !args[4].equals(new File(area, "filter.js").getPath()) ||
                !args[5].equals("-P") || !args[7].equals("-R") || !args[8].equals("qjs")) return null;
            JSONObject old = new JSONObject(args[6]);
            if (!old.getString("package").equals(current.getString("package")) ||
                old.getInt("uid") != current.getInt("uid") ||
                !old.getString("boot").equals(current.getString("boot")) ||
                !old.getString("control").equals(new File(area, "control/lease.json").getPath()) ||
                !old.getString("status").equals(new File(area, "feedback/status.json").getPath()) ||
                old.getInt("appPid") <= 1 || old.getInt("helperPid") <= 1 ||
                !old.getString("appStart").matches("[0-9]+") || !old.getString("helperStart").matches("[0-9]+")) return null;
            return old;
        } catch (Exception invalid) { return null; }
    }

    private static boolean sameProcess(int pid, String expectedStart) {
        try { return start(pid).equals(expectedStart); } catch (Exception ended) { return false; }
    }

    private static void retireOrphanedRuntimes(File area, JSONObject current) throws Exception {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) throw new IOException("HUD_FILTER_PROCESS_DISCOVERY_FAILED");
        for (File process : processes) {
            final int pid;
            final JSONObject old;
            final String processStart;
            final boolean previousOwnerActive;
            try {
                pid = Integer.parseInt(process.getName());
                if (pid <= 1 || pid == Process.myPid()) continue;
                processStart = start(pid);
                if (Os.stat(process.getPath()).st_uid != 0) continue;
                String[] args = read(new File(process, "cmdline"), 8192).split("\u0000");
                old = legacyRuntimeOwner(args, area, current);
                if (old == null) continue;
                String exe = Os.readlink(new File(process, "exe").getPath());
                String expected = new File(area, "injector").getPath();
                if (!exe.equals(expected) && !exe.equals(expected + " (deleted)")) continue;
                if (!sameProcess(pid, processStart)) continue;
                // A live matching supervisor is never a migration target.
                previousOwnerActive = sameProcess(old.getInt("appPid"), old.getString("appStart")) &&
                    sameProcess(old.getInt("helperPid"), old.getString("helperStart"));
            } catch (Exception unrelatedOrEnded) { continue; }
            if (previousOwnerActive) throw new IOException("HUD_FILTER_PREVIOUS_OWNER_ACTIVE");
            if (!sameProcess(pid, processStart)) continue;
            signalIfPresent(pid, OsConstants.SIGTERM);
            long until = SystemClock.elapsedRealtime() + 500;
            while (sameProcess(pid, processStart) && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            if (sameProcess(pid, processStart)) {
                signalIfPresent(pid, OsConstants.SIGKILL);
                until = SystemClock.elapsedRealtime() + 500;
                while (sameProcess(pid, processStart) && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
            }
            if (sameProcess(pid, processStart)) throw new IOException("HUD_FILTER_ORPHAN_EXIT_UNCONFIRMED");
        }
    }
    private static void signalIfPresent(int pid, int signal) throws ErrnoException {
        try { Os.kill(pid, signal); }
        catch (ErrnoException ended) {
            if (ended.errno != OsConstants.ESRCH) throw ended;
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
    static void write(File file, String data, int uid, int mode) throws Exception {
        try (FileOutputStream out = PioneerRootFiles.output(file.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, mode)) {
            out.write(data.getBytes("UTF-8")); Os.fchown(out.getFD(), uid, uid); Os.fchmod(out.getFD(), mode);
        }
    }
    private static boolean exited(java.lang.Process p) { try { p.exitValue(); return true; } catch (IllegalThreadStateException running) { return false; } }
    /** One bounded native lifetime. An uncertain lifetime retains its OS lock until root exits. */
    static final class RuntimeOwnership {
        FileOutputStream lockStream;
        FileLock lock;
        java.lang.Process child;
        Thread drain;
        private boolean uncertain;

        void attach(java.lang.Process process) throws IOException {
            if (child != null || uncertain) throw new IOException("HUD_FILTER_RUNTIME_STILL_OWNED");
            child = process; // Record ownership before any stream/thread setup can fail.
            drain = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] bytes = new byte[1024]; int count, logged = 0;
                    while ((count = in.read(bytes)) >= 0) {
                        if (logged < 4096) android.util.Log.w("DiPlay-HudFilter", new String(bytes, 0, Math.min(count, 4096 - logged), "UTF-8"));
                        logged = Math.min(4096, logged + count);
                    }
                } catch (IOException ignored) { }
            }, "hud-filter-runtime-output");
            drain.setDaemon(true); drain.start();
            process.getOutputStream().close();
        }

        boolean stopChild() {
            if (uncertain) return false;
            boolean interrupted = Thread.interrupted();
            try {
                if (child != null && !exited(child)) {
                    try { child.destroy(); } catch (Exception ignored) { }
                    interrupted |= await(() -> exited(child), 1000);
                    if (!exited(child)) {
                        forceKill(child);
                        interrupted |= await(() -> exited(child), 500);
                    }
                }
                if (drain != null && drain.isAlive()) {
                    interrupted |= await(() -> !drain.isAlive(), 250);
                    if (drain.isAlive()) {
                        // Never synchronously close a stream whose read/close might itself block.
                        drain.interrupt();
                        interrupted |= await(() -> !drain.isAlive(), 250);
                    }
                }
                if ((child != null && !exited(child)) || (drain != null && drain.isAlive())) {
                    uncertain = true;
                    return false;
                }
                child = null; drain = null;
                return true;
            } catch (Throwable failure) {
                uncertain = true;
                return false;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        boolean closeConfirmed() {
            if (!stopChild()) return false;
            try {
                if (lock != null) lock.release();
                if (lockStream != null) lockStream.close();
                lock = null; lockStream = null;
                return true;
            } catch (Exception failure) { uncertain = true; return false; }
        }

        private interface Finished { boolean get(); }
        private static boolean await(Finished finished, long millis) {
            boolean interrupted = false;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
            while (!finished.get()) {
                long remaining = until - System.nanoTime();
                if (remaining <= 0) break;
                try { Thread.sleep(Math.max(1, Math.min(20, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)))); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            return interrupted;
        }

        private static void forceKill(java.lang.Process process) {
            try {
                // API 26+ through reflection; the target Camry runs API 25.
                java.lang.Process.class.getMethod("destroyForcibly").invoke(process);
                return;
            } catch (Exception ignored) { }
            try {
                java.lang.reflect.Field pid = process.getClass().getDeclaredField("pid");
                pid.setAccessible(true);
                int value = pid.getInt(process);
                if (value > 1) Process.killProcess(value);
            } catch (Exception ignored) { }
        }
    }
}
