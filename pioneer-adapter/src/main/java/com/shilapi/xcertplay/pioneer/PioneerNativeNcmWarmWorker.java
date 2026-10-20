package com.shilapi.xcertplay.pioneer;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.util.UUID;

/** Same-owner NCM tasks only; no command execution API. Reuses the prewarm ART process. */
final class PioneerNativeNcmWarmWorker implements Closeable {
    private final File root;
    private final int uid, appPid;
    private final String appStart, apk, boot, name;
    private final NativeNcmModule.Guard owner;
    private final LocalServerSocket server;
    private volatile boolean closed, busy;
    private volatile File active;
    private volatile long idleUntil = SystemClock.elapsedRealtime() + 120_000;

    PioneerNativeNcmWarmWorker(File root, int uid, int appPid, String appStart,
                              String apk, String boot, NativeNcmModule.Guard owner) throws Exception {
        this.root = root.getCanonicalFile(); this.uid = uid; this.appPid = appPid;
        this.appStart = appStart; this.apk = apk; this.boot = boot; this.owner = owner;
        name = "diplay-ncm-" + UUID.randomUUID();
        server = new LocalServerSocket(name);
    }

    JSONObject descriptor() throws Exception {
        return new JSONObject().put("socket", name).put("appPid", appPid).put("appStart", appStart);
    }

    void serve() throws Exception {
        Thread monitor = new Thread(() -> {
            while (!closed) {
                try {
                    owner.check();
                    if (!busy && SystemClock.elapsedRealtime() >= idleUntil) { close(); return; }
                    Thread.sleep(500);
                } catch (Throwable ended) {
                    // Cancel only the exact lease accepted by this worker; let its normal cleanup run.
                    File lease = active;
                    if (lease != null) try (FileOutputStream out = new FileOutputStream(Os.open(
                            new File(lease, "stop").getPath(), OsConstants.O_CREAT | OsConstants.O_TRUNC |
                                OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
                        Os.fchown(out.getFD(), uid, uid);
                        out.write(lease.getName().getBytes("US-ASCII"));
                    } catch (Exception ignored) { }
                    close(); return;
                }
            }
        }, "ncm-warm-owner");
        monitor.setDaemon(true); monitor.start();
        try {
            while (!closed) {
                boolean authenticated = false;
                try (LocalSocket peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    owner.check();
                    Credentials credentials = peer.getPeerCredentials();
                    if (credentials.getUid() != uid || credentials.getPid() != appPid)
                        throw new IOException("Warm NCM owner mismatch");
                    authenticated = true;
                    busy = true;
                    File lease = NativeNcmWarmProtocol.resolveLease(root,
                            NativeNcmWarmProtocol.readTask(peer.getInputStream()));
                    StructStat dir = Os.lstat(lease.getPath());
                    File request = new File(lease, "request.json");
                    StructStat file = Os.lstat(request.getPath());
                    if (!OsConstants.S_ISDIR(dir.st_mode) || dir.st_uid != uid ||
                        !OsConstants.S_ISREG(file.st_mode) || file.st_uid != uid || file.st_nlink != 1 ||
                        file.st_size <= 0 || file.st_size > 8192 || new File(lease, "status.json").exists())
                        throw new IOException("Invalid warm NCM request owner");
                    byte[] content = new byte[(int) file.st_size];
                    try (DataInputStream input = new DataInputStream(new FileInputStream(request))) { input.readFully(content); }
                    JSONObject value = new JSONObject(new String(content, "UTF-8"));
                    if (!apk.equals(value.getString("apk")) || !boot.equals(value.getString("boot")) ||
                        value.getInt("appPid") != appPid || !appStart.equals(value.getString("appStart")) ||
                        value.getLong("deadline") <= SystemClock.elapsedRealtime())
                        throw new IOException("Stale warm NCM request");
                    owner.check();
                    if (closed) throw new IOException("Warm NCM worker expired");
                    active = lease;
                    NativeNcmWarmProtocol.accept(peer.getOutputStream());
                    // One task at a time. A new helper object carries no previous session state.
                    if (!PioneerNativeNcmHelper.runReusable(lease)) return;
                } catch (Exception rejected) {
                    if (active != null || closed) return;
                    // Unauthenticated/malformed clients cannot stop the owner's idle worker.
                } finally {
                    active = null;
                    if (authenticated) idleUntil = SystemClock.elapsedRealtime() + 120_000;
                    busy = false;
                }
            }
        } finally { close(); }
    }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
    }
}
