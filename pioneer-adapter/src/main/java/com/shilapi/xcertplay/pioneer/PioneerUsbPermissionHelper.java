package com.shilapi.xcertplay.pioneer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Xml;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.util.Objects;

/** Fixed diagnostic operations only; never changes USB configuration or opens a data interface. */
public final class PioneerUsbPermissionHelper {
    public static void main(String[] args) {
        long helperEntryMs = SystemClock.elapsedRealtime();
        if (args.length != 1 || Process.myUid() != 0) return;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
            System.exit(3);
        }, "usb-permission-probe-deadline");
        watchdog.setDaemon(true); watchdog.start();
        File directory = null;
        JSONObject result = new JSONObject();
        try {
            File candidate = new File(args[0]).getCanonicalFile();
            if (!candidate.getPath().matches("/data/(?:data|user/0)/[A-Za-z0-9_.]+/files/usb-permission-probe/[0-9a-f-]{36}"))
                throw new IOException("INVALID_PROBE_DIRECTORY");
            StructStat stat = Os.lstat(candidate.getPath());
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid < 10000 || (stat.st_mode & 0777) != 0700)
                throw new IOException("INVALID_PROBE_OWNER");
            File input = new File(candidate, "request.json");
            StructStat file = Os.lstat(input.getPath());
            if (!OsConstants.S_ISREG(file.st_mode) || file.st_uid != stat.st_uid || file.st_nlink != 1 || (file.st_mode & 0777) != 0600)
                throw new IOException("INVALID_PROBE_REQUEST");
            directory = candidate;
            JSONObject request = new JSONObject(read(input, 8192));
            String action = request.getString("action"), pkg = request.getString("package");
            requireAction(action);
            String token = request.getString("token");
            if (!token.equals(directory.getName())) throw new IOException("INVALID_PROBE_TOKEN");
            result.put("token", token).put("helperUid", Process.myUid()).put("helperPid", Process.myPid())
                .put("helperStartupMs", helperEntryMs - request.optLong("requestedAtMs", helperEntryMs));
            Context context = systemContext();
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (app.uid != stat.st_uid || app.uid != request.getInt("uid") || app.uid / 100000 != 0 ||
                !directory.getParentFile().getParentFile().getCanonicalFile().equals(new File(app.dataDir, "files").getCanonicalFile()))
                throw new IOException("PACKAGE_UID_MISMATCH");
            Guard guard = () -> {
                if (new File(candidate, "cancel").exists() || SystemClock.elapsedRealtime() >= request.getLong("deadline"))
                    throw new IOException("PROBE_CANCELLED_OR_EXPIRED");
                if (!request.getString("boot").equals(read(new File("/proc/sys/kernel/random/boot_id"), 128).trim()))
                    throw new IOException("BOOT_CHANGED");
                int pid = request.getInt("appPid");
                if (Os.stat("/proc/" + pid).st_uid != app.uid ||
                    !request.getString("appStart").equals(processStart(pid))) throw new IOException("APP_PROCESS_CHANGED");
            };
            guard.check();
            PioneerNativeNcmPrewarm.requireSupportedFirmware(); // Read-only firmware check, no NCM load.
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (usb == null) throw new IOException("USB_SERVICE_MISSING");
            UsbDevice device = current(usb, request);
            Object service = usbService();
            result.put("usbServiceAccessible", true).put("targetUid", app.uid)
                .put("identityHash", identity(device));
            try { result.put("defaultBefore", defaultFor(device)); }
            catch (Exception denied) { result.put("defaultBefore", "UNKNOWN").put("defaultReadError", denied.getClass().getSimpleName()); }
            if (action.equals("GRANT") || action.equals("AUTO_GRANT")) {
                guard.check(); device = current(usb, request);
                invoke("grantDevicePermission", service, new Class<?>[]{UsbDevice.class, int.class}, device, app.uid);
                result.put("grantCallSucceeded", true);
                if (action.equals("AUTO_GRANT")) {
                    // Persist only our own default. Default selection failure must not undo a valid grant.
                    try {
                        guard.check(); device = current(usb, request);
                        requireOwnDefault(defaultFor(device), pkg);
                        guard.check();
                        invoke("setDevicePackage", service, new Class<?>[]{UsbDevice.class, String.class, int.class}, device, pkg, 0);
                        result.put("defaultSetCallSucceeded", true);
                    } catch (Exception unavailable) {
                        result.put("defaultSetError", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage());
                    }
                }
            } else if (action.equals("CLEAR_DEFAULT") || action.equals("SET_DEFAULT")) {
                String existing = result.getString("defaultBefore");
                requireOwnDefault(existing, pkg);
                guard.check(); device = current(usb, request);
                // Recheck immediately before mutation; never clear a newly observed other-app default.
                requireOwnDefault(defaultFor(device), pkg);
                guard.check();
                boolean setting = action.equals("SET_DEFAULT");
                invoke("setDevicePackage", service, new Class<?>[]{UsbDevice.class, String.class, int.class}, device, setting ? pkg : null, 0);
                result.put(setting ? "defaultSetCallSucceeded" : "defaultClearCallSucceeded", true);
                String expected = setting ? pkg : "NONE";
                String after = defaultFor(current(usb, request));
                long until = Math.min(SystemClock.elapsedRealtime() + 1200, request.getLong("deadline") - 150);
                while (!after.equals(expected) && SystemClock.elapsedRealtime() < until) {
                    Thread.sleep(100);
                    guard.check();
                    after = defaultFor(current(usb, request));
                }
                result.put("defaultAfter", after)
                    .put(setting ? "defaultSetVerified" : "defaultClearVerified", after.equals(expected));
            }
            result.put("code", "COMPLETED");
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException ? ((InvocationTargetException) error).getTargetException() : error;
            try { result.put("code", "ERROR").put("error", cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage())); }
            catch (Exception ignored) { }
        }
        try { result.put("helperOperationMs", SystemClock.elapsedRealtime() - helperEntryMs); } catch (Exception ignored) { }
        if (directory != null) try { publish(directory, result); } catch (Throwable ignored) { }
        System.exit(0);
    }

    /** Reuse the already running module-prewarm process; never start a second ART process at boot. */
    static JSONObject grantDuringPrewarm(String apk, int ownerUid, NativeNcmModule.Guard guard) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
            if (pending.get()) System.exit(4);
        }, "usb-prewarm-grant-deadline");
        watchdog.setDaemon(true); watchdog.start();
        JSONObject result = new JSONObject();
        long started = SystemClock.elapsedRealtime();
        try {
            guard.check();
            Context context = systemContext();
            String[] packages = context.getPackageManager().getPackagesForUid(ownerUid);
            if (packages == null || packages.length != 1 || ownerUid < 10000 || ownerUid / 100000 != 0)
                throw new IOException("PREWARM_PACKAGE_UID_AMBIGUOUS");
            String pkg = packages[0];
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (app.uid != ownerUid || !app.sourceDir.equals(apk)) throw new IOException("PREWARM_PACKAGE_UID_MISMATCH");
            Context target = context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
            if (target.getSharedPreferences("pioneer-debug", 0).getBoolean("usb_permission_probe_hold", false))
                return result.put("code", "HELD");
            UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbDevice device = null;
            for (UsbDevice candidate : usb.getDeviceList().values()) if (candidate.getVendorId() == 0x05ac) {
                if (device != null) throw new IOException("PREWARM_IPHONE_AMBIGUOUS");
                device = candidate;
            }
            if (device == null) return result.put("code", "NO_IPHONE");
            JSONObject selected = new JSONObject().put("node", device.getDeviceName()).put("deviceId", device.getDeviceId())
                .put("vid", device.getVendorId()).put("pid", device.getProductId()).put("identityHash", identity(device));
            Object service = usbService();
            guard.check(); device = current(usb, selected);
            invoke("grantDevicePermission", service, new Class<?>[]{UsbDevice.class, int.class}, device, app.uid);
            result.put("grantCallSucceeded", true).put("targetUid", app.uid).put("node", device.getDeviceName());
            try {
                guard.check(); device = current(usb, selected);
                requireOwnDefault(defaultFor(device), pkg);
                guard.check();
                invoke("setDevicePackage", service, new Class<?>[]{UsbDevice.class, String.class, int.class}, device, pkg, 0);
                result.put("defaultSetCallSucceeded", true);
            } catch (Exception unavailable) { result.put("defaultSetError", unavailable.getClass().getSimpleName()); }
            return result.put("code", "GRANT_CALLED_APP_MUST_VERIFY");
        } finally {
            pending.set(false);
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started);
        }
    }

    interface Guard { void check() throws Exception; }
    static void requireAction(String action) throws IOException {
        if (!action.equals("INSPECT") && !action.equals("GRANT") && !action.equals("CLEAR_DEFAULT") && !action.equals("SET_DEFAULT") && !action.equals("AUTO_GRANT"))
            throw new IOException("UNSUPPORTED_PROBE_ACTION");
    }
    public static String identity(UsbDevice device) throws Exception {
        if (device.getVendorId() != 0x05ac || !"iPhone".equals(device.getProductName())) throw new IOException("NOT_IPHONE");
        String serial = device.getSerialNumber();
        if (serial == null || serial.isEmpty() || serial.length() > 128) throw new IOException("STABLE_IDENTITY_UNAVAILABLE");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(device.getVendorId()); out.writeInt(device.getProductId());
            out.writeInt(device.getDeviceClass()); out.writeInt(device.getDeviceSubclass()); out.writeInt(device.getDeviceProtocol());
            out.writeUTF(String.valueOf(device.getManufacturerName())); out.writeUTF(device.getProductName()); out.writeUTF(serial);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        StringBuilder value = new StringBuilder();
        for (byte b : digest) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }
    static UsbDevice current(UsbManager usb, JSONObject request) throws Exception {
        UsbDevice current = usb.getDeviceList().get(request.getString("node"));
        if (current == null || current.getDeviceId() != request.getInt("deviceId") ||
            current.getVendorId() != request.getInt("vid") || current.getProductId() != request.getInt("pid") ||
            !identity(current).equals(request.getString("identityHash"))) throw new IOException("DEVICE_CHANGED_OR_DETACHED");
        return current;
    }
    private static Context systemContext() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        return (Context) thread.getMethod("getSystemContext").invoke(instance);
    }
    private static Object usbService() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", String.class).invoke(null, "usb");
        if (binder == null) throw new IOException("USB_BINDER_MISSING");
        return Class.forName("android.hardware.usb.IUsbManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private static void invoke(String name, Object service, Class<?>[] types, Object... values) throws Exception {
        try { Class.forName("android.hardware.usb.IUsbManager").getMethod(name, types).invoke(service, values); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    private static String defaultFor(UsbDevice device) throws Exception {
        File defaults = new File("/data/system/users/0/usb_device_manager.xml");
        if (!defaults.exists()) return "NONE";
        return defaultFrom(read(defaults, 262144), device);
    }
    static void requireOwnDefault(String existing, String pkg) throws IOException {
        if (!existing.equals("NONE") && !existing.equals(pkg)) throw new IOException("DEFAULT_OWNED_BY_OTHER_APP_OR_UNKNOWN");
    }
    static String defaultFrom(String contents, UsbDevice device) throws Exception {
        XmlPullParser parser = Xml.newPullParser(); parser.setInput(new StringReader(contents));
        String pkg = null, found = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && parser.getName().equals("preference")) pkg = parser.getAttributeValue(null, "package");
            if (event == XmlPullParser.END_TAG && parser.getName().equals("preference")) pkg = null;
            if (event == XmlPullParser.START_TAG && parser.getName().equals("usb-device") && pkg != null && matchesFilter(parser, device)) {
                if (found != null && !found.equals(pkg)) return "MULTIPLE";
                found = pkg;
            }
        }
        return found == null ? "NONE" : found;
    }
    private static boolean matchesFilter(XmlPullParser p, UsbDevice d) {
        if (!(numberMatches(p, "vendor-id", d.getVendorId()) && numberMatches(p, "product-id", d.getProductId()) &&
            textMatches(p, "manufacturer-name", d.getManufacturerName()) && textMatches(p, "product-name", d.getProductName()) &&
            textMatches(p, "serial-number", d.getSerialNumber()))) return false;
        if (classMatches(p, d.getDeviceClass(), d.getDeviceSubclass(), d.getDeviceProtocol())) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            android.hardware.usb.UsbInterface iface = d.getInterface(i);
            if (classMatches(p, iface.getInterfaceClass(), iface.getInterfaceSubclass(), iface.getInterfaceProtocol())) return true;
        }
        return false;
    }
    private static boolean classMatches(XmlPullParser p, int cls, int sub, int protocol) {
        return numberMatches(p, "class", cls) && numberMatches(p, "subclass", sub) && numberMatches(p, "protocol", protocol);
    }
    private static boolean numberMatches(XmlPullParser p, String key, int actual) {
        String value = p.getAttributeValue(null, key);
        if (value == null || value.equals("-1")) return true;
        try { return Integer.decode(value) == actual; } catch (Exception e) { return false; }
    }
    private static boolean textMatches(XmlPullParser p, String key, String actual) {
        String value = p.getAttributeValue(null, key); return value == null || Objects.equals(value, actual);
    }
    private static String read(File file, int limit) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024]; int count;
            while ((count = in.read(bytes)) >= 0) {
                if (out.size() + count > limit) throw new IOException("PROBE_INPUT_TOO_LARGE");
                out.write(bytes, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
    private static String processStart(int pid) throws Exception {
        String stat = read(new File("/proc/" + pid + "/stat"), 8192);
        return stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19];
    }
    private static void publish(File directory, JSONObject value) throws Exception {
        StructStat owner = Os.lstat(directory.getPath());
        File temp = new File(directory, "result.tmp");
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(),
                OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_NOFOLLOW | OsConstants.O_WRONLY, 0600))) {
            out.write(value.toString().getBytes("UTF-8")); out.getFD().sync();
        }
        Os.chown(temp.getPath(), owner.st_uid, owner.st_gid); Os.chmod(temp.getPath(), 0600);
        Os.rename(temp.getPath(), new File(directory, "result.json").getPath());
    }
}
