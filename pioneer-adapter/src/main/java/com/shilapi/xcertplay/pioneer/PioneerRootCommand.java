package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded child execution: output and process completion must both be observed. */
final class PioneerRootCommand {
    interface Clock { long getAsLong(); }
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    static PioneerInterfaceFirewall.Result run(String[] args) throws Exception {
        return run(args, 2000);
    }
    static PioneerInterfaceFirewall.Result run(String[] args, long timeout) throws Exception {
        return run(new ProcessBuilder(args).redirectErrorStream(true).start(),
            () -> System.nanoTime() / 1_000_000L, Thread::sleep, timeout);
    }
    static PioneerInterfaceFirewall.Result run(Process child, Clock now, Sleeper sleep, long timeout) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicBoolean overflow = new AtomicBoolean(), readFailed = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try (InputStream in = child.getInputStream()) {
                byte[] chunk = new byte[1024]; int count;
                while ((count = in.read(chunk)) >= 0) synchronized (bytes) {
                    if (bytes.size() + count > 65536) overflow.set(true);
                    else bytes.write(chunk, 0, count);
                }
            } catch (IOException error) { readFailed.set(true); }
        }, "pioneer-root-command-output");
        reader.setDaemon(true);
        boolean observed = false;
        try {
            child.getOutputStream().close();
            reader.start();
            long deadline = now.getAsLong() + timeout;
            Integer exit = null;
            while (now.getAsLong() < deadline) {
                try { exit = child.exitValue(); break; }
                catch (IllegalThreadStateException running) { sleep.sleep(15); }
            }
            if (exit == null) throw new IOException("COMMAND_TIMEOUT");
            observed = true;
            reader.join(250);
            if (reader.isAlive() || overflow.get() || readFailed.get()) throw new IOException("COMMAND_OUTPUT_UNCONFIRMED");
            synchronized (bytes) { return new PioneerInterfaceFirewall.Result(exit, bytes.toString("UTF-8").trim()); }
        } catch (Throwable error) {
            if (!observed) {
                boolean stopped = stopChild(child, now, sleep);
                PioneerInterfaceFirewall.CompletionUnknown uncertain = new PioneerInterfaceFirewall.CompletionUnknown(stopped,
                    stopped ? "COMMAND_TIMEOUT_CHILD_EXITED" : "COMMAND_TIMEOUT_CHILD_UNCONFIRMED");
                uncertain.addSuppressed(error); throw uncertain;
            }
            if (error instanceof Exception) throw (Exception) error;
            throw (Error) error;
        } finally {
            // Streams are closed only after the exit decision; never mistake EOF for process exit.
            try { child.getInputStream().close(); } catch (IOException ignored) { }
            try { child.getErrorStream().close(); } catch (IOException ignored) { }
        }
    }
    static boolean stopChild(Process child, Clock now, Sleeper sleep) {
        if (exited(child)) return true;
        try { child.destroy(); } catch (Throwable error) { return false; }
        long deadline = now.getAsLong() + 500;
        while (now.getAsLong() < deadline) {
            if (exited(child)) return true;
            try { sleep.sleep(20); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        }
        return exited(child);
    }
    private static boolean exited(Process child) {
        try { child.exitValue(); return true; } catch (IllegalThreadStateException running) { return false; }
    }
}
