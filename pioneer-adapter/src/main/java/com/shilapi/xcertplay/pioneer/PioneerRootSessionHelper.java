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
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Single root ART owner. Independent bounded lanes prevent hotspot work from delaying HUD leases. */
public final class PioneerRootSessionHelper {
    private static final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, job -> {
        Thread thread = new Thread(job, "root-ipc-deadlines"); thread.setDaemon(true); return thread;
    });
    static { deadlines.setRemoveOnCancelPolicy(true); }
    private static final class Task {
        final String id; final long deadline; final AtomicBoolean cancelled = new AtomicBoolean();
        private Thread runner;
        private boolean interruptible;
        Task(String id, long deadline, int uid) { this.id = id; this.deadline = deadline; }
        boolean current(AtomicBoolean closed) { return !closed.get() && !cancelled.get() && SystemClock.elapsedRealtime() < deadline; }
        synchronized void begin(String operation) { runner = Thread.currentThread(); interruptible = operation.equals("CALLBACK"); }
        synchronized void finish() { runner = null; }
        synchronized void cancel() {
            cancelled.set(true);
            // Hold the task lock through interruption so an old timer cannot interrupt a reused lane thread.
            if (interruptible && runner != null) runner.interrupt();
        }
    }
    private static final ThreadLocal<Task> currentTask = new ThreadLocal<>();
    static void checkTask() throws IOException {
        Task task = currentTask.get();
        if (task != null && (task.cancelled.get() || SystemClock.elapsedRealtime() >= task.deadline))
            throw new IOException("ROOT_IPC_TASK_CANCELLED_OR_EXPIRED");
    }
    private static ThreadPoolExecutor lane(String name, int workers, int queued) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queued),
            job -> { Thread thread = new Thread(job, name); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    }
    public static void main(String[] args) {
        if (args.length != 1 || Process.myUid() != 0) return;
        File descriptor = null; JSONObject request = null;
        AtomicBoolean closed = new AtomicBoolean();
        ConcurrentHashMap<String, Task> requests = new ConcurrentHashMap<>();
        Set<LocalSocket> peers = ConcurrentHashMap.newKeySet();
        ThreadPoolExecutor input = lane("root-ipc-input", 2, 8), control = lane("root-ipc-control", 2, 8),
            init = lane("root-ipc-permission", 2, 8), hotspot = lane("root-ipc-hotspot", 1, 4),
            applications = lane("root-ipc-apps", 1, 4), tools = lane("root-ipc-tools", 1, 4), callbacks = lane("root-ipc-callback", 1, 4), diagnostics = lane("root-ipc-diagnostic", 1, 2);
        PioneerRootHudService hud = null;
        try {
            descriptor = new File(args[0]).getCanonicalFile();
            if (!descriptor.getPath().matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/root-init/[0-9a-f-]{36}\\.json"))
                throw new IOException("ROOT_SERVICE_INVALID_PATH");
            int uid = Os.lstat(descriptor.getPath()).st_uid;
            PioneerVpnPermissionHelper.requireFile(Os.lstat(descriptor.getPath()), false, uid);
            request = new JSONObject(PioneerVpnPermissionHelper.readFile(descriptor, 8192));
            if (!descriptor.getName().equals(request.getString("token") + ".json") || uid != request.getInt("uid"))
                throw new IOException("ROOT_SERVICE_TOKEN_MISMATCH");
            Context context = PioneerVpnPermissionHelper.systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), request.getString("package"), 0, uid / 100000);
            File files = descriptor.getParentFile().getParentFile();
            PioneerVpnPermissionHelper.requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), files, new File(app.dataDir, "files"));
            CamryAdaptation.INSTANCE.useRequestMode(request);
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("ROOT_SERVICE_UNSUPPORTED_DEVICE");
            long now = SystemClock.elapsedRealtime();
            if (request.getLong("requestedAtMs") > now || request.getLong("expiresAt") <= now ||
                request.getLong("expiresAt") - request.getLong("requestedAtMs") > 60_000)
                throw new IOException("ROOT_SERVICE_EXPIRED_BOOTSTRAP");
            final JSONObject owner = new JSONObject(request.toString()); final File marker = descriptor;
            hud = new PioneerRootHudService(owner, files); final PioneerRootHudService hudService = hud;
            // Lock is stable across app/CarPlay restarts: never let two root Java owners overlap.
            File lockFile = new File(marker.getParentFile(), "owner.lock");
            try (FileOutputStream lockOutput = PioneerRootFiles.output(lockFile.getPath(),
                    android.system.OsConstants.O_CREAT | android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NOFOLLOW, 0600)) {
                Os.fchown(lockOutput.getFD(), uid, uid);
                FileLock acquired = null; long until = SystemClock.elapsedRealtime() + 15_000;
                while (acquired == null && SystemClock.elapsedRealtime() < until) {
                    requireOwner(owner); acquired = lockOutput.getChannel().tryLock();
                    if (acquired == null) Thread.sleep(200);
                }
                if (acquired == null) throw new IOException("ROOT_SERVICE_OWNER_BUSY");
                try (FileLock ignored = acquired; LocalServerSocket server = new LocalServerSocket("diplay-root-" + UUID.randomUUID())) {
                    Thread monitor = new Thread(() -> {
                        try { while (!closed.get()) { requireOwner(owner); Thread.sleep(10000); } }
                        catch (Throwable ended) {
                            closed.set(true);
                            for (Task task : requests.values()) task.cancel();
                            hudService.close();
                            for (LocalSocket peer : peers) try { peer.close(); } catch (IOException ignoredClose) { }
                            try { server.close(); } catch (IOException ignoredClose) { }
                            try { publish(marker, new JSONObject(owner.toString()).put("state", "CLOSED"), uid); } catch (Exception ignoredWrite) { }
                            // Only the service owner may terminate the service, never a task timeout.
                            System.exit(0);
                        }
                    }, "root-service-owner");
                    monitor.setDaemon(true);
                    request.put("state", "READY").put("socket", server.getLocalSocketAddress().getName())
                        .put("pid", Process.myPid()).put("rootStart", processStart(Process.myPid()))
                        .put("readyAtMs", SystemClock.elapsedRealtime()).put("ipcVersion", PioneerRootIpc.VERSION);
                    publish(descriptor, request, uid); monitor.start();
                    while (!closed.get()) {
                        LocalSocket peer = server.accept(); peers.add(peer);
                        try { input.execute(() -> {
                            String id = "";
                            try {
                                peer.setSoTimeout(3000);
                                Credentials credentials = peer.getPeerCredentials();
                                if (credentials.getUid() != uid || credentials.getPid() != owner.getInt("appPid")) throw new IOException("ROOT_IPC_PEER_OWNER");
                                requireOwner(owner);
                                JSONObject message = new PioneerRootIpc.Reader(PioneerRootIpc.MAX_REQUEST_BYTES).read(peer.getInputStream());
                                id = message.getString("id");
                                if (!id.matches("[0-9a-f-]{36}") || message.getInt("version") != PioneerRootIpc.VERSION ||
                                    !owner.getString("token").equals(message.getString("token"))) throw new IOException("ROOT_IPC_IDENTITY");
                                String operation = message.getString("operation"); JSONObject payload = message.getJSONObject("payload");
                                long deadline = message.getLong("deadlineMs"), received = SystemClock.elapsedRealtime();
                                if (deadline <= received || deadline - received > 180_000) throw new IOException("ROOT_IPC_DEADLINE");
                                for (Map.Entry<String, Task> entry : requests.entrySet())
                                    if (entry.getValue().deadline < received - 10_000) requests.remove(entry.getKey(), entry.getValue());
                                if (requests.size() >= 512) throw new IOException("ROOT_IPC_BUSY");
                                Task task = new Task(id, deadline, uid);
                                if (requests.putIfAbsent(id, task) != null) throw new IOException("ROOT_IPC_DUPLICATE");
                                ThreadPoolExecutor executor;
                                switch (operation) {
                                    case "HOTSPOT": case "HOTSPOT_WARM": executor = hotspot; break;
                                    case "TASK": executor = init; break;
                                    case "CALLBACK": executor = callbacks; break;
                                    case "APPLICATIONS": executor = applications; break;
                                    case "ADB": executor = tools; break;
                                    case "DIAGNOSTIC": executor = diagnostics; break;
                                    case "CANCEL": case "PING": case "HUD_START": case "HUD_LEASE": case "HUD_STATUS": case "HUD_RELEASE": executor = control; break;
                                    default: throw new IOException("ROOT_IPC_UNKNOWN_OPERATION");
                                }
                                executor.execute(() -> {
                                    task.begin(operation);
                                    currentTask.set(task);
                                    ScheduledFuture<?> expiry = deadlines.schedule(task::cancel,
                                        Math.max(0, task.deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                                    boolean started = false;
                                    try {
                                        if (!task.current(closed)) throw new IOException("ROOT_IPC_CANCELLED_OR_EXPIRED");
                                        requireOwner(owner);
                                        respond(peer, task.id, "accepted", true, new JSONObject(), null);
                                        if (!task.current(closed)) throw new IOException("ROOT_IPC_CANCELLED_OR_EXPIRED");
                                        JSONObject result;
                                        started = true;
                                        switch (operation) {
                                            case "PING": result = new JSONObject().put("pid", Process.myPid()); break;
                                            case "CANCEL": {
                                                Task target = requests.get(payload.getString("requestId"));
                                                if (target != null) target.cancel();
                                                result = new JSONObject().put("cancelled", target != null); break;
                                            }
                                            case "TASK": result = runTask(payload, owner, files, task, closed); break;
                                            case "HOTSPOT": result = PioneerRootHotspotService.execute(payload, owner, files, () -> {
                                                if (!task.current(closed)) return false;
                                                try { requireOwner(owner); return true; } catch (Exception ended) { return false; }
                                            }); break;
                                            case "HOTSPOT_WARM": result = PioneerRootHotspotService.warm(payload, owner, files); break;
                                            case "CALLBACK": result = PioneerRootHotspotService.callback(payload, owner, files); break;
                                            case "APPLICATIONS": result = PioneerRootApplications.handle(payload, owner); break;
                                            case "ADB": result = PioneerAdbHelper.runTask(payload.getBoolean("enabled")); break;
                                            case "DIAGNOSTIC": result = PioneerRootDiagnostics.run(payload.getString("name"), () -> task.current(closed)); break;
                                            default: result = hudService.handle(operation, payload); break;
                                        }
                                        String completion = PioneerRootIpc.resultCompletion(result);
                                        respond(peer, task.id, "result", completion.equals("COMPLETED"), result,
                                            completion.equals("COMPLETED") ? null : result.optString("error", "ROOT_HELPER_ERROR"), completion);
                                    } catch (Throwable failure) {
                                        try { respond(peer, task.id, "result", false, null, failure.toString(),
                                            PioneerRootIpc.failureCompletion(started, failure)); } catch (Exception ignoredWrite) { }
                                    }
                                    finally {
                                        task.finish(); expiry.cancel(false); currentTask.remove(); Thread.interrupted();
                                        peers.remove(peer); try { peer.close(); } catch (IOException ignoredClose) { }
                                    }
                                });
                            } catch (Throwable failure) {
                                try { respond(peer, id, "result", false, null, failure.toString(), "NOT_STARTED"); } catch (Exception ignoredWrite) { }
                                peers.remove(peer); try { peer.close(); } catch (IOException ignoredClose) { }
                            }
                        }); } catch (RejectedExecutionException busy) { peers.remove(peer); peer.close(); }
                    }
                }
            }
        } catch (Throwable error) {
            android.util.Log.e("DiPlayRoot", "Unified service ended", error);
            if (request != null && descriptor != null) try { publish(descriptor, request.put("state", "ERROR").put("error", error.toString()), request.getInt("uid")); } catch (Exception ignored) { }
        } finally {
            closed.set(true); if (hud != null) hud.close();
            for (ThreadPoolExecutor executor : new ThreadPoolExecutor[]{input, control, init, hotspot, applications, tools, callbacks, diagnostics}) executor.shutdownNow();
            for (LocalSocket peer : peers) try { peer.close(); } catch (IOException ignored) { }
            deadlines.shutdownNow();
        }
        System.exit(0);
    }
    static JSONObject validateTaskRequest(JSONObject payload, JSONObject owner, long now, long deadline) throws Exception {
        String kind = payload.getString("kind");
        if (!Arrays.asList("vpn-permission", "usb-permission-probe", "vpn-firewall").contains(kind))
            throw new IOException("ROOT_IPC_TASK_KIND");
        JSONObject request = new JSONObject(payload.getJSONObject("request").toString());
        int uid = owner.getInt("uid");
        for (String key : new String[]{"package", "apk", "boot", "appStart"})
            if (!owner.getString(key).equals(request.getString(key))) throw new IOException("ROOT_IPC_TASK_OWNER");
        if (request.getInt("uid") != uid || request.getInt("appPid") != owner.getInt("appPid"))
            throw new IOException("ROOT_IPC_TASK_OWNER");
        PioneerVpnPermissionHelper.requireLive(request, now);
        if (request.getLong("deadline") > deadline) throw new IOException("ROOT_IPC_TASK_DEADLINE");
        String token = request.getString("token");
        if (!(kind.equals("vpn-firewall") ? token.matches("[0-9a-f]{32}") :
                token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
            throw new IOException("ROOT_IPC_TASK_TOKEN");
        return request;
    }

    private static JSONObject runTask(JSONObject payload, JSONObject owner, File files, Task task, AtomicBoolean closed) throws Exception {
        String kind = payload.getString("kind");
        JSONObject request = validateTaskRequest(payload, owner, SystemClock.elapsedRealtime(), task.deadline);
        int uid = owner.getInt("uid");
        String token = request.getString("token");
        File directory = new File(new File(files, kind), token);
        PioneerVpnPermissionHelper.requireFile(Os.lstat(directory.getParent()), true, uid);
        PioneerVpnPermissionHelper.requireFile(Os.lstat(directory.getPath()), true, uid);
        if (!directory.equals(directory.getCanonicalFile())) throw new IOException("ROOT_IPC_TASK_PATH");
        // Claim once before executing. No accepted task may be replayed with another request ID.
        try (FileOutputStream claim = PioneerRootFiles.output(new File(directory, "submitted").getPath(),
                android.system.OsConstants.O_CREAT | android.system.OsConstants.O_EXCL |
                android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NOFOLLOW, 0600)) {
            claim.write(task.id.getBytes("US-ASCII")); claim.getFD().sync(); Os.fchown(claim.getFD(), uid, uid);
        }
        if (!task.current(closed)) throw new IOException("ROOT_IPC_TASK_CANCELLED");
        if (!kind.equals("usb-permission-probe")) {
            // Identity and rule metadata for recovery only, never a command source.
            File metadata = new File(directory, "recovery-owner.json");
            try (FileOutputStream out = PioneerRootFiles.output(metadata.getPath(),
                    android.system.OsConstants.O_CREAT | android.system.OsConstants.O_EXCL |
                    android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NOFOLLOW, 0600)) {
                out.write(request.toString().getBytes("UTF-8")); out.getFD().sync(); Os.fchown(out.getFD(), uid, uid);
            }
        }
        switch (kind) {
            case "vpn-permission": return PioneerVpnPermissionHelper.runTask(directory, request);
            case "usb-permission-probe": return PioneerUsbPermissionHelper.runTask(directory, request);
            default: return PioneerVpnFirewallHelper.runTask(directory, request);
        }
    }

    private static void respond(LocalSocket peer, String id, String type, boolean ok, JSONObject result, String error) throws Exception {
        respond(peer, id, type, ok, result, error, "COMPLETED");
    }
    private static void respond(LocalSocket peer, String id, String type, boolean ok, JSONObject result, String error, String completion) throws Exception {
        JSONObject message = new JSONObject().put("version", PioneerRootIpc.VERSION).put("id", id).put("type", type).put("ok", ok);
        message.put("completion", completion);
        if (result != null) message.put("result", result); if (error != null) message.put("error", error);
        ScheduledFuture<?> writeTimeout = deadlines.schedule(() -> { try { peer.close(); } catch (IOException ignored) { } },
            3000, TimeUnit.MILLISECONDS);
        try { PioneerRootIpc.write(peer.getOutputStream(), message, PioneerRootIpc.MAX_RESPONSE_BYTES); }
        finally { writeTimeout.cancel(false); }
    }
    static void requireOwner(JSONObject request) throws Exception {
        int pid = request.getInt("appPid");
        if (Os.stat("/proc/" + pid).st_uid != request.getInt("uid") || !processStart(pid).equals(request.getString("appStart")) ||
            !request.getString("boot").equals(PioneerVpnPermissionHelper.readFile(new File("/proc/sys/kernel/random/boot_id"), 128).trim()))
            throw new IOException("ROOT_SERVICE_OWNER_CHANGED");
    }
    private static String processStart(int pid) throws Exception {
        String stat = PioneerVpnPermissionHelper.readFile(new File("/proc/" + pid + "/stat"), 8192);
        String[] fields = stat.substring(stat.lastIndexOf(") ") + 2).split(" ");
        if (fields[0].equals("Z")) throw new IOException("ROOT_SERVICE_OWNER_ZOMBIE"); return fields[19];
    }
    private static void publish(File file, JSONObject data, int uid) throws Exception {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = PioneerRootFiles.output(temp.getPath(), android.system.OsConstants.O_CREAT | android.system.OsConstants.O_TRUNC | android.system.OsConstants.O_WRONLY | android.system.OsConstants.O_NOFOLLOW, 0600)) {
            out.write(data.toString().getBytes("UTF-8")); Os.fchown(out.getFD(), uid, uid);
        }
        Os.rename(temp.getPath(), file.getPath());
    }
}
