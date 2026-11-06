package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PioneerCallbackLifecycleTest {
    private static long now() { return System.nanoTime()/1_000_000; }
    private static File result() throws Exception {
        File file = File.createTempFile("callback-lifecycle-", ".txt");
        Files.write(file.toPath(), "complete=1\n".getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private static final class Child extends java.lang.Process {
        volatile boolean alive = true;
        boolean terminate = true, force;
        int destroys, kills;
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { destroys++; if (terminate) alive = false; }
        @Override public java.lang.Process destroyForcibly() { kills++; if (force) alive = false; return this; }
    }

    @Test public void repeatedInterruptsCannotSkipReapAndRetirementAndFlagIsRestored() throws Exception {
        PioneerRootHotspotService.CallbackLane lane = new PioneerRootHotspotService.CallbackLane();
        Child child = new Child();
        File result = result();
        AtomicInteger interruptions = new AtomicInteger();
        PioneerRootCommand.Sleeper interrupted = millis -> {
            Thread.sleep(Math.min(2, millis));
            interruptions.incrementAndGet();
            throw new InterruptedException("repeated cancellation/deadline");
        };
        try {
            assertThrows(InterruptedException.class, () -> lane.run(() -> child, result,
                PioneerCallbackLifecycleTest::now, interrupted, 100, 20));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(child.alive);
            assertEquals(1, child.destroys);
            assertTrue(interruptions.get() > 2);
            assertFalse(result.exists());
            assertFalse(new File(result+".cancel").exists());
        } finally { Thread.interrupted(); }
        Child next = new Child(); next.alive = false;
        assertEquals("complete=1\n", lane.run(() -> next, result(),
            PioneerCallbackLifecycleTest::now, Thread::sleep, 100, 20));
    }

    @Test public void timeoutEscalatesToForceKillDespiteRepeatedInterrupts() throws Exception {
        Child child = new Child(); child.terminate = false; child.force = true;
        File result = result();
        long began = now();
        try {
            assertThrows(IllegalStateException.class, () -> new PioneerRootHotspotService.CallbackLane().run(
                () -> child, result, PioneerCallbackLifecycleTest::now, millis -> {
                    Thread.sleep(Math.min(2, millis));
                    throw new InterruptedException("cancel during reap");
                }, 0, 10));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(child.alive);
            assertEquals(1, child.destroys);
            assertEquals(1, child.kills);
            assertFalse(result.exists());
            assertTrue("cleanup exceeded bounded budget", now()-began < 3000);
        } finally { Thread.interrupted(); }
    }

    @Test public void unknownExitRetainsJournalAndPermanentlyRejectsAnotherLaunch() throws Exception {
        PioneerRootHotspotService.CallbackLane lane = new PioneerRootHotspotService.CallbackLane();
        Child child = new Child(); child.terminate = false;
        File result = result();
        try {
            assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> lane.run(() -> child, result,
                PioneerCallbackLifecycleTest::now, Thread::sleep, 0, 5));
            assertTrue(result.exists());
            assertTrue(new File(result+".cancel").exists());
            AtomicInteger launches = new AtomicInteger();
            assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> lane.run(() -> {
                launches.incrementAndGet(); return new Child();
            }, result, PioneerCallbackLifecycleTest::now, Thread::sleep, 0, 5));
            assertEquals(0, launches.get());
        } finally { child.alive = false; result.delete(); new File(result+".cancel").delete(); }
    }

    @Test public void failedCancellationJournalStillTerminatesChild() throws Exception {
        Child child = new Child(); File result = result();
        File cancel = new File(result+".cancel"); assertTrue(cancel.mkdir());
        assertThrows(IllegalStateException.class, () -> new PioneerRootHotspotService.CallbackLane().run(
            () -> child, result, PioneerCallbackLifecycleTest::now, Thread::sleep, 0, 5));
        assertFalse(child.alive);
        assertFalse(result.exists());
        assertFalse(cancel.exists());
    }

    @Test public void concurrentCallbackCannotLaunchWhilePreviousExitIsUnknown() throws Exception {
        PioneerRootHotspotService.CallbackLane lane = new PioneerRootHotspotService.CallbackLane();
        Child child = new Child(); child.terminate = false;
        File first = result(), second = result();
        CountDownLatch launched = new CountDownLatch(1);
        AtomicInteger nextLaunches = new AtomicInteger();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), secondFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                lane.run(() -> { launched.countDown(); return child; }, first,
                    PioneerCallbackLifecycleTest::now, Thread::sleep, 0, 10);
            } catch (Throwable error) { firstFailure.set(error); }
        });
        Thread next = new Thread(() -> {
            try {
                lane.run(() -> { nextLaunches.incrementAndGet(); return new Child(); }, second,
                    PioneerCallbackLifecycleTest::now, Thread::sleep, 0, 10);
            } catch (Throwable error) { secondFailure.set(error); }
        });
        worker.setDaemon(true); next.setDaemon(true);
        try {
            worker.start(); assertTrue(launched.await(2, TimeUnit.SECONDS)); next.start();
            worker.join(3000); next.join(3000);
            assertFalse(worker.isAlive()); assertFalse(next.isAlive());
            assertTrue(firstFailure.get() instanceof PioneerRootIpc.CompletionUnknown);
            assertTrue(secondFailure.get() instanceof PioneerRootIpc.CompletionUnknown);
            assertEquals(0, nextLaunches.get());
        } finally {
            child.alive = false; worker.interrupt(); next.interrupt();
            worker.join(1000); next.join(1000);
            first.delete(); second.delete(); new File(first+".cancel").delete();
        }
    }

    @Test public void cancellationBeforeLaunchDoesNotStartChildAndRestoresFlag() throws Exception {
        File result = result(); AtomicInteger launches = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> new PioneerRootHotspotService.CallbackLane().run(() -> {
                launches.incrementAndGet(); return new Child();
            }, result, PioneerCallbackLifecycleTest::now, Thread::sleep, 100, 5));
            assertEquals(0, launches.get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(result.exists());
        } finally { Thread.interrupted(); }
    }

    @Test public void realChildCanWriteBeyondPipeCapacityWithoutBlocking() throws Exception {
        File result = result();
        String text = new PioneerRootHotspotService.CallbackLane().run(() -> new ProcessBuilder(
            "/bin/sh", "-c", "dd if=/dev/zero bs=65536 count=32; printf 'complete=1\\n' > \"$1\"",
            "callback-test", result.getPath()).redirectErrorStream(true).start(), result,
            PioneerCallbackLifecycleTest::now, Thread::sleep, 3000, 100);
        assertEquals("complete=1\n", text);
        assertFalse(result.exists());
    }

    @Test public void realChildIsReapedUnderRepeatedCancellation() throws Exception {
        File result = result();
        AtomicReference<java.lang.Process> child = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean restored = new AtomicBoolean();
        CountDownLatch launched = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                new PioneerRootHotspotService.CallbackLane().run(() -> {
                    java.lang.Process process = new ProcessBuilder("/bin/sh", "-c", "exec sleep 30")
                        .redirectErrorStream(true).start();
                    child.set(process); launched.countDown(); return process;
                }, result, PioneerCallbackLifecycleTest::now, Thread::sleep, 3000, 100);
            } catch (Throwable error) { failure.set(error); }
            finally { restored.set(Thread.currentThread().isInterrupted()); }
        });
        worker.setDaemon(true);
        try {
            worker.start();
            assertTrue(launched.await(2, TimeUnit.SECONDS));
            for (int n = 0; n < 30 && worker.isAlive(); n++) {
                worker.interrupt(); Thread.sleep(3);
            }
            worker.join(3000);
            assertFalse("Cancellation cleanup did not finish", worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedException);
            assertTrue(restored.get());
            child.get().exitValue(); // Throws if the native child was merely abandoned.
            assertFalse(result.exists());
            assertFalse(new File(result+".cancel").exists());
        } finally {
            if (child.get() != null) child.get().destroyForcibly();
            worker.interrupt(); worker.join(1000);
            result.delete(); new File(result+".cancel").delete();
        }
    }
}
