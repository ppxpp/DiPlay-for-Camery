package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;

/** Idle ART process only. All firewall mutations remain inside the ordinary session lease. */
final class PioneerVpnFirewallWarmWorker {
    private static JSONObject read(File file, int uid) throws Exception {
        try (FileInputStream in = new FileInputStream(Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0))) {
            PioneerVpnPermissionHelper.requireFile(Os.fstat(in.getFD()), false, uid);
            return new JSONObject(PioneerVpnPermissionHelper.read(in, 8192));
        }
    }

    static void run(File candidate) throws Exception {
        File descriptor = candidate.getCanonicalFile();
        if (!descriptor.getPath().matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/vpn-firewall/warm-[0-9a-f]{32}\\.json"))
            throw new IOException("VPN_WARM_INVALID_PATH");
        File root = descriptor.getParentFile();
        StructStat dir = Os.lstat(root.getPath());
        PioneerVpnPermissionHelper.requireFile(dir, true, dir.st_uid);
        int uid = dir.st_uid;
        JSONObject request = read(descriptor, uid);
        String token = request.getString("token"), pkg = request.getString("package"), apk = request.getString("apk");
        if (!descriptor.getName().equals("warm-" + token + ".json") || !token.matches("[0-9a-f]{32}") || request.getInt("uid") != uid ||
                !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IOException("VPN_WARM_INVALID_OWNER");
        Context context = PioneerVpnPermissionHelper.systemContext();
        ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
            .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
            .invoke(context.getPackageManager(), pkg, 0, uid / 100000);
        PioneerVpnPermissionHelper.requirePackage(uid, app.uid, apk, app.sourceDir, System.getenv("CLASSPATH"),
            root.getParentFile(), new File(app.dataDir, "files"));
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length != 1 || !pkg.equals(packages[0])) throw new IOException("VPN_WARM_SHARED_UID");
        int appPid = request.getInt("appPid");
        String appStart = request.getString("appStart"), boot = request.getString("boot");
        NativeNcmModule.Guard owner = () -> {
            if (!boot.equals(PioneerVpnPermissionHelper.readFile(new File("/proc/sys/kernel/random/boot_id"), 128).trim()) ||
                    Os.stat("/proc/" + appPid).st_uid != uid || !apk.equals(System.getenv("CLASSPATH")))
                throw new IOException("VPN_WARM_OWNER_CHANGED");
            String stat = PioneerVpnPermissionHelper.readFile(new File("/proc/" + appPid + "/stat"), 8192);
            if (!appStart.equals(stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19])) throw new IOException("VPN_WARM_OWNER_CHANGED");
        };
        owner.check();
        if (SystemClock.elapsedRealtime() >= request.getLong("deadline")) throw new IOException("VPN_WARM_EXPIRED");
        if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("VPN_WARM_UNSUPPORTED_DEVICE");
        PioneerNativeNcmPrewarm.requireSupportedFirmware(); // Read-only; never loads an NCM module.
        String socketName = "diplay-vpn-" + UUID.randomUUID();
        try (LocalServerSocket server = new LocalServerSocket(socketName)) {
            State state = new State();
            Thread monitor = new Thread(() -> {
                while (!state.closed) {
                    try {
                        owner.check();
                        if (!state.busy && SystemClock.elapsedRealtime() >= state.idleUntil) break;
                        Thread.sleep(500);
                    } catch (Throwable ended) { break; }
                }
                state.closed = true;
                File active = state.active;
                if (active != null) try (FileOutputStream out = new FileOutputStream(Os.open(new File(active, "stop").getPath(),
                        OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
                    Os.fchown(out.getFD(), uid, uid);
                    out.write(active.getName().getBytes("US-ASCII"));
                } catch (Exception ignored) { }
                try { server.close(); } catch (IOException ignored) { }
            }, "vpn-warm-owner");
            monitor.setDaemon(true);
            monitor.start();
            try {
                JSONObject ready = new JSONObject(request.toString()).put("state", "READY").put("pid", Process.myPid())
                    .put("socket", socketName).put("updated", SystemClock.elapsedRealtime())
                    .put("rootStart", processStart(Process.myPid()));
                publish(descriptor, ready, uid);
                while (!state.closed) {
                    boolean authenticated = false;
                    try (LocalSocket peer = server.accept()) {
                        peer.setSoTimeout(2000);
                        owner.check();
                        Credentials credentials = peer.getPeerCredentials();
                        if (credentials.getUid() != uid || credentials.getPid() != appPid) throw new IOException("VPN_WARM_PEER_OWNER");
                        authenticated = true;
                        state.busy = true;
                        File lease = NativeNcmWarmProtocol.resolveLease(root, NativeNcmWarmProtocol.readTask(peer.getInputStream()));
                        PioneerVpnPermissionHelper.requireFile(Os.lstat(lease.getPath()), true, uid);
                        if (new File(lease, "result.json").exists()) throw new IOException("VPN_WARM_ALREADY_SUBMITTED");
                        JSONObject task = read(new File(lease, "request.json"), uid);
                        if (!apk.equals(task.getString("apk")) || !boot.equals(task.getString("boot")) || !pkg.equals(task.getString("package")) ||
                                uid != task.getInt("uid") || appPid != task.getInt("appPid") || !appStart.equals(task.getString("appStart")) ||
                                !lease.getName().equals(task.getString("token")) || task.getLong("deadline") <= SystemClock.elapsedRealtime())
                            throw new IOException("VPN_WARM_STALE_REQUEST");
                        owner.check();
                        if (state.closed) throw new IOException("VPN_WARM_EXPIRED");
                        state.active = lease;
                        NativeNcmWarmProtocol.accept(peer.getOutputStream());
                        if (!PioneerVpnFirewallHelper.runReusable(lease)) return;
                    } catch (Exception rejected) {
                        if (state.active != null || state.closed) return;
                    } finally {
                        state.active = null;
                        if (authenticated) state.idleUntil = SystemClock.elapsedRealtime() + 120_000;
                        state.busy = false;
                    }
                }
            } finally { state.closed = true; }
        }
    }

    private static String processStart(int pid) throws Exception {
        String stat = PioneerVpnPermissionHelper.readFile(new File("/proc/" + pid + "/stat"), 8192);
        return stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19];
    }

    private static void publish(File file, JSONObject data, int uid) throws Exception {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(), OsConstants.O_CREAT | OsConstants.O_TRUNC |
                OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
            out.write(data.toString().getBytes("UTF-8"));
            out.getFD().sync();
            Os.fchown(out.getFD(), uid, uid);
        }
        Os.rename(temp.getPath(), file.getPath());
    }

    private static final class State {
        volatile boolean closed, busy;
        volatile File active;
        volatile long idleUntil = SystemClock.elapsedRealtime() + 120_000;
    }
}
