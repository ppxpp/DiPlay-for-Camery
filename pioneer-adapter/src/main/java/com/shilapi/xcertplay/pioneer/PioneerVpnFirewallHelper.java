package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.*;

/** Initialization-only persistent CARNET rules for the verified VPN TUN. */
public final class PioneerVpnFirewallHelper {
    static void runTask(File requestedDirectory) {
        File directory = null;
        JSONObject status = new JSONObject();
        try {
            File dir = requestedDirectory.getCanonicalFile();
            if (!dir.getPath().matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/vpn-firewall/[0-9a-f]{32}"))
                throw new IOException("VPN_FIREWALL_INVALID_DIRECTORY");
            StructStat owner = Os.lstat(dir.getPath());
            PioneerVpnPermissionHelper.requireFile(owner, true, owner.st_uid);
            JSONObject request;
            try (FileInputStream in = PioneerRootFiles.input(new File(dir, "request.json").getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0)) {
                PioneerVpnPermissionHelper.requireFile(Os.fstat(in.getFD()), false, owner.st_uid);
                request = new JSONObject(PioneerVpnPermissionHelper.read(in, 8192));
            }
            String pkg = request.getString("package"), token = request.getString("token");
            int uid = request.getInt("uid");
            if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || uid != owner.st_uid || !token.equals(dir.getName()))
                throw new IOException("VPN_FIREWALL_INVALID_OWNER");
            Context context = PioneerVpnPermissionHelper.systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), pkg, 0, uid / 100000);
            PioneerVpnPermissionHelper.requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), dir.getParentFile().getParentFile(), new File(app.dataDir, "files"));
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length != 1 || !pkg.equals(packages[0])) throw new IOException("VPN_FIREWALL_SHARED_UID");
            directory = dir;
            status.put("token", token).put("pid", Process.myPid())
                .put("helperStartupMs",SystemClock.elapsedRealtime()-request.getLong("requestedAtMs"));
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("VPN_FIREWALL_UNSUPPORTED_DEVICE");
            PioneerFirmwareCatalog.requireSupportedFirmware();
            String name = request.getString("interface");
            if (!name.matches("tun[0-9]{1,5}")) throw new IOException("VPN_FIREWALL_INVALID_TUN");
            int index = request.getInt("index");
            String literal = request.getString("address");
            if (!literal.matches("[0-9a-fA-F:]{2,39}")) throw new IOException("VPN_FIREWALL_INVALID_ADDRESS");
            InetAddress address = InetAddress.getByName(literal);
            if (!(address instanceof Inet6Address) || !address.isLinkLocalAddress()) throw new IOException("VPN_FIREWALL_INVALID_ADDRESS");
            PioneerInterfaceFirewall.Guard guard = () -> {
                long now = SystemClock.elapsedRealtime();
                if (now >= request.getLong("deadline")) throw new IOException("VPN_FIREWALL_START_EXPIRED");
                if (new File(dir, "stop").exists()) throw new IOException("VPN_FIREWALL_STOPPED");
                if (!request.getString("boot").equals(PioneerVpnPermissionHelper.readFile(new File("/proc/sys/kernel/random/boot_id"),128).trim()))
                    throw new IOException("VPN_FIREWALL_BOOT_CHANGED");
                int pid = request.getInt("appPid");
                String stat = PioneerVpnPermissionHelper.readFile(new File("/proc/" + pid + "/stat"),8192);
                if (Os.stat("/proc/" + pid).st_uid != uid || !request.getString("appStart").equals(stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19]))
                    throw new IOException("VPN_FIREWALL_APP_CHANGED");
                NetworkInterface iface = NetworkInterface.getByName(name);
                if (iface == null || iface.getIndex() != index || !Collections.list(iface.getInetAddresses()).stream().anyMatch(a -> Arrays.equals(a.getAddress(),address.getAddress())))
                    throw new IOException("VPN_FIREWALL_TUN_CHANGED");
            };
            guard.check();
            long now = SystemClock.elapsedRealtime();
            if (now < request.getLong("requestedAtMs") || now >= request.getLong("deadline") || request.getLong("deadline")-request.getLong("requestedAtMs") > 15000)
                throw new IOException("VPN_FIREWALL_START_EXPIRED");
            try (FileOutputStream exclusive = PioneerRootFiles.output(new File(dir.getParentFile(),"owner.lock").getPath(),OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW,0)) {
                StructStat lockStat=Os.fstat(exclusive.getFD());
                if (lockStat.st_uid != uid || !OsConstants.S_ISREG(lockStat.st_mode) || lockStat.st_nlink != 1 || (lockStat.st_mode & 0777) != 0600) throw new IOException("VPN_FIREWALL_LOCK_OWNER");
                try (FileLock held = exclusive.getChannel().tryLock()) {
                    if (held == null) throw new IOException("VPN_FIREWALL_OWNER_BUSY");
                    if (!request.optBoolean("persistent")) throw new IOException("VPN_FIREWALL_PERSISTENCE_REQUIRED");
                    String stable = request.getString("stableToken");
                    if (!stable.matches("[0-9a-f]{32}")) throw new IOException("VPN_FIREWALL_STABLE_ID_INVALID");
                    // Legacy journals are app-owned and identify exact old rules. Never clear chains.
                    for (File previous : dir.getParentFile().listFiles()) {
                        if (!previous.isDirectory() || previous.equals(dir)) continue;
                        File oldRequest = new File(previous,"request.json");
                        if (!oldRequest.isFile()) continue;
                        JSONObject old = ownedJson(oldRequest,uid);
                        if (!pkg.equals(old.optString("package"))) continue;
                        if (old.optBoolean("persistent")) {
                            File oldJournal = new File(previous,"firewall-journal.json");
                            if (oldJournal.isFile() && ownedJson(oldJournal,uid).optBoolean("unresolved"))
                                throw new IOException("VPN_FIREWALL_PREVIOUS_COMMAND_UNCONFIRMED");
                            String oldStable = old.optString("stableToken");
                            if (stable.equals(oldStable) && !name.equals(old.optString("interface"))) {
                                List<String> errors = PioneerInterfaceFirewall.recover(old.getString("interface"),stable,
                                    argv -> PioneerRootCommand.run(argv,1200), (pending,unresolved) -> writeJournal(dir,request,"CONFIGURING",pending,unresolved));
                                if (!errors.isEmpty()) throw new IOException("VPN_FIREWALL_OLD_INTERFACE " + errors);
                            }
                        } else if (new File(previous,"firewall-journal.json").exists()) {
                            recoverPrevious(previous,request,status);
                        }
                    }
                    PioneerInterfaceFirewall persistent = new PioneerInterfaceFirewall(name,stable,
                        argv -> PioneerRootCommand.run(argv,1200), guard,
                        (pending,unresolved) -> {
                            writeJournal(dir,request,"CONFIGURING",pending,unresolved);
                            if (!pending.equals("none")) publish(dir,status,"CONFIGURING_RULES",null);
                        });
                    publish(dir,status,"CHECKING_RULES",null);
                    long verifiedAt = SystemClock.elapsedRealtime();
                    int changed = persistent.ensureFromSnapshots();
                    status.put("firewallReadVerifyMs",SystemClock.elapsedRealtime()-verifiedAt);
                    publish(dir,status,"VERIFYING_RULES",null);
                    status.put("rulesChanged",changed);
                    writeJournal(dir,request,"PERSISTENT","none",false);
                    publish(dir,status,"READY",null);
                    return;
                }
            }
        } catch (Throwable error) {
            android.util.Log.e("DiPlayVpnRoot", "Firewall initialization failed directory=" + directory, error);
            if (directory != null) try { publish(directory, status, "ERROR", error.toString()); }
            catch (Throwable ignored) { }
        }
    }

    private static JSONObject ownedJson(File file,int uid) throws Exception {
        try (FileInputStream in = PioneerRootFiles.input(file.getPath(),OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW,0)) {
            PioneerVpnPermissionHelper.requireFile(Os.fstat(in.getFD()),false,uid);
            return new JSONObject(PioneerVpnPermissionHelper.read(in,8192));
        }
    }
    private static void recoverPrevious(File previous,JSONObject current,JSONObject status) throws Exception {
        if (!previous.getName().matches("[0-9a-f]{32}") || !previous.equals(previous.getCanonicalFile()))
            throw new IOException("VPN_FIREWALL_PREVIOUS_PATH_INVALID");
        int uid = current.getInt("uid");
        PioneerVpnPermissionHelper.requireFile(Os.lstat(previous.getPath()),true,uid);
        JSONObject oldJournal = ownedJson(new File(previous,"firewall-journal.json"),uid);
        if (!current.getString("boot").equals(oldJournal.optString("boot"))) {
            return;
        }
        if (oldJournal.optString("state").equals("RELEASED") && oldJournal.optString("pending").equals("none") && !oldJournal.optBoolean("unresolved",true)) return;
        if (!oldJournal.optString("state").equals("ACTIVE") || !oldJournal.optString("pending").equals("none") || oldJournal.optBoolean("unresolved",true))
            throw new IOException("VPN_FIREWALL_PREVIOUS_COMMAND_UNRESOLVED token=" + previous.getName());
        JSONObject old = ownedJson(new File(previous,"request.json"),uid);
        if (!old.getString("token").equals(previous.getName()) || old.getInt("uid") != uid ||
            !old.getString("package").equals(current.getString("package")) || !old.getString("boot").equals(current.getString("boot")))
            throw new IOException("VPN_FIREWALL_PREVIOUS_OWNER_INVALID");
        String name = old.getString("interface");
        if (!name.matches("tun[0-9]{1,5}")) throw new IOException("VPN_FIREWALL_PREVIOUS_INTERFACE_INVALID");
        int oldPid = old.getInt("appPid");
        if (oldPid <= 0 || !old.getString("appStart").matches("[0-9]+")) throw new IOException("VPN_FIREWALL_PREVIOUS_PROCESS_INVALID");
        // Only ENOENT/ESRCH prove death. Permission errors and unreadable process identity refuse recovery.
        boolean gone = false;
        try {
            StructStat proc = Os.stat("/proc/" + oldPid);
            String procStat = PioneerVpnPermissionHelper.readFile(new File("/proc/" + oldPid + "/stat"),8192);
            String start = procStat.substring(procStat.lastIndexOf(") ") + 2).split(" ")[19];
            gone = proc.st_uid != uid || !old.getString("appStart").equals(start);
        } catch (android.system.ErrnoException error) {
            if (error.errno != OsConstants.ENOENT && error.errno != OsConstants.ESRCH) throw error;
            gone = true;
        } catch (FileNotFoundException error) {
            // A stat/read race needs a second kernel lookup; never infer death from a Java permission error.
            try { Os.stat("/proc/" + oldPid + "/stat"); }
            catch (android.system.ErrnoException lookup) {
                if (lookup.errno != OsConstants.ENOENT && lookup.errno != OsConstants.ESRCH) throw lookup;
                gone = true;
            }
        }
        if (!gone) throw new IOException("VPN_FIREWALL_PREVIOUS_APP_STILL_ALIVE token=" + previous.getName());
        try (FileOutputStream stop = PioneerRootFiles.output(new File(previous,"stop").getPath(),OsConstants.O_CREAT | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW,0600)) {
            stop.write(previous.getName().getBytes("US-ASCII")); stop.getFD().sync();
        }
        List<String> errors = PioneerInterfaceFirewall.recover(name,previous.getName(),argv -> PioneerRootCommand.run(argv,1200),
            (pending,unresolved) -> writeJournal(previous,old,"ACTIVE",pending,unresolved));
        if (!errors.isEmpty()) throw new IOException("VPN_FIREWALL_PREVIOUS_RECOVERY_FAILED token=" + previous.getName() + " " + errors);
        writeJournal(previous,old,"RELEASED","none",false);
        org.json.JSONArray recovered = status.optJSONArray("recovered");
        if (recovered == null) { recovered = new org.json.JSONArray(); status.put("recovered",recovered); }
        if (recovered.length() < 8) recovered.put(new JSONObject().put("token",previous.getName()).put("interface",name).put("oldAppPid",oldPid).put("oldJournal",oldJournal));
    }

    private static void publish(File dir,JSONObject status,String state,String error) throws Exception {
        status.put("state",state).put("updated",SystemClock.elapsedRealtime());
        if(error != null) status.put("error",error);
        PioneerVpnPermissionHelper.publish(dir,status,!state.equals("READY"));
    }
    private static void writeJournal(File dir,JSONObject request,String state,String pending,boolean unresolved) throws Exception {
        JSONObject data = new JSONObject().put("boot",request.getString("boot")).put("state",state).put("pending",pending).put("unresolved",unresolved);
        try(FileOutputStream out = PioneerRootFiles.output(new File(dir,"firewall-journal.json").getPath(),OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW,0600)) {
            out.write(data.toString().getBytes("UTF-8")); out.getFD().sync(); Os.fchown(out.getFD(),request.getInt("uid"),request.getInt("uid"));
        }
    }
}
