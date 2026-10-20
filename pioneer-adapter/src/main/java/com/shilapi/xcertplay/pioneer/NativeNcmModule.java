package com.shilapi.xcertplay.pioneer;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Fixed, audited b819 module artifacts. Never unload or replace a live kernel module. */
final class NativeNcmModule {
    private static final String LOADER_ASSET = "assets/camry_ncm/init_module_armv7";
    private static final String LOADER_HASH = "6591233307b0f21b4d0bd91a3fa95e305b1f61e604baf3a0641796bfc34ed196";
    static final class Variant {
        final String key, kernel, hash, buildId;
        Variant(String key, String kernel, String hash, String buildId) {
            this.key = key; this.kernel = kernel; this.hash = hash; this.buildId = buildId;
        }
    }
    private static String diagnosticValue(String value) {
        String clean = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 180 ? clean : clean.substring(0, 180);
    }
    static Variant select(String version, String fingerprint, String kernel, int sdk, String machine) throws IOException {
        Variant v;
        if (version.equals("1.000062_1")) v = new Variant("0062", "4.9.17-g2b63fe51",
            "3810093aa26a0417d51e3470ccbd2ac3854cb79b15141f5e8d3317fca19063f1", "c52324939a8372cf53d6e2e7b237ae8f478889febf4640f126f13e4716f6e21a");
        // b819's exact 0132 mapping, plus the separately verified 152 kernel-compatible firmware.
        else if (version.equals("1.000132_1") || version.equals("1.000132_5") || version.equals("1.000152_1"))
            v = new Variant("0132", "4.9.17-gbd3a8122",
            "a349ad4e1bd79f12a4378453d1fecb3da2756f385583a25eee8a15b088e1750d", "e93761405abac06c5058bd48049df4c8fe2032f20125f1c2163e49ec7f1f2207");
        else throw new IOException("UNSUPPORTED_NCM_FIRMWARE version=" + diagnosticValue(version) +
            " fingerprint=" + diagnosticValue(fingerprint) + " kernel=" + diagnosticValue(kernel) +
            " sdk=" + sdk + " machine=" + diagnosticValue(machine));
        String revision = version.equals("1.000132_5") ? "5" : "1";
        if (sdk != 25 || !machine.equals("armv7l") || !kernel.equals(v.kernel) ||
            !fingerprint.equals("Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/" + revision + ":user/release-keys"))
            throw new IOException("UNSUPPORTED_NCM_KERNEL version=" + diagnosticValue(version) +
                " fingerprint=" + diagnosticValue(fingerprint) + " kernel=" + diagnosticValue(kernel) +
                " sdk=" + sdk + " machine=" + diagnosticValue(machine));
        return v;
    }
    interface Guard { void check() throws Exception; }
    static void ensureLoaded(String apk, String boot, Guard guard) throws Exception {
        String version = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.product.version");
        Variant v = select(version, Build.FINGERPRINT, Os.uname().release, Build.VERSION.SDK_INT, Os.uname().machine);
        guard.check();
        File root = new File("/data/diplay-ncm-module-v1");
        try { Os.mkdir(root.getPath(), 0700); } catch (android.system.ErrnoException e) { if (e.errno != OsConstants.EEXIST) throw e; }
        StructStat stat = Os.lstat(root.getPath());
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != 0 || (stat.st_mode & 0777) != 0700)
            throw new IOException("INVALID_MODULE_ROOT");
        // Root-owned cross-application lock also fences late or unconfirmed insmod children.
        try (FileOutputStream lockFile = new FileOutputStream(Os.open(new File(root, "lock").getPath(),
                OsConstants.O_CREAT | OsConstants.O_RDWR | OsConstants.O_NOFOLLOW, 0600));
             java.nio.channels.FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("NCM_MODULE_LOADER_BUSY");
            guard.check();
            File journal = new File(root, "journal");
            if (journal.exists()) {
                String prior = text(journal);
                if (!prior.matches("[0-9a-f-]{36} (PENDING|READY|FAILED)"))
                    throw new IOException("NCM_MODULE_LOAD_UNCONFIRMED; reboot head unit");
                if (prior.equals(boot + " PENDING")) {
                    if (loadChildRunning()) throw new IOException("NCM_MODULE_LOAD_UNCONFIRMED; loader child is still running");
                    if (new File("/sys/module/cdc_ncm").exists()) {
                        requireLoaded(v);
                        write(journal, boot + " READY");
                    } else {
                        // The previous app process died before any loader child could start.
                        write(journal, boot + " FAILED");
                    }
                }
            }
            if (new File("/sys/module/cdc_ncm").exists()) { requireLoaded(v); return; }
            if (!boot.matches("[0-9a-f-]{36}")) throw new IOException("INVALID_MODULE_BOOT");
            File toybox = new File("/system/bin/toybox");
            if (!Os.readlink("/system/bin/insmod").equals("toybox") || Os.stat(toybox.getPath()).st_uid != 0 ||
                !hash(toybox).equals("6007d0d6deae3514588a858c92028686400f07f8086cc23e6ef26d7c17a23da0"))
                throw new IOException("UNSUPPORTED_MODULE_LOADER");
            File module = new File(root, "cdc_ncm_" + v.key + ".ko");
            // Extract directly into a root-private file; no app-writable module path reaches insmod.
            try (ZipFile zip = new ZipFile(apk)) {
                java.util.zip.ZipEntry entry = zip.getEntry("assets/camry_ncm/" + module.getName());
                if (entry == null) throw new IOException("MODULE_ASSET_MISSING");
                writeExecutableSource(zip, entry, module, 46804);
            }
            if (module.length() != 46804 || !hash(module).equals(v.hash)) throw new IOException("MODULE_HASH_MISMATCH");
            File loader = new File(root, "init-module-armv7");
            try (ZipFile zip = new ZipFile(apk)) {
                java.util.zip.ZipEntry entry = zip.getEntry(LOADER_ASSET);
                if (entry == null) throw new IOException("MODULE_LOADER_ASSET_MISSING");
                writeExecutableSource(zip, entry, loader, 1024 * 1024);
            }
            if (!hash(loader).equals(LOADER_HASH)) throw new IOException("MODULE_LOADER_HASH_MISMATCH");
            Os.chmod(loader.getPath(), 0500);
            guard.check();
            write(journal, boot + " PENDING");
            try { guard.check(); } catch (Exception cancelled) { write(journal, boot + " FAILED"); throw cancelled; }
            // Keep PENDING on all unconfirmed outcomes. A reboot is required before another load.
            NativeNcmFirewall.Result result = NativeNcmCommand.run(
                new String[]{loader.getPath(), module.getPath(), "apple_no_status_control=3"}, 5000);
            if (result.exit != 0) {
                guard.check();
                NativeNcmFirewall.Result toyboxResult = NativeNcmCommand.run(
                    new String[]{"/system/bin/insmod", module.getPath(), "apple_no_status_control=3"}, 5000);
                result = new NativeNcmFirewall.Result(toyboxResult.exit,
                    "init_module=" + result.output + "; toybox=" + toyboxResult.output);
            }
            if (result.exit != 0) { write(journal, boot + " FAILED"); throw new IOException("NCM_INSMOD_FAILED: " + result.output); }
            requireLoaded(v);
            write(journal, boot + " READY");
        }
    }
    private static boolean loadChildRunning() {
        long self = android.os.Process.myPid();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return true;
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9]+") || Long.parseLong(entry.getName()) == self) continue;
            try {
                String command = text(new File(entry, "cmdline")).replace('\u0000', ' ');
                if (command.contains("/system/bin/insmod") || command.contains("init-module-armv7"))
                    return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
    private static void writeExecutableSource(ZipFile zip, java.util.zip.ZipEntry entry, File target, int maximum) throws Exception {
        FileDescriptor fd = Os.open(target.getPath(),
            OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600);
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maximum) throw new IOException("MODULE_ASSET_SIZE");
                Os.write(fd, buffer, 0, n);
            }
            Os.fsync(fd);
        } finally {
            Os.close(fd);
        }
    }
    private static void requireLoaded(Variant v) throws Exception {
        File module = new File("/sys/module/cdc_ncm");
        if (!text(new File(module, "initstate")).equals("live") ||
            !text(new File(module, "srcversion")).equals("2BA99E0A92611E1D326D33E") ||
            !hash(new File(module, "notes/.note.gnu.build-id")).equals(v.buildId) ||
            !text(new File(module, "parameters/apple_no_status_control")).equals("3") ||
            !new File("/sys/bus/usb/drivers/cdc_ncm").isDirectory())
            throw new IOException("LOADED_MODULE_MISMATCH; unplug phone and restart head unit");
    }
    private static String text(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[256]; int n;
            while ((n = in.read(b)) != -1) { if (out.size() + n > 4096) throw new IOException("Module state too large"); out.write(b, 0, n); }
            return out.toString("UTF-8").trim();
        }
    }
    private static void write(File file, String value) throws Exception {
        File temp = new File(file.getParentFile(), "journal.tmp");
        try (FileOutputStream out = new FileOutputStream(Os.open(temp.getPath(),
            OsConstants.O_CREAT | OsConstants.O_TRUNC | OsConstants.O_WRONLY | OsConstants.O_NOFOLLOW, 0600))) {
            out.write(value.getBytes("UTF-8")); out.getFD().sync();
        }
        Os.rename(temp.getPath(), file.getPath());
        java.io.FileDescriptor directory = Os.open(file.getParent(), OsConstants.O_RDONLY, 0);
        try { if (!OsConstants.S_ISDIR(Os.fstat(directory).st_mode)) throw new IOException("Invalid module directory"); Os.fsync(directory); } finally { Os.close(directory); }
    }
    private static String hash(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) hash.update(b, 0, n); }
        StringBuilder hex = new StringBuilder(); for (byte b : hash.digest()) hex.append(String.format(Locale.US, "%02x", b & 255));
        return hex.toString();
    }
}
