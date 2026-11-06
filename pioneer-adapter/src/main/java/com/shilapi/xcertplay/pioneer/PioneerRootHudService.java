package com.shilapi.xcertplay.pioneer;

import android.os.FileObserver;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Per authenticated root owner. START/STATUS/RELEASE take {}; LEASE takes active and issuedMs.
 * All responses are memory snapshots. Only a navigation-worker LEASE can extend the 20s deadline.
 * The host must close this service when its authenticated app owner ends.
 */
public final class PioneerRootHudService implements Closeable {
    static final long HEARTBEAT_MS = 5000, TIMEOUT_MS = 20000;
    private final JSONObject owner;
    private final File files;
    interface Supervisor { void run(JSONObject owner, File files, PioneerRootHudService host) throws Exception; }
    private final Supervisor runner;
    interface LeaseIo {
        void write(File temp, String data) throws Exception;
        void rename(File temp, File target) throws Exception;
    }
    private static final LeaseIo FILE_IO = new LeaseIo() {
        @Override public void write(File temp, String data) throws Exception {
            PioneerHudFilterHelper.write(temp, data, 0, 0644);
        }
        @Override public void rename(File temp, File target) throws Exception { Os.rename(temp.getPath(), target.getPath()); }
    };
    private final LeaseIo leaseIo;
    private final Thread leaseWriter;
    // One pending value, regardless of how many heartbeats arrive while IO is stalled.
    private LeaseUpdate pendingLease;
    private long runtimeGeneration, leaseRevision;
    private static final class LeaseUpdate {
        final File target;
        final String data;
        final long generation, revision;
        LeaseUpdate(File target, String data, long generation, long revision) {
            this.target = target; this.data = data; this.generation = generation; this.revision = revision;
        }
    }
    private final Object lock = new Object();
    private volatile boolean closed;
    private Thread supervisor;
    // Strong ownership prevents GC from closing the channel and dropping its OS lock. Kept even
    // after close(): the root process lifetime, not another HUD_START, ends an uncertain runtime.
    private PioneerHudFilterHelper.RuntimeOwnership quarantinedRuntime;
    private File control, status;
    private FileObserver observer;
    private String state = "NEW", error;
    private JSONObject feedback = new JSONObject();
    private boolean active, validFeedback;
    private long issuedMs = -1, deadline, retryAt;

    public PioneerRootHudService(JSONObject owner, File files) throws Exception {
        this(owner, files, PioneerHudFilterHelper::run);
    }

    PioneerRootHudService(JSONObject owner, File files, Supervisor runner) throws Exception {
        this(owner, files, runner, FILE_IO);
    }

    PioneerRootHudService(JSONObject owner, File files, Supervisor runner, LeaseIo leaseIo) throws Exception {
        this.owner = new JSONObject(owner.toString());
        if (this.owner.getString("token").isEmpty()) throw new IOException("HUD_FILTER_OWNER_TOKEN");
        // Separate from IPC authentication; stable through repeated START and native reinjection.
        this.owner.put("token", UUID.randomUUID().toString());
        this.files = files;
        this.runner = runner;
        this.leaseIo = leaseIo;
        // Host identity is authoritative; never take identity from operation payloads.
        leaseWriter = new Thread(this::writeLeases, "root-hud-lease-writer");
        leaseWriter.setDaemon(true); leaseWriter.start();
    }

    public JSONObject handle(String op, JSONObject payload) throws Exception {
        Thread candidate = null;
        if ("HUD_START".equals(op)) {
            candidate = new Thread(this::supervise, "root-hud-supervisor");
            candidate.setDaemon(true);
        }
        Thread launch = null;
        JSONObject response;
        long awaitRevision = -1, awaitDeadline = 0;
        synchronized (lock) {
            if (closed) throw new IOException("HUD_FILTER_CLOSED");
            switch (op) {
                case "HUD_START":
                    if (quarantinedRuntime == null && supervisor == null && SystemClock.elapsedRealtime() >= retryAt) {
                        invalidateRuntimeLocked();
                        state = "STARTING"; error = null;
                        supervisor = candidate; launch = candidate;
                    }
                    break;
                case "HUD_LEASE": {
                    if (quarantinedRuntime != null) throw new IOException("HUD_FILTER_RUNTIME_CLEANUP_UNCONFIRMED");
                    boolean requested = payload.getBoolean("active");
                    long issued = payload.getLong("issuedMs"), now = SystemClock.elapsedRealtime();
                    if (issued < 0 || issued > now || now - issued >= TIMEOUT_MS)
                        throw new IOException("HUD_FILTER_LEASE_EXPIRED");
                    // Reject replay/out-of-order renewals without extending an old lease.
                    if (issued <= issuedMs) throw new IOException("HUD_FILTER_LEASE_STALE");
                    issuedMs = issued; active = requested; deadline = requested ? issued + TIMEOUT_MS : 0;
                    scheduleLeaseLocked();
                    if (requested && control != null && "RUNNING".equals(state) && validFeedback &&
                            feedback.optBoolean("installed")) {
                        awaitRevision = leaseRevision; awaitDeadline = deadline;
                    }
                    break;
                }
                case "HUD_RELEASE":
                    active = false; deadline = 0; validFeedback = false;
                    issuedMs = SystemClock.elapsedRealtime(); scheduleLeaseLocked();
                    break;
                case "HUD_STATUS": break;
                default: throw new IOException("HUD_FILTER_UNKNOWN_OP " + op);
            }
            // Return a fresh native acknowledgement, not the previous heartbeat's deadline.
            // wait releases the monitor: disk publication, refresh and RELEASE remain independent.
            long waitUntilNs = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(600);
            while (awaitRevision >= 0 && !closed && active && leaseRevision == awaitRevision &&
                    !(validFeedback && feedback.optBoolean("installed") && feedback.optBoolean("active") &&
                        feedback.optLong("expiresMs") == awaitDeadline)) {
                long remainingNs = waitUntilNs - System.nanoTime();
                if (remainingNs <= 0) break;
                lock.wait(Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNs)));
            }
            response = snapshot();
        }
        if (launch != null) launch.start();
        return response;
    }

    private void invalidateRuntimeLocked() {
        runtimeGeneration++; leaseRevision++; pendingLease = null;
        control = null; status = null; feedback = new JSONObject(); validFeedback = false;
    }

    private void supervise() {
        try {
            if (!closed) runner.run(new JSONObject(owner.toString()), files, this);
        } catch (Exception failure) {
            if (!closed) failed(failure.toString());
        } finally {
            FileObserver old;
            synchronized (lock) {
                old = observer; observer = null;
                invalidateRuntimeLocked();
                supervisor = null;
                state = closed ? "CLOSED" : quarantinedRuntime != null ? "CLEANUP_UNCONFIRMED" : "ERROR";
                retryAt = SystemClock.elapsedRealtime() + 15000;
            }
            if (old != null) old.stopWatching();
        }
    }

    void cleanupUnconfirmed(PioneerHudFilterHelper.RuntimeOwnership runtime) {
        synchronized (lock) {
            quarantinedRuntime = runtime;
            active = false; deadline = 0;
            invalidateRuntimeLocked();
            error = "HUD_FILTER_RUNTIME_CLEANUP_UNCONFIRMED";
            state = closed ? "CLOSED" : "CLEANUP_UNCONFIRMED";
            lock.notifyAll();
        }
    }

    boolean stopped() { return closed; }

    void prepared(File control, File status) throws Exception {
        final long generation;
        synchronized (lock) {
            if (closed || quarantinedRuntime != null) throw new IOException("HUD_FILTER_CLOSED_OR_QUARANTINED");
            generation = runtimeGeneration;
        }
        // FileObserver construction/start/stop may call native code; never run them with lock held.
        FileObserver created = new FileObserver(status.getParent(), FileObserver.MOVED_TO | FileObserver.DELETE |
                FileObserver.DELETE_SELF | FileObserver.MOVE_SELF) {
            @Override public void onEvent(int event, String path) {
                if ("status.json".equals(path) || (event & (DELETE_SELF | MOVE_SELF)) != 0) refresh();
            }
        };
        created.startWatching();
        FileObserver old = null;
        boolean accepted = false;
        try {
            synchronized (lock) {
                if (closed || quarantinedRuntime != null || generation != runtimeGeneration) throw new IOException("HUD_FILTER_RUNTIME_CHANGED");
                old = observer; observer = created;
                runtimeGeneration++; // Invalidate any staged write or status read from earlier preparation.
                this.control = control; this.status = status;
                feedback = new JSONObject(); validFeedback = false;
                state = "RUNNING";
                scheduleLeaseLocked();
                accepted = true;
            }
        } finally {
            if (!accepted) created.stopWatching();
            if (old != null) old.stopWatching();
        }
        refresh();
    }

    /** Root↔agent adapter only. IO and process validation run outside the state monitor. */
    void refresh() {
        final File file;
        final long generation;
        synchronized (lock) {
            if (closed || status == null) return;
            file = status; generation = runtimeGeneration;
        }
        JSONObject value = new JSONObject();
        boolean valid = false;
        try {
            value = readAcknowledgement(file);
            valid = value.optString("token").equals(owner.getString("token")) &&
                value.optString("boot").equals(owner.getString("boot")) &&
                value.optInt("helperPid") == android.os.Process.myPid() &&
                value.optString("helperStart").equals(PioneerHudFilterHelper.start(android.os.Process.myPid())) &&
                PioneerHudFilterHelper.isTps(value.optInt("tpsPid"), value.optString("tpsStart"));
        } catch (Exception ignored) { }
        synchronized (lock) {
            if (closed || generation != runtimeGeneration || file != status) return;
            feedback = value; validFeedback = valid;
            lock.notifyAll();
            if (valid && value.optBoolean("installed")) error = null;
        }
    }

    JSONObject acknowledgement() throws Exception {
        File file;
        synchronized (lock) { file = status; }
        return file == null ? new JSONObject() : readAcknowledgement(file);
    }

    private JSONObject readAcknowledgement(File file) throws Exception {
        android.system.StructStat stat = Os.lstat(file.getPath());
        if (stat.st_uid != 1000 || !android.system.OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_nlink != 1 || (stat.st_mode & 0022) != 0)
            throw new IOException("HUD_FILTER_STATUS_OWNER");
        return new JSONObject(PioneerHudFilterHelper.read(file, 4096));
    }

    void failed(String message) { synchronized (lock) { error = message; validFeedback = false; } }

    /** Called with lock held: constructs a memory-only latest value, preserving navigation issue time. */
    private void scheduleLeaseLocked() throws Exception {
        leaseRevision++;
        if (control == null) { pendingLease = null; return; }
        JSONObject lease = new JSONObject().put("version", 1).put("token", owner.getString("token"))
            .put("boot", owner.getString("boot")).put("pid", owner.getInt("appPid"))
            .put("start", owner.getString("appStart")).put("active", active)
            .put("issuedMs", issuedMs).put("expiresMs", deadline);
        pendingLease = new LeaseUpdate(control, lease.toString(), runtimeGeneration, leaseRevision);
        lock.notifyAll();
    }

    private boolean currentLease(LeaseUpdate update) {
        synchronized (lock) {
            return !closed && update.generation == runtimeGeneration && update.revision == leaseRevision &&
                update.target == control;
        }
    }

    private void writeLeases() {
        while (true) {
            LeaseUpdate update;
            synchronized (lock) {
                while (!closed && pendingLease == null) {
                    try { lock.wait(); } catch (InterruptedException ignored) { }
                }
                if (closed) return;
                update = pendingLease; pendingLease = null;
            }
            // An obsolete service's blocked write must not corrupt a replacement service's stage file.
            File temp = new File(update.target.getPath() + ".tmp-" + owner.optString("token") + "-" +
                update.generation + "-" + update.revision);
            try {
                if (!currentLease(update)) continue;
                leaseIo.write(temp, update.data);
                // Release/restart/close can supersede a blocked stage write. Never commit that stale value.
                if (!currentLease(update)) continue;
                // A rename already in progress cannot be cancelled; the single writer publishes the latest
                // release next, and an active value always retains its original 20s expiry.
                leaseIo.rename(temp, update.target);
            } catch (Exception failure) {
                synchronized (lock) {
                    if (!closed && update.generation == runtimeGeneration && update.revision == leaseRevision) {
                        error = failure.toString(); validFeedback = false;
                    }
                }
            } finally {
                temp.delete(); // Best-effort cleanup, on the dedicated IO thread only.
            }
        }
    }

    private JSONObject snapshot() throws Exception {
        long now = SystemClock.elapsedRealtime(), health = feedback.optLong("healthMs");
        boolean ready = "RUNNING".equals(state) && active && now < deadline && validFeedback &&
            feedback.optBoolean("installed") && feedback.optBoolean("active") &&
            feedback.optLong("expiresMs") > now && feedback.optLong("expiresMs") <= deadline &&
            health <= now && now - health < TIMEOUT_MS;
        long readyUntil = ready ? Math.min(deadline, Math.min(feedback.optLong("expiresMs"), health + TIMEOUT_MS)) : 0;
        return new JSONObject().put("state", state).put("ready", ready).put("readyUntilElapsedMs", readyUntil).put("requested", active)
            .put("cleanupUnconfirmed", quarantinedRuntime != null).put("heartbeatMs", HEARTBEAT_MS).put("timeoutMs", TIMEOUT_MS).put("expiresAtElapsedMs", deadline)
            .put("error", error == null ? JSONObject.NULL : error).put("blockedFrames", feedback.optLong("blockedFrames"))
            .put("blockedExits", feedback.optLong("blockedExits")).put("tpsPid", feedback.optInt("tpsPid"));
    }

    @Override public void close() {
        Thread worker;
        FileObserver old;
        synchronized (lock) {
            if (closed) return;
            closed = true; active = false; deadline = 0; state = "CLOSED";
            invalidateRuntimeLocked();
            old = observer; observer = null;
            worker = supervisor;
            lock.notifyAll();
        }
        if (worker != null) worker.interrupt(); // Supervisor owns child reaping and releases its lock.
        if (old != null) {
            Thread cleanup = new Thread(old::stopWatching, "root-hud-observer-close");
            cleanup.setDaemon(true); cleanup.start();
        }
        // No IO, native observer calls, or joins on the root death-monitor/control thread.
    }
}
