package com.shilapi.xcertplay.pioneer;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;

/** Root worker launched through the verified Pioneer service. No arbitrary command input. */
public final class PioneerNativeNcmHelper {
    private static final File TOOL_CACHE_ROOT = new File("/data/diplay-ncm-module-v1");
    private static final File TOOL_CACHE = new File(TOOL_CACHE_ROOT, "tools-v1");
    private File directory, usb, control, data;
    private JSONObject request;
    private String token, name, mac, address;
    private int uid, index, oldMtu;
    private boolean oldUp, oldIpv6Disabled, changedIpv6, changedMtu, changedUp, addedAddress, addedNetwork, addedRoute;
    private PioneerInterfaceFirewall firewall;
    private boolean journalOwned, unresolvedChild, prepared;
    private String firewallPending = "none";
    private NetdIdentity netd;
    private long firewallCheckedAt;
    private long launchDeadline;
    private final long helperEntryMs = SystemClock.elapsedRealtime();
    private final JSONObject preparationTiming = new JSONObject();
    private void preparationStep(String name) throws Exception { preparationTiming.put(name, SystemClock.elapsedRealtime() - helperEntryMs); }
    private boolean cleaning, borrowed, networkMutated;
    private Set<String> originalOwners = Collections.emptySet();

    public static void main(String[] args) {
        if (Process.myUid() != 0 || args.length != 1) return;
        PioneerNativeNcmHelper worker = new PioneerNativeNcmHelper();
        try { worker.run(new File(args[0])); }
        catch (Throwable error) { worker.statusQuietly(worker.journalOwned ? "RELEASE_FAILED" : "FAILED", message(error)); }
        finally { System.exit(0); } // This entry point runs only in the separate root app_process.
    }

    static boolean runReusable(File directory) {
        PioneerNativeNcmHelper worker = new PioneerNativeNcmHelper();
        try {
            worker.run(directory);
            String state = new JSONObject(read(new File(directory, "status.json"), 8192)).getString("state");
            return !worker.unresolvedChild && (state.equals("CLOSED") || state.equals("FAILED"));
        } catch (Throwable error) {
            worker.statusQuietly(worker.journalOwned ? "RELEASE_FAILED" : "FAILED", message(error));
            return false;
        }
    }

    private void run(File dir) throws Exception {
        directory = dir.getCanonicalFile();
        uid = Os.lstat(directory.getPath()).st_uid;
        if (uid < 10000 || !directory.getPath().matches(
            "/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/native-ncm/[0-9a-f]{32}"))
            throw new IOException("Invalid native lease directory");
        token = directory.getName();
        request = new JSONObject(read(new File(directory, "request.json"), 8192));
        launchDeadline = request.getLong("deadline");
        if (SystemClock.elapsedRealtime() > launchDeadline || stopRequested()) throw new IOException("Native launch expired");
        // App creates this file before launch; never replace the inode used by another worker.
        File lockFile = new File(directory.getParentFile(), "owner.lock");
        if (Os.lstat(lockFile.getPath()).st_uid != uid) throw new IOException("Invalid native owner lock");
        try (RandomAccessFile owner = new RandomAccessFile(lockFile, "rw"); FileChannel channel = owner.getChannel()) {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Previous native NCM lease is still active");
            try {
                String failure = null;
                try {
                    if (stopRequested() || SystemClock.elapsedRealtime() > launchDeadline) throw new IOException("Native launch expired");
                    status("PREPARING", "");
                    configure();
                    if (stopRequested() || SystemClock.elapsedRealtime() > launchDeadline) throw new IOException("Native preparation cancelled");
                    prepared = true;
                    status("READY", "");
                    while (!stopRequested()) {
                        validateIdentity();
                        verifyNetworkIdentity();
                        if (!hasAddress()) throw new IOException("Native IPv6 address disappeared");
                        if (borrowed && !networkOwners(command("/system/bin/dumpsys", "netd"), name).equals(originalOwners))
                            throw new IOException("Borrowed NCM network ownership changed");
                        if (!liveHeartbeat()) throw new IOException("Native lease heartbeat expired");
                        if (SystemClock.elapsedRealtime() - firewallCheckedAt >= 10_000) {
                            firewall.verify();
                            firewallCheckedAt = SystemClock.elapsedRealtime();
                        }
                        status("READY", "");
                        Thread.sleep(500);
                    }
                } catch (Throwable error) {
                    failure = message(error);
                    if (error instanceof PioneerInterfaceFirewall.CompletionUnknown && !((PioneerInterfaceFirewall.CompletionUnknown) error).childStopped) {
                        unresolvedChild = true; writeJournal("UNCERTAIN");
                    }
                }
                // Publish the trigger before removing routes/firewall rules. Otherwise a media
                // socket's EPERM can hide the helper failure that actually initiated teardown.
                statusQuietly("RELEASING", failure == null ? "stop requested" : failure);
                List<String> cleanup = cleanup();
                if (!cleanup.isEmpty()) {
                    writeJournal("UNCERTAIN");
                    status("RELEASE_FAILED", (failure == null ? "" : failure + "; ") + String.join("; ", cleanup));
                } else {
                    if (journalOwned) writeJournal("RELEASED");
                    status(failure == null ? "CLOSED" : "FAILED", failure == null ? "" : failure);
                }
            } finally { lock.release(); }
        }
    }

    private void configure() throws Exception {
        preparationStep("configureStartMs");
        CamryAdaptation.INSTANCE.useRequestMode(request);
        if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("Native NCM requires the verified Pioneer firmware family");
        int bus = request.getInt("bus"), device = request.getInt("device");
        File[] entries = new File("/sys/bus/usb/devices").listFiles();
        List<File> matches = new ArrayList<>();
        if (entries != null) for (File entry : entries) {
            if (number(entry, "busnum", 10) == bus && number(entry, "devnum", 10) == device) matches.add(entry.getCanonicalFile());
        }
        if (matches.size() != 1) throw new IOException("iPhone USB identity not found or ambiguous");
        usb = matches.get(0);
        int config = request.getInt("configuration");
        int controlId = request.getInt("control"), dataId = request.getInt("data");
        control = new File(usb, usb.getName() + ":" + config + "." + controlId);
        data = new File(usb, usb.getName() + ":" + config + "." + dataId);
        validateIdentity();
        if (number(control, "bInterfaceClass", 16) != 2 || number(control, "bInterfaceSubClass", 16) != 13 ||
            number(data, "bInterfaceClass", 16) != 10) throw new IOException("USB function is not NCM");
        NativeNcmModule.ensureLoaded(request.getString("apk"), request.getString("boot"), this::guardCommand);
        preparationStep("moduleReadyMs");
        File driver = new File(control, "driver");
        if (!driver.exists()) {
            // A previous user-space backend can leave the driver detached after usbfs closes.
            // Rebind only this exact phone's NCM function; never unbind another driver.
            File bind = new File("/sys/bus/usb/drivers/cdc_ncm/bind");
            try (FileOutputStream out = new FileOutputStream(bind)) { out.write(control.getName().getBytes("US-ASCII")); }
        }
        // Old i.MX6 units can take materially longer than 3 s to finish cdc_ncm probe/bind.
        // Keep enough of the total lease budget for address, route and firewall verification.
        long end = Math.max(SystemClock.elapsedRealtime(),
            Math.min(launchDeadline - 5000, SystemClock.elapsedRealtime() + 12000));
        while (!new File(control, "net").exists() && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
        File[] nets = new File(control, "net").listFiles();
        if (nets == null || nets.length != 1) throw new IOException("NCM_INTERFACE_TIMEOUT: cdc_ncm did not create one network interface");
        name = nets[0].getName();
        if (!name.matches("[a-zA-Z0-9_.-]{1,15}")) throw new IOException("Invalid NCM interface name");
        index = number(new File("/sys/class/net", name), "ifindex", 10);
        mac = read(new File("/sys/class/net/" + name + "/address"), 64).trim();
        verifyNetworkIdentity();
        preparationStep("interfaceReadyMs");
        oldMtu = number(new File("/sys/class/net", name), "mtu", 10);
        oldUp = (Integer.parseInt(read(new File("/sys/class/net/" + name + "/flags"), 64).trim().replace("0x", ""), 16) & 1) != 0;
        oldIpv6Disabled = read(new File("/proc/sys/net/ipv6/conf/" + name + "/disable_ipv6"), 8).trim().equals("1");
        address = request.getString("address");
        if (!address.matches("[0-9a-fA-F:]+") || !(InetAddress.getByName(address) instanceof Inet6Address) ||
            !InetAddress.getByName(address).isLinkLocalAddress()) throw new IOException("Invalid native IPv6 address");
        Set<String> owners = networkOwners(command("/system/bin/dumpsys", "netd"), name);
        originalOwners = new HashSet<>(owners);
        String existing = existingAddress();
        borrowed = !owners.isEmpty();
        if (borrowed && (!oldUp || oldMtu < 1280 || oldIpv6Disabled || existing == null))
            throw new IOException("Managed NCM network is not ready for borrowing");
        if (existing != null) address = existing;
        else address = sessionAddress(mac, token);
        if (!borrowed && !command("/system/bin/ip", "-6", "route", "show", "table", "local_network", "dev", name).trim().isEmpty())
            throw new IOException("Unowned NCM interface has existing local routes");
        verifyFirewallTools();
        netd = findNetd();
        preparationStep("toolsAndOwnersReadyMs");
        firewall = new PioneerInterfaceFirewall(name, token, this::executeCommand, () -> {
            guardCommand();
            validateIdentity(); verifyNetworkIdentity();
            if (!netd.equals(readNetd(netd.pid))) throw new IOException("NETD_IDENTITY_CHANGED");
        }, (pending, unresolved) -> {
            firewallPending = pending; unresolvedChild |= unresolved;
            writeJournal(unresolved ? "UNCERTAIN" : "ACTIVE");
        });
        writeJournal("ACTIVE");
        firewall.install();
        preparationStep("firewallReadyMs");
        firewallCheckedAt = SystemClock.elapsedRealtime();
        if (borrowed) { firewall.verify(); return; }
        networkMutated = true;
        // Only record mutations this lease owns. A pre-existing route/interface is preserved.
        if (!oldUp) { changedUp = true; command("/system/bin/ndc", "interface", "setcfg", name, "up"); }
        if (oldMtu < 1280) { changedMtu = true; command("/system/bin/ndc", "interface", "setmtu", name, "1280"); }
        if (oldIpv6Disabled) { changedIpv6 = true; command("/system/bin/ndc", "interface", "ipv6", name, "enable"); }
        if (!hasAddress()) {
            addedAddress = true;
            command("/system/bin/ip", "-6", "addr", "add", address + "/64", "dev", name, "scope", "link", "nodad");
        }
        if (owners.isEmpty()) {
            addedNetwork = true; command("/system/bin/ndc", "network", "interface", "add", "local", name);
        }
        String routes = command("/system/bin/ip", "-6", "route", "show", "table", "local_network", "dev", name);
        if (!routes.contains("fe80::/64")) {
            addedRoute = true; command("/system/bin/ndc", "network", "route", "add", "local", name, "fe80::/64");
        }
        preparationStep("networkConfiguredMs");
        firewall.verify();
        preparationStep("verifiedMs");
        firewallCheckedAt = SystemClock.elapsedRealtime();
        if (!hasAddress()) throw new IOException("Native IPv6 address was not configured");
    }

    private void validateIdentity() throws Exception {
        if (usb == null || number(usb, "busnum", 10) != request.getInt("bus") ||
            number(usb, "devnum", 10) != request.getInt("device") || number(usb, "idVendor", 16) != 0x05ac ||
            number(usb, "idProduct", 16) != request.getInt("product") ||
            number(usb, "bConfigurationValue", 10) != request.getInt("configuration")) throw new IOException("iPhone USB attachment changed");
    }

    private void verifyNetworkIdentity() throws Exception {
        if (name == null || index <= 0 || !mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) throw new IOException("Invalid native network identity");
        File net = new File("/sys/class/net", name);
        if (number(net, "ifindex", 10) != index || !read(new File(net, "address"), 64).trim().equals(mac) ||
            !new File(net, "device").getCanonicalFile().equals(control.getCanonicalFile()) ||
            !new File(control, "driver").getCanonicalFile().equals(new File("/sys/bus/usb/drivers/cdc_ncm")) ||
            !new File(data, "driver").getCanonicalFile().equals(new File("/sys/bus/usb/drivers/cdc_ncm"))) throw new IOException("NCM network binding changed");
    }

    static String sessionAddress(String mac, String token) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest((mac + ":" + token).getBytes("UTF-8"));
        byte[] bytes = new byte[16]; bytes[0] = (byte) 0xfe; bytes[1] = (byte) 0x80;
        System.arraycopy(hash, 0, bytes, 8, 8); bytes[8] = (byte) (bytes[8] & 0xfc);
        return InetAddress.getByAddress(bytes).getHostAddress();
    }

    private String existingAddress() throws Exception {
        NetworkInterface iface = NetworkInterface.getByName(name);
        if (iface == null || iface.getIndex() != index) throw new IOException("NCM interface changed");
        List<String> addresses = new ArrayList<>();
        for (InetAddress a : Collections.list(iface.getInetAddresses()))
            if (a instanceof Inet6Address && a.isLinkLocalAddress()) {
                String candidate = a.getHostAddress().split("%", 2)[0];
                String dump = command("/system/bin/ip", "-6", "addr", "show", "dev", name, "scope", "link");
                if (addressReady(dump, a.getAddress())) addresses.add(candidate);
            }
        Collections.sort(addresses);
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    static boolean addressReady(String dump, byte[] address) throws Exception {
        for (String line : dump.split("\\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 2 || !fields[0].equals("inet6") ||
                line.contains("tentative") || line.contains("dadfailed") || line.contains("deprecated")) continue;
            if (Arrays.equals(InetAddress.getByName(fields[1].split("/", 2)[0]).getAddress(), address)) return true;
        }
        return false;
    }

    private void releaseGuard(Set<String> owners) throws Exception {
        validateIdentity(); verifyNetworkIdentity();
        if (!networkOwners(command("/system/bin/dumpsys", "netd"), name).equals(owners))
            throw new IOException("NCM network ownership changed during release");
        validateIdentity(); verifyNetworkIdentity();
    }

    private boolean hasAddress() throws Exception {
        NetworkInterface iface = NetworkInterface.getByName(name);
        if (iface == null || iface.getIndex() != index) return false;
        for (InetAddress a : Collections.list(iface.getInetAddresses())) if (Arrays.equals(a.getAddress(), InetAddress.getByName(address).getAddress())) return true;
        return false;
    }

    private List<String> cleanup() {
        cleaning = true;
        List<String> errors = new ArrayList<>();
        if (unresolvedChild) {
            errors.add("COMMAND_CHILD_UNCONFIRMED; no further network mutation permitted"); return errors;
        }
        if (firewall != null) errors.addAll(firewall.restore());
        if (unresolvedChild || borrowed || !networkMutated || name == null) return errors;
        try { validateIdentity(); verifyNetworkIdentity(); }
        catch (Exception gone) {
            // The kernel removes routes with a detached netdev, but netd may retain its name.
            // Remove our membership only when that name has not been reused by a new netdev.
            if (new File("/sys/class/net", name).exists()) {
                errors.add("NCM identity changed; cannot restore a replacement interface");
            } else if (addedNetwork) {
                try {
                    Set<String> owners = networkOwners(command("/system/bin/dumpsys", "netd"), name);
                    if (!owners.isEmpty() && !owners.equals(Collections.singleton("LOCAL")))
                        throw new IOException("Detached NCM network ownership changed");
                    if (owners.equals(Collections.singleton("LOCAL"))) {
                        if (new File("/sys/class/net", name).exists()) throw new IOException("NCM interface name reused during release");
                        command("/system/bin/ndc", "network", "interface", "remove", "local", name);
                    }
                    if (new File("/sys/class/net", name).exists() || !networkOwners(command("/system/bin/dumpsys", "netd"), name).isEmpty())
                        throw new IOException("Detached NCM network release unconfirmed");
                } catch (Exception e) { errors.add(message(e)); }
            }
            return errors; // Never restore interface settings onto a replacement device.
        }
        try {
            Set<String> currentOwners = networkOwners(command("/system/bin/dumpsys", "netd"), name);
            if (!currentOwners.equals(originalOwners) && !(addedNetwork && currentOwners.equals(Collections.singleton("LOCAL"))))
                throw new IOException("NCM network ownership changed during release");
            Set<String> expected = currentOwners;
            releaseGuard(expected);
            if (addedRoute && command("/system/bin/ip", "-6", "route", "show", "table", "local_network", "dev", name).contains("fe80::/64")) {
                releaseGuard(expected);
                command("/system/bin/ndc", "network", "route", "remove", "local", name, "fe80::/64");
            }
            if (addedNetwork && expected.equals(Collections.singleton("LOCAL"))) {
                releaseGuard(expected);
                command("/system/bin/ndc", "network", "interface", "remove", "local", name);
                expected = originalOwners;
            }
            releaseGuard(expected);
            if (addedAddress && hasAddress()) command("/system/bin/ip", "-6", "addr", "del", address + "/64", "dev", name);
            releaseGuard(expected);
            if (changedIpv6) command("/system/bin/ndc", "interface", "ipv6", name, "disable");
            releaseGuard(expected);
            if (changedMtu) command("/system/bin/ndc", "interface", "setmtu", name, Integer.toString(oldMtu));
            releaseGuard(expected);
            if (changedUp) command("/system/bin/ndc", "interface", "setcfg", name, "down");
            releaseGuard(originalOwners);
            boolean up = (Integer.parseInt(read(new File("/sys/class/net/" + name + "/flags"), 64).trim().replace("0x", ""), 16) & 1) != 0;
            if (up != oldUp || number(new File("/sys/class/net", name), "mtu", 10) != oldMtu ||
                read(new File("/proc/sys/net/ipv6/conf/" + name + "/disable_ipv6"), 8).trim().equals("1") != oldIpv6Disabled ||
                addedAddress && hasAddress() || !command("/system/bin/ip", "-6", "route", "show", "table", "local_network", "dev", name).trim().isEmpty())
                throw new IOException("NCM network restoration readback mismatch");
        } catch (Exception e) { errors.add(message(e)); }
        return errors;
    }

    private void attempt(List<String> errors, String... args) {
        try { command(args); } catch (Exception e) { errors.add(message(e)); }
    }

    static Set<String> networkOwners(String dump, String name) throws IOException {
        if (!dump.contains("NetworkController")) throw new IOException("Cannot inspect netd network ownership");
        Set<String> owners = new HashSet<>(); int locals = 0;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([0-9]+) (DUMMY|LOCAL|PHYSICAL|VIRTUAL)(?: ([A-Za-z0-9_.,-]+))?");
        for (String line : dump.split("\\n")) {
            java.util.regex.Matcher match = pattern.matcher(line.trim());
            if (!match.matches()) continue;
            if (match.group(2).equals("LOCAL")) locals++;
            if (match.group(3) != null && Arrays.asList(match.group(3).split(",")).contains(name)) owners.add(match.group(2));
        }
        if (locals != 1) throw new IOException("Ambiguous netd local network");
        return owners;
    }

    private void guardCommand() throws IOException {
        if (unresolvedChild) throw new IOException("COMMAND_CHILD_UNCONFIRMED");
        if (!cleaning && (stopRequested() || (!prepared && SystemClock.elapsedRealtime() > launchDeadline) || !liveHeartbeat()))
            throw new IOException("Native session cancelled");
    }

    private PioneerInterfaceFirewall.Result executeCommand(String[] args) throws Exception {
        guardCommand();
        // Never configure a replacement netd process. Exact owned firewall deletion is still
        // allowed after a restart, as in b819, but only after dump + -C agree on that rule.
        if (netd != null && (args[0].endsWith("/ndc") ||
            args[0].endsWith("/ip6tables") && java.util.Arrays.asList(args).contains("-I"))) {
            if (!netd.equals(readNetd(netd.pid))) throw new IOException("NETD_IDENTITY_CHANGED");
        }
        if (prepared && !cleaning) status("READY", "verifying native transport");
        try { return PioneerRootCommand.run(args); }
        catch (PioneerInterfaceFirewall.CompletionUnknown error) {
            unresolvedChild |= !error.childStopped;
            if (journalOwned) writeJournal("UNCERTAIN");
            throw error;
        }
    }

    private String command(String... args) throws Exception {
        PioneerInterfaceFirewall.Result result = executeCommand(args);
        if (result.exit != 0) throw new CommandFailure(result.exit, new File(args[0]).getName() + " failed: " + result.output);
        if (args[0].endsWith("/ndc")) {
            String[] lines = result.output.split("\\n");
            if (lines.length == 0 || !lines[lines.length - 1].trim().matches("200 [0-9]+(?: .*)?"))
                throw new IOException("NETD_REJECTED: " + result.output);
        }
        return result.output;
    }

    private void writeJournal(String state) throws Exception {
        JSONObject value = new JSONObject().put("version", 2).put("boot", request.optString("boot", "unknown"))
            .put("token", token).put("state", state).put("pending", firewallPending).put("unresolved", unresolvedChild)
            .put("interface", name == null ? "" : name).put("ifindex", index).put("mac", mac == null ? "" : mac)
            .put("usb", usb == null ? "" : usb.getCanonicalPath())
            .put("netdPid", netd == null ? 0 : netd.pid).put("netdStart", netd == null ? 0 : netd.start);
        File temp = new File(directory, "firewall-journal.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString().getBytes("UTF-8")); out.getFD().sync();
        }
        Os.chown(temp.getPath(), uid, uid); Os.chmod(temp.getPath(), 0600);
        Os.rename(temp.getPath(), new File(directory, "firewall-journal.json").getPath());
        journalOwned = true;
    }

    private static final class NetdIdentity {
        final int pid; final long start;
        NetdIdentity(int pid, long start) { this.pid = pid; this.start = start; }
        @Override public boolean equals(Object other) {
            return other instanceof NetdIdentity && ((NetdIdentity) other).pid == pid && ((NetdIdentity) other).start == start;
        }
        @Override public int hashCode() { return pid * 31 + Long.valueOf(start).hashCode(); }
    }
    private static NetdIdentity findNetd() throws Exception {
        List<NetdIdentity> matches = new ArrayList<>();
        for (File entry : new File("/proc").listFiles()) if (entry.getName().matches("[0-9]+")) {
            try { matches.add(readNetd(Integer.parseInt(entry.getName()))); } catch (Exception ignored) { }
        }
        if (matches.size() != 1) throw new IOException("NETD_IDENTITY_UNCONFIRMED");
        return matches.get(0);
    }
    private static NetdIdentity readNetd(int pid) throws Exception {
        String command = read(new File("/proc/" + pid + "/cmdline"), 8192).split("\\x00", 2)[0];
        if (!command.equals("/system/bin/netd") || !Os.readlink("/proc/" + pid + "/exe").equals("/system/bin/netd"))
            throw new IOException("NETD_IDENTITY_CHANGED");
        return new NetdIdentity(pid, processStartTime(read(new File("/proc/" + pid + "/stat"), 8192), pid));
    }
    static long processStartTime(String stat, int pid) throws IOException {
        int open = stat.indexOf('('), close = stat.lastIndexOf(") ");
        if (open < 1 || close <= open || !stat.substring(0, open).trim().equals(Integer.toString(pid)))
            throw new IOException("Invalid process identity");
        String[] fields = stat.substring(close + 2).trim().split("\\s+");
        try { return Long.parseLong(fields[19]); } catch (Exception error) { throw new IOException("Invalid process start time", error); }
    }
    /**
     * b819 caches verified system binaries until their inode/metadata changes. The cache lives in
     * the same root-private directory as the audited module and never weakens the hash comparison.
     */
    static void verifyFirewallTools() throws Exception {
        // Same audited tool identities as b819. Never issue privileged mutations on a different binary.
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("/system/bin/netd", "3344fabbb32d2c2a8755ed073b286d9e256d23bbb129399a9e85588db1238ded");
        hashes.put("/system/bin/ndc", "60beeec345d4fcb894b825ac956bab37d8ad7ff16fca859963d0416b12b67248");
        hashes.put("/system/bin/ip", "ebe3318280d9953306ac83d97875d28420d3f2edd9e38faec0ed6819490de6c5");
        hashes.put("/system/bin/dumpsys", "f9131615b2a8bf197174d81cac85fec015785eaeccf760aa0042234aa88aad63");
        hashes.put("/system/bin/iptables", "337a500732243da20df46ac76e716bcba300abfe657e3f21165507ae8906ece8");
        hashes.put("/system/bin/ip6tables", "8ebf50a4b41ff6d248ff1b4e9166ecbf5c03816e021c919df211e614fa2d5efd");
        hashes.put("/system/lib/libcutils.so", "ce358f905b4720966c295c13ab51a6969c48e941a7b7b0a5f471f9f3d2ff8525");
        hashes.put("/system/lib/liblogwrap.so", "b9e5e0fd41578f9d06a027f7491e9fa14a716f726701c2855f10c61b53d7aecb");
        hashes.put("/system/lib/libnetutils.so", "a292492832c4e280bc685ab72670dc793f7b97ada712b7e4bcadb4af7b7a1b9e");
        hashes.put("/system/lib/libsysutils.so", "38b73b38320f1203058d734cf28831a3a714fa934e91e55897c0576f0fdc6867");
        hashes.put("/system/lib/libiprouteutil.so", "fca18bd3ab4ae58b21ac5618c3624f6b74bb68dc319526963d2b22974dc1c2c5");
        hashes.put("/system/lib/libnetlink.so", "c8a12462426b3266d0ca61a1bb635827a9b20e273006378dfa92806db33f045f");
        ensureToolCacheRoot();
        Map<String, CachedTool> cached = readToolCache();
        Map<String, CachedTool> current = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            File file = new File(entry.getKey());
            StructStat before = Os.stat(file.getPath());
            if (!OsConstants.S_ISREG(before.st_mode)) throw new IOException("UNSUPPORTED_FIREWALL_TOOL " + entry.getKey());
            String identity = before.st_dev + ":" + before.st_ino + ":" + before.st_size + ":" + before.st_mtime + ":" + before.st_ctime;
            CachedTool previous = cached.get(entry.getKey());
            if (previous != null && identity.equals(previous.identity) && entry.getValue().equals(previous.hash)) {
                current.put(entry.getKey(), previous);
                continue;
            }
            java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) >= 0) hash.update(buffer, 0, n);
            }
            StructStat after = Os.stat(file.getPath());
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size ||
                before.st_mtime != after.st_mtime || before.st_ctime != after.st_ctime)
                throw new IOException("UNSTABLE_FIREWALL_TOOL " + entry.getKey());
            StringBuilder hex = new StringBuilder(); for (byte b : hash.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            if (!entry.getValue().equals(hex.toString())) throw new IOException("UNSUPPORTED_FIREWALL_TOOL " + entry.getKey());
            current.put(entry.getKey(), new CachedTool(identity, entry.getValue()));
        }
        if (!current.equals(cached)) writeToolCache(current);
    }

    private static final class CachedTool {
        final String identity, hash;
        CachedTool(String identity, String hash) { this.identity = identity; this.hash = hash; }
        @Override public boolean equals(Object other) {
            return other instanceof CachedTool && identity.equals(((CachedTool) other).identity) && hash.equals(((CachedTool) other).hash);
        }
        @Override public int hashCode() { return identity.hashCode() * 31 + hash.hashCode(); }
    }

    private static void ensureToolCacheRoot() throws Exception {
        try { Os.mkdir(TOOL_CACHE_ROOT.getPath(), 0700); }
        catch (android.system.ErrnoException error) { if (error.errno != OsConstants.EEXIST) throw error; }
        StructStat stat = Os.lstat(TOOL_CACHE_ROOT.getPath());
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 0777) != 0700)
            throw new IOException("INVALID_MODULE_ROOT");
    }

    private static Map<String, CachedTool> readToolCache() {
        Map<String, CachedTool> values = new LinkedHashMap<>();
        if (!TOOL_CACHE.isFile() || TOOL_CACHE.length() > 8192) return values;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(TOOL_CACHE), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 3 || !fields[0].startsWith("/system/") ||
                    !fields[1].matches("[0-9:-]+") || !fields[2].matches("[0-9a-f]{64}")) continue;
                values.put(fields[0], new CachedTool(fields[1], fields[2]));
            }
        } catch (Exception ignored) { return new LinkedHashMap<>(); }
        return values;
    }

    private static void writeToolCache(Map<String, CachedTool> values) throws Exception {
        File temp = new File(TOOL_CACHE_ROOT, TOOL_CACHE.getName() + "." + Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, CachedTool> entry : values.entrySet())
                text.append(entry.getKey()).append('\t').append(entry.getValue().identity).append('\t')
                    .append(entry.getValue().hash).append('\n');
            out.write(text.toString().getBytes("UTF-8"));
            out.getFD().sync();
        }
        Os.rename(temp.getPath(), TOOL_CACHE.getPath());
        FileDescriptor directory = Os.open(TOOL_CACHE_ROOT.getPath(), OsConstants.O_RDONLY, 0);
        try { Os.fsync(directory); } finally { Os.close(directory); }
    }

    private static final class CommandFailure extends IOException {
        final int code;
        CommandFailure(int code, String text) { super(text); this.code = code; }
    }

    private boolean liveHeartbeat() {
        try { long at = Long.parseLong(read(new File(directory, "heartbeat"), 64).trim()); return at <= SystemClock.elapsedRealtime() && SystemClock.elapsedRealtime() - at < 15000; }
        catch (Exception error) { return false; }
    }
    private boolean stopRequested() { return new File(directory, "stop").exists(); }
    private void statusQuietly(String state, String error) { try { if (directory != null) status(state, error); } catch (Exception ignored) { } }
    private void status(String state, String error) throws Exception {
        JSONObject value = new JSONObject().put("token", token).put("state", state).put("error", error)
            .put("address", address == null ? "" : address).put("mode", borrowed ? "BORROWED" : "CONFIGURED")
            .put("updated", SystemClock.elapsedRealtime())
            .put("preparationTiming", preparationTiming)
            .put("helperStartupMs", helperEntryMs - (request == null ? helperEntryMs : request.optLong("requestedAtMs", helperEntryMs)))
            .put("interface", name == null ? "" : name).put("index", index).put("mac", mac == null ? "" : mac);
        File temp = new File(directory, "status.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(value.toString().getBytes("UTF-8")); if (!state.equals("READY")) out.getFD().sync(); }
        Os.chown(temp.getPath(), uid, uid); Os.chmod(temp.getPath(), 0600);
        Os.rename(temp.getPath(), new File(directory, "status.json").getPath());
    }
    private static int number(File dir, String name, int radix) {
        try { return Integer.parseInt(read(new File(dir, name), 128).trim(), radix); } catch (Exception e) { return -1; }
    }
    private static String read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512]; int n;
            while ((n = in.read(buffer)) >= 0) { if (out.size() + n > limit) throw new IOException("Native state too large"); out.write(buffer, 0, n); }
            return out.toString("UTF-8");
        }
    }
    private static String message(Throwable error) { return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()); }
}
