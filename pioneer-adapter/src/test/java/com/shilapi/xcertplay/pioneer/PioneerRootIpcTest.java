package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.net.SocketTimeoutException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 25, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PioneerRootIpcTest {
    private byte[] frame(JSONObject value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PioneerRootIpc.write(out, value, 4096); return out.toByteArray();
    }
    @Test public void separatesBackToBackUtf8Frames() throws Exception {
        JSONObject first = new JSONObject().put("name", "凯美瑞").put("version", 1);
        byte[] a = frame(first), b = frame(new JSONObject().put("ready", true));
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(a); out.write(b);
        ByteArrayInputStream input = new ByteArrayInputStream(out.toByteArray());
        PioneerRootIpc.Reader reader = new PioneerRootIpc.Reader(4096);
        assertEquals("凯美瑞", reader.read(input).getString("name"));
        assertTrue(reader.read(input).getBoolean("ready")); assertEquals(-1, input.read());
    }
    @Test public void retainsPartialFramesAcrossCancellationTimeoutChecks() throws Exception {
        byte[] wire = frame(new JSONObject().put("operation", "HUD_RELEASE"));
        InputStream input = new InputStream() {
            int offset;
            @Override public int read() { return offset < wire.length ? wire[offset++] & 255 : -1; }
            @Override public int read(byte[] target, int begin, int count) throws IOException {
                if (offset % 3 == 2) { int previous = offset; offset = -offset - 1; throw new SocketTimeoutException("tick " + previous); }
                if (offset < 0) offset = -offset - 1;
                if (offset == wire.length) return -1;
                target[begin] = wire[offset++]; return 1;
            }
        };
        PioneerRootIpc.Reader reader = new PioneerRootIpc.Reader(4096);
        JSONObject result = null;
        for (int n = 0; n < wire.length * 2 && result == null; n++) {
            try { result = reader.read(input); } catch (SocketTimeoutException expected) { }
        }
        assertNotNull(result); assertEquals("HUD_RELEASE", result.getString("operation"));
    }
    @Test public void preservesCompletionUncertaintyThroughWrappedFailures() {
        assertEquals("NOT_STARTED", PioneerRootIpc.failureCompletion(false, new IOException("rejected")));
        assertEquals("FAILED", PioneerRootIpc.failureCompletion(true, new IOException("finished with error")));
        assertEquals("UNKNOWN", PioneerRootIpc.failureCompletion(true,
            new RuntimeException(new PioneerRootIpc.CompletionUnknown("child still running"))));
    }
    @Test public void structuredHelperErrorsNeverBecomeSuccessfulCompletions() throws Exception {
        assertEquals("COMPLETED", PioneerRootIpc.resultCompletion(new JSONObject().put("code", "COMPLETED")));
        assertEquals("COMPLETED", PioneerRootIpc.resultCompletion(new JSONObject().put("state", "READY")));
        assertEquals("FAILED", PioneerRootIpc.resultCompletion(new JSONObject().put("code", "ERROR")));
        assertEquals("UNKNOWN", PioneerRootIpc.resultCompletion(new JSONObject().put("code", "ERROR")
            .put("mutationAttempted", true).put("childrenFinished", true)));
        assertEquals("UNKNOWN", PioneerRootIpc.resultCompletion(new JSONObject().put("code", "ERROR")
            .put("unresolvedChild", true)));
        assertEquals("UNKNOWN", PioneerRootIpc.resultCompletion(new JSONObject().put("state", "ERROR")));
        assertEquals("UNKNOWN", PioneerRootIpc.resultCompletion(new JSONObject().put("text", "failure\nRESULT: ERROR\n")));
        assertEquals("COMPLETED", PioneerRootIpc.resultCompletion(new JSONObject().put("text", "ERROR diagnostic only\nRESULT: OK\n")));
    }

    @Test public void rejectsOversizeTruncationAndInvalidJson() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> PioneerRootIpc.write(output, new JSONObject().put("text", "12345"), 2));
        assertEquals(0, output.size());
        for (byte[] header : new byte[][]{{0,0,0,0}, {(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff}, {0,1,0,0}})
            assertThrows(IOException.class, () -> new PioneerRootIpc.Reader(4096).read(new ByteArrayInputStream(header)));
        byte[] full = frame(new JSONObject().put("x", 1));
        assertThrows(EOFException.class, () -> new PioneerRootIpc.Reader(4096).read(new ByteArrayInputStream(full, 0, full.length - 1)));
        assertThrows(Exception.class, () -> new PioneerRootIpc.Reader(4096).read(new ByteArrayInputStream(new byte[]{0,0,0,1,'x'})));
    }
}
