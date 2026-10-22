package com.shilapi.xcertplay.pioneer;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded line protocol shared by the app and its root worker. */
public final class NativeNcmWarmProtocol {
    private static final int MAX_TASK_BYTES = 1024;
    private static final String ACCEPTED = "ACCEPTED";

    private NativeNcmWarmProtocol() { }

    public static String readTask(InputStream input) throws IOException {
        return readLine(input, MAX_TASK_BYTES);
    }

    public static void submitTask(OutputStream output, String path) throws IOException {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_TASK_BYTES || path.indexOf('\n') >= 0 ||
                path.indexOf('\r') >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("Invalid warm NCM task");
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    public static void requireAccepted(InputStream input) throws IOException {
        if (!ACCEPTED.equals(readLine(input, 32)))
            throw new IOException("Native warm worker did not accept task");
    }

    static void accept(OutputStream output) throws IOException {
        output.write((ACCEPTED + "\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    static File resolveLease(File root, String path) throws IOException {
        File lease = new File(path).getCanonicalFile();
        if (!root.getCanonicalFile().equals(lease.getParentFile()) ||
                !lease.getName().matches("[0-9a-f]{32}"))
            throw new IOException("Invalid warm NCM lease");
        return lease;
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != '\n') {
            if (next < 0 || next == 0 || next == '\r' || bytes.size() >= limit)
                throw new IOException("Invalid warm NCM message");
            bytes.write(next);
        }
        if (bytes.size() == 0) throw new IOException("Empty warm NCM message");
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
