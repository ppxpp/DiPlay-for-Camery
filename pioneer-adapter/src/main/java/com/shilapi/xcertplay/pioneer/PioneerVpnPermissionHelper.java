package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.util.Arrays;

/** Fixed INSPECT/GRANT tasks for the requesting package only. Does not load any USB driver. */
public final class PioneerVpnPermissionHelper {
    public static void main(String[] args) {
        if (args.length != 1 || Process.myUid() != 0) return;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
            System.exit(3);
        }, "vpn-grant-deadline");
        watchdog.setDaemon(true); watchdog.start();
        File directory = null;
        JSONObject result = new JSONObject();
        long entered = SystemClock.elapsedRealtime();
        try {
            File candidate = new File(args[0]).getCanonicalFile();
            requireDirectory(candidate.getPath());
            StructStat dir = Os.lstat(candidate.getPath());
            requireFile(dir, true, dir.st_uid);
            File input = new File(candidate, "request.json");
            JSONObject request;
            try (FileInputStream stream = new FileInputStream(Os.open(input.getPath(),
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0))) {
                requireFile(Os.fstat(stream.getFD()), false, dir.st_uid);
                request = new JSONObject(read(stream, 8192));
            }
            String token = request.getString("token"), action = request.getString("action");
            requireAction(action);
            if (!token.equals(candidate.getName())) throw new IOException("VPN_TOKEN_MISMATCH");
            String pkg = request.getString("package");
            int uid = request.getInt("uid"), user = uid / 100000;
            if (!pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || uid != dir.st_uid || uid % 100000 < 10000)
                throw new IOException("VPN_INVALID_PACKAGE_OWNER");
            Context context = systemContext();
            ApplicationInfo app = (ApplicationInfo) context.getPackageManager().getClass()
                .getMethod("getApplicationInfoAsUser", String.class, int.class, int.class)
                .invoke(context.getPackageManager(), pkg, 0, user);
            requirePackage(uid, app.uid, request.getString("apk"), app.sourceDir,
                System.getenv("CLASSPATH"), candidate.getParentFile().getParentFile(), new File(app.dataDir, "files"));
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length != 1 || !packages[0].equals(pkg))
                throw new IOException("VPN_SHARED_UID_UNSUPPORTED");
            directory = candidate;
            result.put("token", token).put("helperUid", Process.myUid()).put("helperPid", Process.myPid())
                .put("package", pkg).put("targetUid", uid).put("userId", user).put("action", action)
                .put("helperStartupMs", entered - request.getLong("requestedAtMs"));
            NativeNcmModule.Guard guard = () -> {
                requireLive(request, SystemClock.elapsedRealtime());
                if (new File(candidate, "cancel").exists()) throw new IOException("VPN_TASK_CANCELLED");
                if (!request.getString("boot").equals(readFile(new File("/proc/sys/kernel/random/boot_id"), 128).trim()))
                    throw new IOException("VPN_BOOT_CHANGED");
                int pid = request.getInt("appPid");
                String stat = readFile(new File("/proc/" + pid + "/stat"), 8192);
                if (Os.stat("/proc/" + pid).st_uid != uid ||
                    !request.getString("appStart").equals(stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19]))
                    throw new IOException("VPN_APP_PROCESS_CHANGED");
            };
            guard.check();
            if (!PioneerUsbPolicy.INSTANCE.matchesDevice()) throw new IOException("VPN_UNSUPPORTED_DEVICE");
            PioneerNativeNcmPrewarm.requireSupportedFirmware(); // Catalog check only; never ensureLoaded().
            result.put("selfVpnOwner", requireNoOtherVpn(pkg, user));
            guard.check();
            String before = query(pkg, user, guard);
            result.put("appOpBefore", before);
            if (action.equals("GRANT")) {
                guard.check();
                requireNoOtherVpn(pkg, user);
                guard.check();
                // Persist the uncertain-mutation fence before launching an appops child.
                try (FileOutputStream marker = new FileOutputStream(Os.open(new File(candidate, "mutation").getPath(),
                        OsConstants.O_CREAT | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
                    marker.write(token.getBytes("US-ASCII")); marker.getFD().sync();
                    Os.fchown(marker.getFD(), uid, uid);
                }
                guard.check();
                NativeNcmFirewall.Result command;
                try { command = NativeNcmCommand.run(arguments("set", pkg, user), 1500); }
                catch (NativeNcmFirewall.CompletionUnknown uncertain) {
                    result.put("unresolvedChild", !uncertain.childStopped);
                    throw uncertain;
                }
                result.put("setExit", command.exit).put("setOutput", command.output);
                if (command.exit != 0) throw new IOException("VPN_APPOP_SET_FAILED exit=" + command.exit);
                guard.check();
                String after = query(pkg, user, guard);
                result.put("appOpAfter", after).put("appOpAllowed", isAllowed(after));
                if (!isAllowed(after)) throw new IOException("VPN_APPOP_READBACK_NOT_ALLOWED");
            }
            result.put("code", "COMPLETED");
        } catch (Throwable error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException) error).getTargetException() : error;
            try {
                if (cause instanceof NativeNcmFirewall.CompletionUnknown)
                    result.put("unresolvedChild", !((NativeNcmFirewall.CompletionUnknown) cause).childStopped);
                result.put("code", "ERROR").put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            catch (Exception ignored) { }
        }
        // All bounded command calls have returned. CompletionUnknown explicitly records whether
        // its child was reaped; a watchdog/process death never publishes this terminal evidence.
        try { result.put("childrenFinished", !result.optBoolean("unresolvedChild")); } catch (Exception ignored) { }
        try { result.put("helperOperationMs", SystemClock.elapsedRealtime() - entered); } catch (Exception ignored) { }
        if (directory != null) try { publish(directory, result); } catch (Throwable ignored) { }
        System.exit(0);
    }

    static void requireAction(String action) throws IOException {
        if (!Arrays.asList("INSPECT", "GRANT").contains(action)) throw new IOException("VPN_UNSUPPORTED_ACTION");
    }
    static void requireDirectory(String path) throws IOException {
        if (!path.matches("/data/(?:data|user/[0-9]+)/[A-Za-z0-9_.]+/files/vpn-permission/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IOException("VPN_INVALID_TASK_DIRECTORY");
    }
    static void requireFile(StructStat stat, boolean directory, int uid) throws IOException {
        if (uid % 100000 < 10000 || stat.st_uid != uid ||
            (directory ? !OsConstants.S_ISDIR(stat.st_mode) : !OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1) ||
            (stat.st_mode & 0777) != (directory ? 0700 : 0600) || (!directory && (stat.st_size <= 0 || stat.st_size > 8192)))
            throw new IOException("VPN_INVALID_REQUEST_OWNER_OR_MODE");
    }
    static void requirePackage(int uid, int actualUid, String apk, String actualApk, String classpath,
                               File files, File actualFiles) throws IOException {
        if (uid != actualUid || !apk.equals(actualApk) || !apk.equals(classpath) ||
            !files.getCanonicalFile().equals(actualFiles.getCanonicalFile()))
            throw new IOException("VPN_PACKAGE_IDENTITY_MISMATCH");
    }
    static void requireLive(JSONObject request, long now) throws Exception {
        long started = request.getLong("requestedAtMs"), deadline = request.getLong("deadline");
        if (started < 0 || now < started || deadline <= now || deadline - started > 6000 || deadline <= started)
            throw new IOException("VPN_TASK_EXPIRED");
    }
    static String[] arguments(String action, String pkg, int user) {
        if (!Arrays.asList("get", "set").contains(action) || user < 0 ||
            !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid appops task");
        return action.equals("set")
            ? new String[]{"/system/bin/appops", "set", "--user", String.valueOf(user), pkg, "ACTIVATE_VPN", "allow"}
            : new String[]{"/system/bin/appops", "get", "--user", String.valueOf(user), pkg, "ACTIVATE_VPN"};
    }
    static boolean isAllowed(String output) {
        return java.util.regex.Pattern.compile("(?im)^\\s*(?:ACTIVATE_VPN|android:activate_vpn):\\s*allow(?:[;\\s]|$)")
            .matcher(output).find();
    }
    private static String query(String pkg, int user, NativeNcmModule.Guard guard) throws Exception {
        guard.check();
        NativeNcmFirewall.Result result = NativeNcmCommand.run(arguments("get", pkg, user), 1000);
        if (result.exit != 0) throw new IOException("VPN_APPOP_QUERY_FAILED exit=" + result.exit + " " + result.output);
        return result.output;
    }
    private static boolean requireNoOtherVpn(String pkg, int user) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class)
            .invoke(null, "connectivity");
        if (binder == null) throw new IOException("VPN_CONNECTIVITY_SERVICE_MISSING");
        Class<?> api = Class.forName("android.net.IConnectivityManager");
        Object service = Class.forName("android.net.IConnectivityManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object config = api.getMethod("getVpnConfig", int.class).invoke(service, user);
        if (config != null && !pkg.equals(config.getClass().getField("user").get(config)))
            throw new IOException("VPN_ACTIVE_OTHER_APP");
        // app_process has no ActivityManager-registered ApplicationThread. A ContentResolver
        // query fails on the target API25 firmware even as root; query the VPN owner directly
        // through ConnectivityService instead, using the same validated target user.
        String alwaysOn = (String) api.getMethod("getAlwaysOnVpnPackage", int.class).invoke(service, user);
        if (alwaysOn != null && !alwaysOn.isEmpty() && !pkg.equals(alwaysOn))
            throw new IOException("VPN_ALWAYS_ON_OTHER_APP");
        return config != null;
    }
    private static Context cachedContext;
    static Context systemContext() throws Exception {
        if (cachedContext != null) return cachedContext;
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object instance = type.getMethod("systemMain").invoke(null);
        cachedContext = (Context) type.getMethod("getSystemContext").invoke(instance);
        return cachedContext;
    }
    static String readFile(File file, int limit) throws Exception {
        try (InputStream in = new FileInputStream(file)) { return read(in, limit); }
    }
    static String read(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[1024]; int count;
        while ((count = in.read(chunk)) >= 0) {
            if (bytes.size() + count > limit) throw new IOException("VPN_INPUT_TOO_LARGE");
            bytes.write(chunk, 0, count);
        }
        return bytes.toString("UTF-8");
    }
    static void publish(File dir, JSONObject result) throws Exception { publish(dir, result, true); }
    static void publish(File dir, JSONObject result, boolean durable) throws Exception {
        StructStat owner = Os.lstat(dir.getPath());
        File temp = new File(dir, "result.tmp");
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
            out.write(result.toString().getBytes("UTF-8")); if (durable) out.getFD().sync();
            Os.fchown(out.getFD(), owner.st_uid, owner.st_gid);
        }
        Os.rename(temp.getPath(), new File(dir, "result.json").getPath());
    }
}
