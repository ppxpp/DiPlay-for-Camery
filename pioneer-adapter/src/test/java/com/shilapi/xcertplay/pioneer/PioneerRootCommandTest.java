package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class PioneerRootCommandTest {
    static class Child extends Process {
        boolean alive, stops = true; byte[] output = "ok".getBytes();
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        Child(boolean alive) { this.alive = alive; }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(output); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public void destroy() { if (stops) alive = false; }
    }
    @Test public void knownExitIncludesCompleteOutput() throws Exception {
        AtomicLong clock = new AtomicLong();
        assertEquals("ok", PioneerRootCommand.run(new Child(false), clock::get, clock::addAndGet, 100).output);
    }
    @Test public void timeoutWithStoppedChildIsDifferentFromUnconfirmedChild() throws Exception {
        for (boolean stops : new boolean[]{true, false}) {
            Child child = new Child(true); child.stops = stops; AtomicLong clock = new AtomicLong();
            PioneerInterfaceFirewall.CompletionUnknown error = assertThrows(PioneerInterfaceFirewall.CompletionUnknown.class,
                () -> PioneerRootCommand.run(child, clock::get, clock::addAndGet, 100));
            assertEquals(stops, error.childStopped);
            assertTrue(clock.get() <= 650);
        }
    }
    @Test public void truncatedOutputCannotBeAcceptedAsValidFirewallState() {
        Child child = new Child(false); child.output = new byte[65537]; AtomicLong clock = new AtomicLong();
        assertThrows(IOException.class, () -> PioneerRootCommand.run(child, clock::get, clock::addAndGet, 100));
    }
}
