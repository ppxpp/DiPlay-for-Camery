package com.shilapi.xcertplay.pioneer;

import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Fixed read-only commands, with no intermediary shell and one bounded child/output lifetime. */
public final class PioneerRootDiagnostics {
    // Even fully JSON-escaped output must fit the common IPC response frame.
    private static final int OUTPUT_LIMIT = 512 * 1024;
    private static final Pattern USB_KERNEL_LINE = Pattern.compile("(?i)(\\busb\\b|usbfs|[xoeu]hci|ci_hdrc|dwc[23]?|\\botg\\b)");
    private static final Map<String, String[]> COMMANDS = new LinkedHashMap<>();
    static {
        COMMANDS.put("identity", new String[]{"id"});
        COMMANDS.put("logcat", new String[]{"logcat", "-b", "main", "-b", "system", "-b", "crash", "-d", "-t", "2500", "-v", "threadtime", "*:S", "DiPlay:I", "DiPlayUpdate:I", "DiPlay-USB:E", "DiPlay-Pioneer-Navi:V", "DiPlay-HudFilter:V", "Pioneer:I", "UsbHostManager:I", "UsbDeviceManager:I", "UsbPortManager:I", "AndroidRuntime:E", "DEBUG:E", "ActivityManager:W", "WifiStateMachine:I", "WifiService:I", "wpa_supplicant:I", "hostapd:I", "dnsmasq:I", "Blueforce:I", "BlueforceService:I", "BluetoothAdapter:I", "BluetoothJni:I", "Tethering:I", "WifiController:I", "ConnectivityService:I"});
        COMMANDS.put("hud_firmware", PioneerHudFirmware.command());
        COMMANDS.put("kernel_usb", new String[]{"dmesg"});
        COMMANDS.put("interfaces", new String[]{"ip", "addr", "show"});
        COMMANDS.put("routes", new String[]{"ip", "route", "show", "table", "all"});
        COMMANDS.put("rules", new String[]{"ip", "rule", "show"});
        COMMANDS.put("firewall", new String[]{"iptables-save"});
        COMMANDS.put("firewall6", new String[]{"ip6tables-save"});
        COMMANDS.put("services", new String[]{"service", "list"});
        COMMANDS.put("processes", new String[]{"ps"});
        COMMANDS.put("wifi_link", new String[]{"iw", "dev"});
    }
    private static final Lane lane = new Lane();
    public static String[] names() { return COMMANDS.keySet().toArray(new String[0]); }
    static String kernelUsbLines(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (USB_KERNEL_LINE.matcher(line).find() && !lower.contains("serialnumber") &&
                    !lower.contains("serial number") && !lower.contains("serial=")) result.append(line).append('\n');
        }
        return result.toString();
    }
    static JSONObject run(String name, Callable<Boolean> current) throws Exception {
        String[] command = COMMANDS.get(name);
        if (command == null) throw new IOException("ROOT_DIAGNOSTIC_UNKNOWN_COMMAND");
        return lane.run(name, () -> new ProcessBuilder(command).redirectErrorStream(true).start(), current,
            () -> System.nanoTime() / 1_000_000L, Thread::sleep, 3000, 500);
    }

    interface Starter { Process start() throws Exception; }
    static final class Lane {
        private boolean poisoned;
        synchronized JSONObject run(String name, Starter starter, Callable<Boolean> current,
                PioneerRootCommand.Clock clock, PioneerRootCommand.Sleeper sleep,
                long executionMs, long cleanupMs) throws Exception {
            if (poisoned) throw new PioneerRootIpc.CompletionUnknown("DIAGNOSTIC_PREVIOUS_CHILD_UNCONFIRMED");
            if (!current.call()) throw new IOException("DIAGNOSTIC_CANCELLED");
            Process child = starter.start();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            AtomicBoolean truncated = new AtomicBoolean(), readFailed = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                try (InputStream input = child.getInputStream()) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = input.read(chunk)) >= 0) synchronized (bytes) {
                        int take = Math.min(count, OUTPUT_LIMIT - bytes.size());
                        if (take > 0) bytes.write(chunk, 0, take);
                        if (take < count) truncated.set(true);
                    }
                } catch (IOException error) { readFailed.set(true); }
            }, "root-diagnostic-output");
            reader.setDaemon(true);
            boolean interrupted = false;
            try {
                child.getOutputStream().close();
                reader.start();
                long until = clock.getAsLong() + executionMs;
                Integer exit = null;
                while (clock.getAsLong() < until && current.call()) {
                    try { exit = child.exitValue(); break; }
                    catch (IllegalThreadStateException busy) { sleep.sleep(25); }
                }
                if (exit == null) try { exit = child.exitValue(); } catch (IllegalThreadStateException busy) { }
                // A process exit does not prove that its output reader has completed.
                reader.join(250);
                String text;
                synchronized (bytes) { text = bytes.toString("UTF-8"); }
                // Keep command errors intact; a denied dmesg must not look like an empty USB history.
                if ("kernel_usb".equals(name) && exit != null && exit == 0) text = kernelUsbLines(text);
                String status = exit == null ? "timeout" : readFailed.get() ? "unavailable" :
                    truncated.get() || reader.isAlive() ? "truncated" : exit != 0 ? "command_failed" : text.isEmpty() ? "empty" : "success";
                if ("hud_firmware".equals(name) && exit != null) text = PioneerHudFirmware.commandReport(text, exit);
                return new JSONObject().put("name", name).put("status", status).put("text", text);
            } catch (InterruptedException cancelled) {
                interrupted = true;
                throw cancelled;
            } finally {
                // Cancellation cannot skip teardown. An unconfirmed child/reader fences this lane
                // for the owner's lifetime, so repeated exports cannot accumulate processes/threads.
                interrupted |= Thread.interrupted();
                try {
                    if (!exited(child)) {
                        child.destroy();
                        interrupted |= awaitExit(child, clock, sleep, cleanupMs);
                        if (!exited(child)) {
                            forceKill(child);
                            interrupted |= awaitExit(child, clock, sleep, cleanupMs);
                        }
                    }
                    if (exited(child)) {
                        try { child.getInputStream().close(); } catch (IOException ignored) { }
                        try { child.getErrorStream().close(); } catch (IOException ignored) { }
                    }
                    long until = clock.getAsLong() + 250;
                    while (reader.isAlive() && clock.getAsLong() < until) {
                        try { reader.join(10); } catch (InterruptedException cancelled) { interrupted = true; }
                    }
                    if (!exited(child) || reader.isAlive()) {
                        poisoned = true;
                        throw new PioneerRootIpc.CompletionUnknown("DIAGNOSTIC_CHILD_OR_OUTPUT_UNCONFIRMED");
                    }
                } catch (Throwable failure) {
                    poisoned = true;
                    if (failure instanceof PioneerRootIpc.CompletionUnknown) throw (PioneerRootIpc.CompletionUnknown) failure;
                    throw new PioneerRootIpc.CompletionUnknown("DIAGNOSTIC_TEARDOWN_UNCONFIRMED", failure);
                } finally {
                    if (interrupted || Thread.interrupted()) Thread.currentThread().interrupt();
                }
            }
        }
        private static boolean awaitExit(Process child, PioneerRootCommand.Clock clock,
                PioneerRootCommand.Sleeper sleep, long budget) {
            boolean interrupted = false;
            long until = clock.getAsLong() + budget;
            while (!exited(child) && clock.getAsLong() < until) {
                try { sleep.sleep(Math.max(1, Math.min(20, until - clock.getAsLong()))); }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            return interrupted;
        }
        private static void forceKill(Process child) {
            try { Process.class.getMethod("destroyForcibly").invoke(child); return; } catch (Exception ignored) { }
            try {
                java.lang.reflect.Field pid = child.getClass().getDeclaredField("pid");
                pid.setAccessible(true); int value = pid.getInt(child);
                if (value > 1) android.os.Process.killProcess(value);
            } catch (Exception ignored) { }
        }
        private static boolean exited(Process child) {
            try { child.exitValue(); return true; } catch (IllegalThreadStateException busy) { return false; }
        }
    }
}
