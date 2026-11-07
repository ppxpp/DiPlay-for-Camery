package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.json.JSONObject;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PioneerRootDiagnosticsTest {
    private static long now() { return System.nanoTime()/1_000_000; }
    private static class Child extends Process {
        boolean alive = true, terminate = true, force;
        int destroys, kills;
        InputStream input = new ByteArrayInputStream(new byte[0]);
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public int waitFor() { alive = false; return 0; }
        public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { destroys++; if (terminate) alive = false; }
        public Process destroyForcibly() { kills++; if (force) alive = false; return this; }
    }
    private JSONObject run(PioneerRootDiagnostics.Lane lane, Child child) throws Exception {
        return lane.run("test", () -> child, () -> true, PioneerRootDiagnosticsTest::now, Thread::sleep, 0, 5);
    }
    @Test public void kernelUsbFilterKeepsResetDisconnectAndControllerErrorsOnly() {
        String text = "[1.0] usb 1-1: reset high-speed USB device\n" +
            "[2.0] ci_hdrc 2184000.usb: timeout\n" +
            "[3.0] usb 1-1: USB disconnect, device number 3\n" +
            "[4.0] wlan0: unrelated network event\n" +
            "[5.0] usb 1-1: SerialNumber: PRIVATE-SERIAL\n";
        String filtered = PioneerRootDiagnostics.kernelUsbLines(text);
        assertTrue(filtered.contains("reset high-speed"));
        assertTrue(filtered.contains("ci_hdrc"));
        assertTrue(filtered.contains("USB disconnect"));
        assertFalse(filtered.contains("wlan0"));
        assertFalse(filtered.contains("PRIVATE-SERIAL"));
    }

    @Test public void deniedKernelReadPreservesCommandFailureInsteadOfReportingEmptyHistory() throws Exception {
        Child child = new Child() { public int exitValue() { return 1; } };
        child.alive = false;
        child.input = new ByteArrayInputStream("dmesg: Permission denied\n".getBytes("UTF-8"));
        JSONObject result = new PioneerRootDiagnostics.Lane().run("kernel_usb", () -> child, () -> true,
            PioneerRootDiagnosticsTest::now, Thread::sleep, 100, 5);
        assertEquals("command_failed", result.getString("status"));
        assertTrue(result.getString("text").contains("Permission denied"));
    }

    @Test public void timeoutConfirmsTerminationBeforeReuse() throws Exception {
        PioneerRootDiagnostics.Lane lane = new PioneerRootDiagnostics.Lane();
        Child child = new Child();
        assertEquals("timeout", run(lane, child).getString("status"));
        assertFalse(child.alive); assertEquals(1, child.destroys);
        Child next = new Child(); next.alive = false;
        assertEquals("empty", run(lane, next).getString("status"));
    }
    @Test public void ignoredTerminationEscalatesAndUnconfirmedExitFencesLaunches() throws Exception {
        Child killed = new Child(); killed.terminate = false; killed.force = true;
        run(new PioneerRootDiagnostics.Lane(), killed);
        assertFalse(killed.alive); assertEquals(1, killed.kills);
        PioneerRootDiagnostics.Lane lane = new PioneerRootDiagnostics.Lane();
        Child stuck = new Child(); stuck.terminate = false;
        assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> run(lane, stuck));
        AtomicInteger starts = new AtomicInteger();
        assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> lane.run("test", () -> {
            starts.incrementAndGet(); return new Child();
        }, () -> true, PioneerRootDiagnosticsTest::now, Thread::sleep, 0, 5));
        assertEquals(0, starts.get());
    }
    @Test public void unfinishedReaderAlsoFencesTheLane() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Child child = new Child(); child.alive = false;
        child.input = new InputStream() {
            public int read() {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
                return -1;
            }
        };
        PioneerRootDiagnostics.Lane lane = new PioneerRootDiagnostics.Lane();
        try {
            assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> run(lane, child));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(PioneerRootIpc.CompletionUnknown.class, () -> run(lane, new Child()));
        } finally { release.countDown(); }
    }
    @Test public void cancellationNeverLaunchesAndInterruptionCannotSkipCleanup() throws Exception {
        PioneerRootDiagnostics.Lane lane = new PioneerRootDiagnostics.Lane();
        AtomicInteger starts = new AtomicInteger();
        assertThrows(IOException.class, () -> lane.run("test", () -> { starts.incrementAndGet(); return new Child(); },
            () -> false, PioneerRootDiagnosticsTest::now, Thread::sleep, 0, 5));
        assertEquals(0, starts.get());
        Child child = new Child();
        try {
            assertThrows(InterruptedException.class, () -> lane.run("test", () -> child, () -> true,
                PioneerRootDiagnosticsTest::now, ms -> { throw new InterruptedException(); }, 100, 5));
            assertFalse(child.alive); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test public void outputIsBoundedEvenAfterJsonEscaping() throws Exception {
        Child child = new Child(); child.alive = false;
        child.input = new ByteArrayInputStream(new byte[600 * 1024]);
        JSONObject result = run(new PioneerRootDiagnostics.Lane(), child);
        assertEquals("truncated", result.getString("status"));
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        PioneerRootIpc.write(frame, result, PioneerRootIpc.MAX_RESPONSE_BYTES);
        assertTrue(frame.size() < PioneerRootIpc.MAX_RESPONSE_BYTES);
    }
}
