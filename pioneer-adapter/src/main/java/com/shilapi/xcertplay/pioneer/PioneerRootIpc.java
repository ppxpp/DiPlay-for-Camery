package com.shilapi.xcertplay.pioneer;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Versioned, bounded frames used by every app-to-root request. No shell commands on this wire. */
public final class PioneerRootIpc {
    public static final int VERSION = 1;
    public static final int MAX_REQUEST_BYTES = 16384;
    public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private PioneerRootIpc() { }

    /** Explicitly distinct from a confirmed rejection/failure: side effects/child lifetime are uncertain. */
    public static final class CompletionUnknown extends IOException {
        public CompletionUnknown(String message) { super(message); }
        public CompletionUnknown(String message, Throwable cause) { super(message, cause); }
    }
    static String failureCompletion(boolean started, Throwable failure) {
        if (!started) return "NOT_STARTED";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CompletionUnknown || cause instanceof PioneerInterfaceFirewall.CompletionUnknown)
                return "UNKNOWN";
        }
        return "FAILED";
    }

    /** Helpers may report failure as structured data rather than throw. Never label it success. */
    static String resultCompletion(JSONObject result) {
        boolean error = "ERROR".equals(result.optString("code")) || "ERROR".equals(result.optString("state")) ||
            java.util.regex.Pattern.compile("(?m)^RESULT: *ERROR\\s*$").matcher(result.optString("text")).find();
        if (!error) return "COMPLETED";
        return result.optBoolean("unresolvedChild") || result.optBoolean("mutationAttempted") ||
            "ERROR".equals(result.optString("state")) || result.has("text") ? "UNKNOWN" : "FAILED";
    }

    public static void write(OutputStream output, JSONObject message, int limit) throws Exception {
        byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > limit) throw new IOException("ROOT_IPC_FRAME_SIZE");
        DataOutputStream stream = new DataOutputStream(output);
        stream.writeInt(bytes.length); stream.write(bytes); stream.flush();
    }

    /** Retains partial headers/bodies across socket timeouts so cancellation cannot corrupt framing. */
    public static final class Reader {
        private final int limit;
        private final byte[] header = new byte[4];
        private int headerUsed, used;
        private byte[] body;
        public Reader(int limit) { this.limit = limit; }
        private static int readChunk(InputStream input, byte[] target, int offset, int count) throws IOException {
            try { return input.read(target, offset, count); }
            catch (IOException error) {
                // Android 7 LocalSocket reports SO_RCVTIMEO as errno text rather than SocketTimeoutException.
                String message = error.getMessage();
                if ("Try again".equals(message) || "Resource temporarily unavailable".equals(message)) {
                    java.net.SocketTimeoutException timeout = new java.net.SocketTimeoutException(message);
                    timeout.initCause(error);
                    throw timeout;
                }
                throw error;
            }
        }
        public JSONObject read(InputStream input) throws Exception {
            while (headerUsed < 4) {
                int n = readChunk(input, header, headerUsed, 4 - headerUsed);
                if (n < 0) throw new EOFException("ROOT_IPC_HEADER_EOF");
                headerUsed += n;
            }
            if (body == null) {
                int size = ((header[0] & 255) << 24) | ((header[1] & 255) << 16) |
                    ((header[2] & 255) << 8) | (header[3] & 255);
                if (size <= 0 || size > limit) throw new IOException("ROOT_IPC_FRAME_SIZE");
                body = new byte[size];
            }
            while (used < body.length) {
                int n = readChunk(input, body, used, body.length - used);
                if (n < 0) throw new EOFException("ROOT_IPC_BODY_EOF");
                used += n;
            }
            String value = new String(body, StandardCharsets.UTF_8);
            body = null; headerUsed = 0; used = 0;
            return new JSONObject(value);
        }
    }
}
