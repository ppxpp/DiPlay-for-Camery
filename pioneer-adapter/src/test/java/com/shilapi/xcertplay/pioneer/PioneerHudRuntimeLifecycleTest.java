package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real file locks and process/stream failures, without Android services or a vehicle. */
public class PioneerHudRuntimeLifecycleTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    private static class Child extends java.lang.Process {
        volatile boolean alive = true;
        boolean terminate = true, force;
        int destroys, kills;
        final InputStream output;
        Child() { this(new ByteArrayInputStream(new byte[0])); }
        Child(InputStream output) { this.output = output; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() { throw new AssertionError("Unbounded waitFor forbidden"); }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroys++; if (terminate) alive = false; }
        @Override public java.lang.Process destroyForcibly() { kills++; if (force) alive = false; return this; }
    }

    private PioneerHudFilterHelper.RuntimeOwnership own(File file) throws Exception {
        PioneerHudFilterHelper.RuntimeOwnership runtime = new PioneerHudFilterHelper.RuntimeOwnership();
        runtime.lockStream = new FileOutputStream(file);
        runtime.lock = runtime.lockStream.getChannel().lock();
        return runtime;
    }
    private void assertHeld(File file) throws Exception {
        try (FileOutputStream contender = new FileOutputStream(file, true)) {
            assertThrows(OverlappingFileLockException.class, () -> contender.getChannel().tryLock());
        }
    }
    private void releaseForTest(PioneerHudFilterHelper.RuntimeOwnership runtime) throws Exception {
        if (runtime.lock != null && runtime.lock.isValid()) runtime.lock.release();
        if (runtime.lockStream != null) runtime.lockStream.close();
    }

    @Test public void confirmedExitAndDrainPermitNextChildBeforeReleasingOwnerLock() throws Exception {
        File file = directory.newFile();
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(file);
        try {
            Child first = new Child(); runtime.attach(first);
            assertTrue(runtime.stopChild());
            assertFalse(first.alive); assertNull(runtime.child); assertNull(runtime.drain);
            assertHeld(file); // Reinjection within this owner retains the same OS lock.
            Child next = new Child(); runtime.attach(next);
            assertTrue(runtime.closeConfirmed());
            assertFalse(next.alive);
            try (FileOutputStream contender = new FileOutputStream(file, true)) {
                java.nio.channels.FileLock acquired = contender.getChannel().tryLock();
                assertNotNull(acquired); acquired.release();
            }
        } finally { releaseForTest(runtime); }
    }

    @Test public void unknownExitRetainsLockAndChildAndRejectsReplacementEvenAfterLateExit() throws Exception {
        File file = directory.newFile();
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(file);
        Child child = new Child(); child.terminate = false;
        try {
            runtime.attach(child);
            long began = System.nanoTime();
            assertFalse(runtime.closeConfirmed());
            assertTrue("cleanup must remain bounded", System.nanoTime() - began < TimeUnit.SECONDS.toNanos(4));
            assertSame(child, runtime.child); assertHeld(file);
            assertEquals(1, child.destroys); assertEquals(1, child.kills);
            assertThrows(IOException.class, () -> runtime.attach(new Child()));
            child.alive = false;
            assertFalse(runtime.closeConfirmed()); // No automatic retry after ownership uncertainty.
            assertHeld(file);
        } finally { child.alive = false; releaseForTest(runtime); }
    }

    @Test public void exitedChildWithBlockedDrainRetainsOwnershipWithoutBlockingStreamClose() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), unblock = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean();
        InputStream blocked = new InputStream() {
            @Override public int read() {
                reading.countDown();
                while (true) {
                    try { unblock.await(); return -1; } catch (InterruptedException ignored) { }
                }
            }
            @Override public void close() { streamClosed.set(true); }
        };
        File file = directory.newFile();
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(file);
        Child child = new Child(blocked);
        try {
            runtime.attach(child); assertTrue(reading.await(2, TimeUnit.SECONDS));
            long began = System.nanoTime();
            assertFalse(runtime.closeConfirmed());
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2));
            assertFalse(child.alive); assertFalse(streamClosed.get());
            assertTrue(runtime.drain.isAlive()); assertHeld(file);
            assertThrows(IOException.class, () -> runtime.attach(new Child()));
        } finally {
            unblock.countDown();
            if (runtime.drain != null) runtime.drain.join(2000);
            releaseForTest(runtime);
        }
        assertTrue(streamClosed.get());
    }

    @Test public void repeatedInterruptsCannotSkipForceKillAndCleanupRestoresFlag() throws Exception {
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(directory.newFile());
        Child child = new Child(); child.terminate = false; child.force = true;
        AtomicBoolean result = new AtomicBoolean(), restored = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            entered.countDown(); result.set(runtime.closeConfirmed());
            restored.set(Thread.currentThread().isInterrupted());
        });
        try {
            runtime.attach(child); worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int n = 0; n < 100 && worker.isAlive(); n++) { worker.interrupt(); Thread.sleep(5); }
            worker.join(3000);
            assertFalse(worker.isAlive()); assertTrue(result.get()); assertTrue(restored.get());
            assertFalse(child.alive); assertEquals(1, child.destroys); assertEquals(1, child.kills);
        } finally { child.alive = false; worker.interrupt(); worker.join(2000); releaseForTest(runtime); }
    }

    @Test public void failedStreamSetupStillOwnsAndReapsLaunchedChild() throws Exception {
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(directory.newFile());
        Child child = new Child() {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) { }
                    @Override public void close() throws IOException { throw new IOException("stdin close failed"); }
                };
            }
        };
        try {
            assertThrows(IOException.class, () -> runtime.attach(child));
            assertSame(child, runtime.child);
            assertTrue(runtime.closeConfirmed()); assertFalse(child.alive);
        } finally { child.alive = false; releaseForTest(runtime); }
    }

    @Test public void realNativeChildIsReapedAndOutputDrainStops() throws Exception {
        PioneerHudFilterHelper.RuntimeOwnership runtime = own(directory.newFile());
        java.lang.Process child = new ProcessBuilder("/bin/sh", "-c", "exec sleep 30").redirectErrorStream(true).start();
        try {
            runtime.attach(child);
            Thread drain = runtime.drain;
            assertTrue(runtime.closeConfirmed());
            child.exitValue(); // EOF or destroy() alone must not count as successful teardown.
            assertFalse(drain.isAlive());
        } finally { child.destroyForcibly(); releaseForTest(runtime); }
    }
}
