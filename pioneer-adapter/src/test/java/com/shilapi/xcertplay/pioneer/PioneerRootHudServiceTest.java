package com.shilapi.xcertplay.pioneer;

import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PioneerRootHudServiceTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private JSONObject owner() throws Exception {
        return new JSONObject().put("token", "root-owner-token").put("uid", 10001)
            .put("appPid", 42).put("appStart", "100").put("boot", "boot");
    }
    private JSONObject lease(long now) throws Exception {
        return new JSONObject().put("active", true).put("issuedMs", now);
    }
    private void field(PioneerRootHudService service, String key, Object value) throws Exception {
        Field field = PioneerRootHudService.class.getDeclaredField(key);
        field.setAccessible(true); field.set(service, value);
    }

    private static final class BlockingLeaseIo implements PioneerRootHudService.LeaseIo {
        final CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1), stagedWrite = new CountDownLatch(1);
        final List<JSONObject> writes = Collections.synchronizedList(new ArrayList<>());
        final List<JSONObject> commits = Collections.synchronizedList(new ArrayList<>());
        String staged;
        @Override public void write(File temp, String data) throws Exception {
            writes.add(new JSONObject(data));
            if (writes.size() == 1) {
                entered.countDown();
                // Model filesystem IO that ignores interruption, including during close().
                boolean interrupted = false;
                while (true) {
                    try { unblock.await(); break; } catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            staged = data; stagedWrite.countDown();
        }
        @Override public void rename(File temp, File target) throws Exception {
            commits.add(new JSONObject(staged)); committed.countDown();
        }
    }

    private void awaitWriter(PioneerRootHudService service) throws Exception {
        Field f = PioneerRootHudService.class.getDeclaredField("leaseWriter"); f.setAccessible(true);
        Thread writer = (Thread) f.get(service);
        writer.join(2000);
        assertFalse("Lease writer did not stop", writer.isAlive());
    }

    private void awaitWriterWaiting(PioneerRootHudService service) throws Exception {
        Field f = PioneerRootHudService.class.getDeclaredField("leaseWriter"); f.setAccessible(true);
        Thread writer = (Thread) f.get(service);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (writer.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.yield();
        assertEquals(Thread.State.WAITING, writer.getState());
    }

    @Test public void blockedWriterCannotOccupyControlLanesAndReleaseDiscardsQueuedActiveLeases() throws Exception {
        BlockingLeaseIo io = new BlockingLeaseIo();
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, h) -> {}, io);
        ExecutorService controls = Executors.newFixedThreadPool(2);
        try {
            field(service, "control", new File("unused/lease.json"));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            assertTrue(io.entered.await(2, TimeUnit.SECONDS));
            // Both real root control slots must remain usable despite the blocked filesystem operation.
            controls.submit(() -> {
                for (int n = 0; n < 1000; n++) service.handle("HUD_STATUS", new JSONObject());
                return null;
            }).get(2, TimeUnit.SECONDS);
            controls.submit(() -> {
                for (int n = 0; n < 1000; n++) {
                    ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
                    service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
                }
                return service.handle("HUD_RELEASE", new JSONObject());
            }).get(2, TimeUnit.SECONDS);
            assertFalse(service.handle("HUD_STATUS", new JSONObject()).getBoolean("requested"));
            assertEquals(1, io.writes.size()); // No unbounded queue of heartbeat IO jobs.
            io.unblock.countDown();
            assertTrue(io.committed.await(2, TimeUnit.SECONDS));
            service.close(); awaitWriter(service);
            assertEquals(2, io.writes.size());
            assertEquals(1, io.commits.size());
            assertFalse(io.commits.get(0).getBoolean("active"));
            assertEquals(0, io.commits.get(0).getLong("expiresMs"));
        } finally { io.unblock.countDown(); service.close(); controls.shutdownNow(); }
    }

    @Test public void closeReturnsWithUninterruptibleWriterAndDropsStagedAndQueuedWork() throws Exception {
        BlockingLeaseIo io = new BlockingLeaseIo();
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, h) -> {}, io);
        ExecutorService monitor = Executors.newSingleThreadExecutor();
        try {
            field(service, "control", new File("unused/lease.json"));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            assertTrue(io.entered.await(2, TimeUnit.SECONDS));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            monitor.submit(service::close).get(2, TimeUnit.SECONDS);
            assertEquals(1, io.unblock.getCount()); // close did not rely on IO returning.
            io.unblock.countDown(); awaitWriter(service);
            assertEquals(1, io.writes.size());
            assertTrue(io.commits.isEmpty());
        } finally { io.unblock.countDown(); service.close(); monitor.shutdownNow(); }
    }

    @Test public void blockedRenameDoesNotHoldControlOrCloseAndCannotDrainAnOldHeartbeatQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger(), renames = new AtomicInteger();
        PioneerRootHudService.LeaseIo io = new PioneerRootHudService.LeaseIo() {
            @Override public void write(File temp, String data) { writes.incrementAndGet(); }
            @Override public void rename(File temp, File target) throws Exception {
                entered.countDown(); unblock.await(); renames.incrementAndGet();
            }
        };
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, h) -> {}, io);
        ExecutorService controls = Executors.newFixedThreadPool(2);
        try {
            field(service, "control", new File("unused/lease.json"));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            controls.submit(() -> service.handle("HUD_STATUS", new JSONObject())).get(2, TimeUnit.SECONDS);
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
            controls.submit(() -> service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()))).get(2, TimeUnit.SECONDS);
            controls.submit(() -> service.handle("HUD_RELEASE", new JSONObject())).get(2, TimeUnit.SECONDS);
            controls.submit(service::close).get(2, TimeUnit.SECONDS);
            assertEquals(1, unblock.getCount());
            unblock.countDown(); awaitWriter(service);
            // The kernel rename already in progress may finish, but no queued active lease follows it.
            assertEquals(1, writes.get()); assertEquals(1, renames.get());
        } finally { unblock.countDown(); service.close(); controls.shutdownNow(); }
    }

    @Test public void heartbeatBurstCoalescesToLatestOriginalNavigationTimestamp() throws Exception {
        BlockingLeaseIo io = new BlockingLeaseIo();
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, h) -> {}, io);
        try {
            field(service, "control", new File("unused/lease.json"));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            assertTrue(io.entered.await(2, TimeUnit.SECONDS));
            for (int n = 0; n < 1000; n++) {
                ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
                service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            }
            long issued = SystemClock.elapsedRealtime();
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5000));
            io.unblock.countDown();
            assertTrue(io.committed.await(2, TimeUnit.SECONDS));
            service.close(); awaitWriter(service);
            assertEquals(2, io.writes.size()); assertEquals(1, io.commits.size());
            assertEquals(issued, io.commits.get(0).getLong("issuedMs"));
            assertEquals(issued + 20000, io.commits.get(0).getLong("expiresMs"));
        } finally { io.unblock.countDown(); service.close(); }
    }

    @Test public void preparationSchedulesLeaseWithoutRenewingItAtIoTime() throws Exception {
        BlockingLeaseIo io = new BlockingLeaseIo(); io.unblock.countDown();
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, h) -> {}, io);
        try {
            long issued = SystemClock.elapsedRealtime();
            service.handle("HUD_LEASE", lease(issued));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5000));
            File area = directory.newFolder();
            service.prepared(new File(area, "lease.json"), new File(area, "status.json"));
            assertTrue(io.committed.await(2, TimeUnit.SECONDS));
            assertEquals(issued, io.commits.get(0).getLong("issuedMs"));
            assertEquals(issued + 20000, io.commits.get(0).getLong("expiresMs"));
        } finally { service.close(); awaitWriter(service); }
    }

    @Test public void supervisorRestartInvalidatesBlockedAndQueuedWritesBeforePreparingAgain() throws Exception {
        BlockingLeaseIo io = new BlockingLeaseIo();
        CountDownLatch entered = new CountDownLatch(1), endFirst = new CountDownLatch(1);
        CountDownLatch restarted = new CountDownLatch(1), prepareAgain = new CountDownLatch(1);
        CountDownLatch prepared = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, host) -> {
            if (starts.incrementAndGet() == 1) { entered.countDown(); endFirst.await(); }
            else {
                restarted.countDown(); prepareAgain.await();
                File area = directory.newFolder();
                host.prepared(new File(area, "lease.json"), new File(area, "status.json"));
                prepared.countDown(); new CountDownLatch(1).await();
            }
        }, io);
        try {
            service.handle("HUD_START", new JSONObject()); assertTrue(entered.await(2, TimeUnit.SECONDS));
            field(service, "control", new File("unused/lease.json"));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            assertTrue(io.entered.await(2, TimeUnit.SECONDS));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            endFirst.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!service.handle("HUD_STATUS", new JSONObject()).getString("state").equals("ERROR") &&
                    System.nanoTime() < until) Thread.yield();
            assertEquals("ERROR", service.handle("HUD_STATUS", new JSONObject()).getString("state"));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(15000));
            service.handle("HUD_START", new JSONObject()); assertTrue(restarted.await(2, TimeUnit.SECONDS));
            // Before any release/new preparation can supersede the old values, restart itself must drop them.
            io.unblock.countDown(); assertTrue(io.stagedWrite.await(2, TimeUnit.SECONDS));
            awaitWriterWaiting(service);
            assertEquals(1, io.writes.size()); assertTrue(io.commits.isEmpty());
            service.handle("HUD_RELEASE", new JSONObject());
            prepareAgain.countDown(); assertTrue(prepared.await(2, TimeUnit.SECONDS));
            assertTrue(io.committed.await(2, TimeUnit.SECONDS));
            service.close(); awaitWriter(service);
            assertEquals(2, io.writes.size()); assertEquals(1, io.commits.size());
            assertFalse(io.commits.get(0).getBoolean("active"));
        } finally { endFirst.countDown(); prepareAgain.countDown(); io.unblock.countDown(); service.close(); }
    }

    @Test public void coldStartDoesNotBlockControlAndRepeatedStartAttaches() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, host) -> {
            assertNotEquals("root-owner-token", o.getString("token"));
            starts.incrementAndGet(); entered.countDown();
            try { new CountDownLatch(1).await(); } finally { stopped.countDown(); }
        })) {
            assertEquals("STARTING", service.handle("HUD_START", new JSONObject()).getString("state"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int n = 0; n < 100; n++) service.handle("HUD_START", new JSONObject());
            assertEquals(1, starts.get());
            long now = SystemClock.elapsedRealtime();
            JSONObject response = service.handle("HUD_LEASE", lease(now));
            assertEquals(now + 20000, response.getLong("expiresAtElapsedMs"));
            assertFalse(response.getBoolean("ready"));
            service.handle("HUD_RELEASE", new JSONObject());
            assertEquals(0, service.handle("HUD_STATUS", new JSONObject()).getLong("expiresAtElapsedMs"));
        }
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test public void snapshotsNeverRenewAndExpiryIsExactEvenWithCachedAcknowledgement() throws Exception {
        try (PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"))) {
            long now = SystemClock.elapsedRealtime();
            service.handle("HUD_LEASE", lease(now));
            field(service, "state", "RUNNING"); field(service, "validFeedback", true);
            field(service, "feedback", new JSONObject().put("installed", true).put("active", true)
                .put("healthMs", now).put("expiresMs", now + 20000).put("blockedFrames", 7));
            for (int n = 0; n < 1000; n++) assertTrue(service.handle("HUD_STATUS", new JSONObject()).getBoolean("ready"));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(19999));
            assertTrue(service.handle("HUD_STATUS", new JSONObject()).getBoolean("ready"));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1));
            JSONObject expired = service.handle("HUD_STATUS", new JSONObject());
            assertFalse(expired.getBoolean("ready"));
            assertEquals(now + 20000, expired.getLong("expiresAtElapsedMs"));
            assertEquals(7, expired.getInt("blockedFrames"));
        }
    }

    @Test public void aRenewalDoesNotStretchAnOlderAgentAcknowledgement() throws Exception {
        try (PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"))) {
            long now = SystemClock.elapsedRealtime();
            service.handle("HUD_LEASE", lease(now));
            field(service, "state", "RUNNING"); field(service, "validFeedback", true);
            field(service, "feedback", new JSONObject().put("installed", true).put("active", true)
                .put("healthMs", now).put("expiresMs", now + 20000));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5000));
            JSONObject renewed = service.handle("HUD_LEASE", lease(now + 5000));
            assertTrue(renewed.getBoolean("ready"));
            assertEquals(now + 25000, renewed.getLong("expiresAtElapsedMs"));
            assertEquals(now + 20000, renewed.getLong("readyUntilElapsedMs"));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(15000));
            assertFalse(service.handle("HUD_STATUS", new JSONObject()).getBoolean("ready"));
        }
    }

    @Test public void staleFutureAndExpiredLeasesCannotExtendDeadline() throws Exception {
        try (PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"))) {
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(20000));
            long now = SystemClock.elapsedRealtime();
            service.handle("HUD_LEASE", lease(now));
            for (long bad : new long[] { -1, now + 1, now - 20000, now - 1, now }) {
                try { service.handle("HUD_LEASE", lease(bad)); fail("Accepted timestamp " + bad); }
                catch (IOException expected) { }
            }
            assertEquals(now + 20000, service.handle("HUD_STATUS", new JSONObject()).getLong("expiresAtElapsedMs"));
            service.handle("HUD_RELEASE", new JSONObject());
            assertFalse(service.handle("HUD_STATUS", new JSONObject()).getBoolean("requested"));
        }
    }

    @Test public void failedStartCanRetryAndCloseRejectsFurtherOperations() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<String> token = new AtomicReference<>();
        CountDownLatch retried = new CountDownLatch(1);
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, host) -> {
            if (starts.incrementAndGet() == 1) token.set(o.getString("token"));
            else { assertEquals(token.get(), o.getString("token")); retried.countDown(); }
            throw new IOException("firmware mismatch");
        });
        service.handle("HUD_START", new JSONObject());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!service.handle("HUD_STATUS", new JSONObject()).getString("state").equals("ERROR") && System.nanoTime() < until)
            Thread.yield();
        assertEquals("ERROR", service.handle("HUD_STATUS", new JSONObject()).getString("state"));
        service.handle("HUD_START", new JSONObject()); assertEquals(1, starts.get());
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(15000));
        service.handle("HUD_START", new JSONObject());
        assertTrue(retried.await(2, TimeUnit.SECONDS));
        assertEquals(2, starts.get());
        service.close(); service.close();
        try { service.handle("HUD_STATUS", new JSONObject()); fail("Closed service accepted op"); }
        catch (IOException expected) { }
    }
    @Test public void uncertainRuntimeBlocksStartAndRenewalAfterSupervisorEndsButNotStatusReleaseOrClose() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        PioneerHudFilterHelper.RuntimeOwnership runtime = new PioneerHudFilterHelper.RuntimeOwnership();
        CountDownLatch quarantined = new CountDownLatch(1);
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"), (o, f, host) -> {
            starts.incrementAndGet();
            host.cleanupUnconfirmed(runtime);
            quarantined.countDown();
        });
        try {
            service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime()));
            service.handle("HUD_START", new JSONObject());
            assertTrue(quarantined.await(2, TimeUnit.SECONDS));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            Field supervisor = PioneerRootHudService.class.getDeclaredField("supervisor");
            supervisor.setAccessible(true);
            while (supervisor.get(service) != null && System.nanoTime() < until) Thread.yield();
            assertNull(supervisor.get(service));
            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5));
            for (int n = 0; n < 100; n++) {
                JSONObject response = service.handle("HUD_START", new JSONObject());
                assertEquals("CLEANUP_UNCONFIRMED", response.getString("state"));
                assertTrue(response.getBoolean("cleanupUnconfirmed"));
                assertFalse(response.getBoolean("ready"));
                assertFalse(response.getBoolean("requested"));
                assertEquals(0, response.getLong("expiresAtElapsedMs"));
                service.handle("HUD_STATUS", new JSONObject());
                service.handle("HUD_RELEASE", new JSONObject());
            }
            assertEquals(1, starts.get());
            assertThrows(IOException.class, () -> service.handle("HUD_LEASE", lease(SystemClock.elapsedRealtime())));
            assertThrows(IOException.class, () -> service.prepared(new File("unused/lease.json"), new File("unused/status.json")));
            service.close(); awaitWriter(service);
            Field retained = PioneerRootHudService.class.getDeclaredField("quarantinedRuntime");
            retained.setAccessible(true);
            assertSame(runtime, retained.get(service)); // close must not relinquish uncertain native ownership.
        } finally { service.close(); }
    }

    @Test public void quarantineAfterCloseStillRetainsNativeOwnership() throws Exception {
        PioneerRootHudService service = new PioneerRootHudService(owner(), new File("unused"));
        service.close();
        PioneerHudFilterHelper.RuntimeOwnership runtime = new PioneerHudFilterHelper.RuntimeOwnership();
        service.cleanupUnconfirmed(runtime);
        Field retained = PioneerRootHudService.class.getDeclaredField("quarantinedRuntime");
        retained.setAccessible(true);
        assertSame(runtime, retained.get(service));
        awaitWriter(service);
    }

}
