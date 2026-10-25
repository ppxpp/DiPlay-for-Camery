package com.shilapi.xcertplay.pioneer;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class PioneerRootTaskProtocolTest {
    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void taskRoundTripsUtf8AndConsumesOnlyOneLine() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String path = "/data/用户/0123456789abcdef0123456789abcdef";
        PioneerRootTaskProtocol.submitTask(output, path);
        assertEquals(path, PioneerRootTaskProtocol.readTask(new ByteArrayInputStream(output.toByteArray())));
        ByteArrayInputStream lines = input("first\nsecond\n");
        assertEquals("first", PioneerRootTaskProtocol.readTask(lines));
        assertEquals("second", PioneerRootTaskProtocol.readTask(lines));
    }

    @Test public void acceptsExactByteLimitAndRejectsOversizeBeforeWriting() throws Exception {
        String maximum = new String(new char[1024]).replace('\0', 'a');
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PioneerRootTaskProtocol.submitTask(output, maximum);
        assertEquals(maximum, PioneerRootTaskProtocol.readTask(new ByteArrayInputStream(output.toByteArray())));
        output.reset();
        assertThrows(IOException.class, () -> PioneerRootTaskProtocol.submitTask(output, maximum + "a"));
        assertEquals(0, output.size());
        assertThrows(IOException.class, () -> PioneerRootTaskProtocol.readTask(input(maximum + "a\n")));
        assertThrows(IOException.class, () -> PioneerRootTaskProtocol.submitTask(output, new String(new char[400]).replace("\0", "车")));
    }

    @Test public void rejectsTruncatedEmptyAndInjectedMessages() {
        for (String value : new String[]{"", "task", "\n", "a\rb\n", "a\0b\n"})
            assertThrows(value, IOException.class, () -> PioneerRootTaskProtocol.readTask(input(value)));
        for (String value : new String[]{"", "a\nb", "a\rb", "a\0b"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThrows(IOException.class, () -> PioneerRootTaskProtocol.submitTask(output, value));
            assertEquals(0, output.size());
        }
    }

    @Test public void acknowledgementRequiresExactCompleteReply() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PioneerRootTaskProtocol.accept(output);
        PioneerRootTaskProtocol.requireAccepted(new ByteArrayInputStream(output.toByteArray()));
        for (String reply : new String[]{"ACCEPTED", "REJECTED\n", "ACCEPTED extra\n", "\n"})
            assertThrows(IOException.class, () -> PioneerRootTaskProtocol.requireAccepted(input(reply)));
    }

    @Test public void submissionFailurePropagatesWithoutRetrying() {
        OutputStream failing = new OutputStream() {
            int written;
            @Override public void write(int value) throws IOException {
                if (++written == 4) throw new IOException("connection lost after submission");
            }
        };
        assertThrows(IOException.class, () -> PioneerRootTaskProtocol.submitTask(failing, "/data/lease"));
    }

    @Test public void leaseMustBeDirectChildWithValidToken() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "native-ncm");
        String token = "0123456789abcdef0123456789abcdef";
        assertEquals(new File(root, token).getCanonicalFile(),
                PioneerRootTaskProtocol.resolveLease(root, new File(root, token).getPath()));
        for (String path : new String[]{new File(root, "../" + token).getPath(),
                new File(root, "nested/" + token).getPath(), new File(root, "bad-token").getPath()})
            assertThrows(IOException.class, () -> PioneerRootTaskProtocol.resolveLease(root, path));
    }
}
