package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Credentials;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded initialization host. USB and VPN tasks can return independently in one root process. */
public final class PioneerRootSessionHelper {
    public static void main(String[] args) {
        if (args.length != 1 || Process.myUid() != 0) return;
        File descriptor = null;
        JSONObject request = null;
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try {
            descriptor = new File(args[0]).getCanonicalFile();
            if (!descriptor.getPath().matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/root-init/[0-9a-f-]{36}\\.json"))
                throw new IOException("ROOT_INIT_INVALID_PATH");
            int uid = Os.lstat(descriptor.getPath()).st_uid;
            PioneerVpnPermissionHelper.requireFile(Os.lstat(descriptor.getPath()), false, uid);
            request = new JSONObject(PioneerVpnPermissionHelper.readFile(descriptor, 8192));
            if (!descriptor.getName().equals(request.getString("token") + ".json") || uid != request.getInt("uid"))
                throw new IOException("ROOT_INIT_TOKEN_MISMATCH");
            Context context = PioneerVpnPermissionHelper.systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), request.getString("package"), 0, uid / 100000);
            File files = descriptor.getParentFile().getParentFile();
            PioneerVpnPermissionHelper.requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), files, new File(app.dataDir, "files"));
            CamryAdaptation.INSTANCE.useRequestMode(request);
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("ROOT_INIT_UNSUPPORTED_DEVICE");
            long now = SystemClock.elapsedRealtime();
            if (request.getLong("requestedAtMs") > now || request.getLong("expiresAt") <= now ||
                request.getLong("expiresAt") - request.getLong("requestedAtMs") > 180_000)
                throw new IOException("ROOT_INIT_EXPIRED");
            final JSONObject owner = new JSONObject(request.toString());
            final File marker = descriptor;
            final java.util.Set<String> submitted = java.util.concurrent.ConcurrentHashMap.newKeySet();
            final AtomicInteger active = new AtomicInteger();
            final AtomicBoolean closed = new AtomicBoolean();
            final AtomicBoolean terminalPublished = new AtomicBoolean();
            try (LocalServerSocket server = new LocalServerSocket("diplay-init-" + UUID.randomUUID())) {
                Thread monitor = new Thread(() -> {
                    try {
                        while (!closed.get()) {
                            requireOwner(owner);
                            if (SystemClock.elapsedRealtime() >= owner.optLong("expiresAt") ||
                                (new File(marker.getPath()+".stop").exists() && active.get() == 0)) break;
                            Thread.sleep(1000);
                        }
                    } catch (Throwable error) { android.util.Log.e("DiPlayVpnRoot", "Initialization owner ended", error); }
                    closed.set(true);
                    try { server.close(); } catch (IOException ignored) { }
                    // Do not leave a blocked Binder task or accepted socket holding root alive.
                    if (terminalPublished.compareAndSet(false,true)) try {
                        JSONObject terminal = new JSONObject(owner.toString()).put("state",active.get() > 0 ? "ERROR" : "CLOSED")
                            .put("pid",Process.myPid()).put("closedAtMs",SystemClock.elapsedRealtime());
                        publish(marker,terminal,owner.getInt("uid"));
                    } catch (Exception failure) { android.util.Log.e("DiPlayVpnRoot","Cannot publish initialization exit",failure); }
                    if (active.get() > 0) Process.killProcess(Process.myPid());
                    // On API25 closing LocalServerSocket from another thread does not reliably
                    // wake accept(). All tasks have finished, so explicitly end this ART process.
                    System.exit(0);
                }, "root-init-owner");
                monitor.setDaemon(true);
                request.put("state", "READY").put("socket", server.getLocalSocketAddress().getName())
                    .put("pid", Process.myPid()).put("readyAtMs", SystemClock.elapsedRealtime());
                publish(descriptor, request, uid);
                monitor.start();
                while (!closed.get()) {
                    LocalSocket peer;
                    try { peer = server.accept(); } catch (IOException stopped) { if (closed.get()) break; throw stopped; }
                    try {
                        peer.setSoTimeout(2000);
                        Credentials credentials = peer.getPeerCredentials();
                        if (credentials.getUid() != uid || credentials.getPid() != owner.getInt("appPid")) throw new IOException("ROOT_INIT_PEER_OWNER");
                        requireOwner(owner);
                        File directory = new File(PioneerRootTaskProtocol.readTask(peer.getInputStream())).getCanonicalFile();
                        File parent = directory.getParentFile();
                        if (!parent.getParentFile().equals(files) ||
                            !java.util.Arrays.asList("vpn-permission", "usb-permission-probe", "vpn-firewall").contains(parent.getName()))
                            throw new IOException("ROOT_INIT_TASK_PATH");
                        PioneerVpnPermissionHelper.requireFile(Os.lstat(directory.getPath()), true, uid);
                        if (new File(directory,"result.json").exists()) throw new IOException("ROOT_INIT_ALREADY_SUBMITTED");
                        File input = new File(directory,"request.json");
                        PioneerVpnPermissionHelper.requireFile(Os.lstat(input.getPath()),false,uid);
                        JSONObject task = new JSONObject(PioneerVpnPermissionHelper.readFile(input,8192));
                        for (String key : new String[]{"package", "boot", "appStart"})
                            if (!owner.getString(key).equals(task.getString(key))) throw new IOException("ROOT_INIT_TASK_OWNER");
                        if (task.getInt("uid") != uid || task.getInt("appPid") != owner.getInt("appPid")) throw new IOException("ROOT_INIT_TASK_UID");
                        if (task.optLong("deadline") <= SystemClock.elapsedRealtime()) throw new IOException("ROOT_INIT_TASK_EXPIRED");
                        if (active.incrementAndGet() > 2) { active.decrementAndGet(); throw new IOException("ROOT_INIT_BUSY"); }
                        if (!submitted.add(directory.getPath())) { active.decrementAndGet(); throw new IOException("ROOT_INIT_DUPLICATE_TASK"); }
                        tasks.execute(() -> {
                            try {
                                if (parent.getName().equals("vpn-permission")) PioneerVpnPermissionHelper.runTask(directory);
                                else if (parent.getName().equals("usb-permission-probe")) PioneerUsbPermissionHelper.runTask(directory);
                                else PioneerVpnFirewallHelper.runTask(directory);
                            } finally { active.decrementAndGet(); }
                        });
                        PioneerRootTaskProtocol.accept(peer.getOutputStream());
                    } catch (Throwable error) { android.util.Log.e("DiPlayVpnRoot", "Initialization request rejected", error); }
                    finally { peer.close(); }
                }
                closed.set(true);
            }
            if (terminalPublished.compareAndSet(false,true)) {
                request.put("state", "CLOSED"); publish(descriptor,request,uid);
            }
        } catch (Throwable error) {
            android.util.Log.e("DiPlayVpnRoot", "Initialization failed", error);
            if (request != null && descriptor != null) try {
                request.put("state","ERROR"); publish(descriptor,request,request.getInt("uid"));
            } catch (Exception ignored) { }
        } finally { tasks.shutdownNow(); }
        System.exit(0);
    }
    private static void requireOwner(JSONObject request) throws Exception {
        int pid = request.getInt("appPid");
        String stat = PioneerVpnPermissionHelper.readFile(new File("/proc/"+pid+"/stat"),8192);
        if (Os.stat("/proc/"+pid).st_uid != request.getInt("uid") ||
            !request.getString("appStart").equals(stat.substring(stat.lastIndexOf(") ")+2).split(" ")[19]) ||
            !request.getString("boot").equals(PioneerVpnPermissionHelper.readFile(new File("/proc/sys/kernel/random/boot_id"),128).trim()))
            throw new IOException("ROOT_INIT_OWNER_CHANGED");
    }
    private static void publish(File file, JSONObject data, int uid) throws Exception {
        File temp = new File(file.getPath()+".tmp");
        try (FileOutputStream out = PioneerRootFiles.output(temp.getPath(), android.system.OsConstants.O_CREAT | android.system.OsConstants.O_TRUNC | android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NOFOLLOW,0600)) {
            out.write(data.toString().getBytes("UTF-8")); Os.fchown(out.getFD(),uid,uid);
        }
        Os.rename(temp.getPath(),file.getPath());
    }
}
