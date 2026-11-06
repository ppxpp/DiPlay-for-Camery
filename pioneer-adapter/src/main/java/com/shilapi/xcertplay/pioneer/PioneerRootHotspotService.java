package com.shilapi.xcertplay.pioneer;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import android.os.SystemClock;

/** Fixed-purpose operations hosted inside the authenticated shared root runtime. */
public final class PioneerRootHotspotService {
    private PioneerRootHotspotService() {}
    private static final String HELPER = "com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeHelper";
    private static void owner(JSONObject owner, File files) throws Exception {
        if (Process.myUid()!=0 || Process.is64Bit() || owner.getInt("uid")<10000 ||
                Os.stat(files.getCanonicalPath()).st_uid!=owner.getInt("uid"))
            throw new SecurityException("Invalid root operation owner");
    }
    private static boolean enabled(JSONObject owner) {
        String mode=owner.optString("camryAdaptation","AUTO");
        return mode.equals("ON") || (mode.equals("AUTO") && PioneerUsbPolicy.INSTANCE.matchesModel());
    }
    private static File asset(String path, String name, JSONObject owner, File files) throws Exception {
        File file=new File(path);
        File expected=new File(files.getCanonicalFile().getParentFile(),"code_cache/"+name);
        if (!file.getAbsolutePath().equals(expected.getPath()) || !file.getCanonicalFile().equals(expected))
            throw new SecurityException("Invalid native asset path");
        owned(file,owner.getInt("uid"));
        return file;
    }
    private static void owned(File file,int uid) throws Exception {
        android.system.StructStat info=Os.lstat(file.getPath());
        if(info.st_uid!=uid || !OsConstants.S_ISREG(info.st_mode) || info.st_nlink!=1 || (info.st_mode&0022)!=0)
            throw new SecurityException("Helper file owner/type mismatch");
    }
    private static File scratch(File files,int uid,String prefix) throws Exception {
        File file=new File(files,prefix+UUID.randomUUID()+".txt");
        if(!file.createNewFile())throw new IllegalStateException("Scratch collision");
        Os.chmod(file.getPath(),0600); Os.chown(file.getPath(),uid,-1);
        return file;
    }
    private static String read(File file) throws Exception {
        if(file.length()>256*1024)throw new IllegalStateException("Helper output too large");
        try(FileInputStream input=new FileInputStream(file); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while((count=input.read(buffer))!=-1) {
                if(output.size()+count>256*1024)throw new IllegalStateException("Helper output too large");
                output.write(buffer,0,count);
            }
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static boolean exited(java.lang.Process child) {
        try { child.exitValue(); return true; } catch(IllegalThreadStateException running) { return false; }
    }
    private static void retire(File file) {
        for(String suffix:new String[]{"", ".cancel", ".running", ".running.tmp", ".tmp"})new File(file.getPath()+suffix).delete();
    }
    public static synchronized JSONObject warm(JSONObject payload,JSONObject owner,File files) throws Exception {
        owner(owner,files);
        if(!enabled(owner))throw new SecurityException("Camry adaptation required");
        Class.forName(HELPER,true,PioneerRootHotspotService.class.getClassLoader()).getMethod("warmShared").invoke(null);
        return new JSONObject().put("warmed",true);
    }
    static boolean hotspotAllowed(String action,String token,boolean authorized,boolean enabled) {
        return action!=null && token!=null && token.matches("[0-9a-f]{32}") && authorized &&
            Arrays.asList("PREPARE","PREPARE_LOCAL_ONLY","RESUME_LOCAL_ONLY","START","FIVE_GHZ","FIREWALL","STATUS","STOP","STOP_OWNED","RELEASE").contains(action) &&
            (enabled || action.equals("STOP_OWNED") || action.equals("RELEASE"));
    }
    public static synchronized JSONObject execute(JSONObject payload, JSONObject owner, File files, Callable<Boolean> current) throws Exception {
        owner(owner,files);
        String action=payload.getString("action"), token=payload.getString("token");
        if(!hotspotAllowed(action,token,payload.optBoolean("adaptationAuthorized"),enabled(owner)))
            throw new SecurityException("Invalid hotspot action or adaptation authorization");
        File runner=asset(payload.getString("runner"),"pioneer-command-runner",owner,files);
        String gate="-";
        if(payload.has("gate")) {
            if(!action.equals("PREPARE") && !action.equals("PREPARE_LOCAL_ONLY"))throw new SecurityException("Invalid AP gate action");
            gate=asset(payload.getString("gate"),"pioneer-ap-frequency-gate",owner,files).getPath();
        }
        if(!current.call())throw new InterruptedException("Hotspot cancelled");
        File result=scratch(files,owner.getInt("uid"),"pioneer-hotspot-"+token+"-");
        try {
            // The shared root process was loaded from the authenticated owner's APK.
            Class<?> helper=Class.forName(HELPER,true,PioneerRootHotspotService.class.getClassLoader());
            String[] args={action,owner.getString("package"),result.getPath(),token,gate,"immediate","camry-enabled",runner.getPath()};
            helper.getMethod("runShared",String[].class,Callable.class).invoke(null,args,current);
            return new JSONObject().put("text",read(result));
        } finally { retire(result); }
    }
    private static final CallbackLane callbacks = new CallbackLane();

    interface ChildStarter { java.lang.Process start() throws Exception; }

    /** This monitor stays held through teardown, including an unconfirmed-exit decision. */
    static final class CallbackLane {
        private boolean poisoned;

        private void requireHealthy() throws PioneerRootIpc.CompletionUnknown {
            if (poisoned) throw new PioneerRootIpc.CompletionUnknown("Callback lane poisoned: previous native child exit/output unconfirmed");
        }

        synchronized String run(ChildStarter starter, File result, PioneerRootCommand.Clock clock,
                PioneerRootCommand.Sleeper sleep, long executionMs, long cleanupMs) throws Exception {
            requireHealthy();
            java.lang.Process child = null;
            Thread drain = null;
            Interrupts interrupts = new Interrupts();
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Callback cancelled before launch");
                child = starter.start();
                // Discard continuously with fixed memory: a full stdout pipe must not stop the
                // native helper from reaching its cancellation checks or writing its result.
                final java.lang.Process observed = child;
                drain = new Thread(() -> {
                    try (java.io.InputStream input = observed.getInputStream()) {
                        byte[] bytes = new byte[4096];
                        while (input.read(bytes) != -1) { }
                    } catch (Exception ignored) { }
                }, "root-callback-output");
                drain.setDaemon(true); drain.start();
                child.getOutputStream().close();
                long until = clock.getAsLong() + executionMs;
                while (!exited(child)) {
                    if (clock.getAsLong() >= until) throw new IllegalStateException("Callback native task timed out");
                    sleep.sleep(Math.max(1, Math.min(25, until - clock.getAsLong())));
                }
                String text = read(result);
                if (!text.endsWith("complete=1\n")) throw new IllegalStateException("Callback native task incomplete");
                return text;
            } catch (InterruptedException cancelled) {
                interrupts.seen = true;
                throw cancelled;
            } finally {
                interrupts.capture();
                boolean confirmed = child == null;
                try {
                    if (child != null) {
                        if (!exited(child)) {
                            // Journal failure must not skip child termination either.
                            try (FileOutputStream cancel = new FileOutputStream(result.getPath()+".cancel")) {
                                cancel.write(1);
                            } catch (Exception ignored) { }
                            awaitCleanup(child, clock.getAsLong()+cleanupMs, clock, sleep, interrupts);
                            if (!exited(child)) {
                                PioneerRootCommand.stopChild(child, clock,
                                    millis -> interrupts.sleep(millis, clock, sleep));
                            }
                            if (!exited(child)) {
                                forceKill(child);
                                awaitCleanup(child, clock.getAsLong()+500, clock, sleep, interrupts);
                            }
                        }
                        boolean childExited = exited(child);
                        if (childExited && drain != null) {
                            long until = clock.getAsLong()+250;
                            while (drain.isAlive() && clock.getAsLong() < until)
                                interrupts.sleep(Math.min(10, until-clock.getAsLong()), clock, sleep);
                            // Do not accumulate blocked drain threads across callbacks either.
                        }
                        confirmed = childExited && (drain == null || !drain.isAlive());
                    }
                } catch (Throwable cleanupFailure) {
                    throw new PioneerRootIpc.CompletionUnknown("Callback teardown failed; lane poisoned", cleanupFailure);
                } finally {
                    // No exception or repeated interrupt may leave the lane reusable while a
                    // native child could still mutate the vehicle. Keep all journals if unsure.
                    try {
                        if (confirmed) retire(result);
                        else poisoned = true;
                    } finally {
                        interrupts.capture();
                        if (interrupts.seen) Thread.currentThread().interrupt();
                    }
                }
                if (!confirmed) throw new PioneerRootIpc.CompletionUnknown("Callback native child exit/output unconfirmed; lane poisoned");
            }
        }

        private static void awaitCleanup(java.lang.Process child, long until, PioneerRootCommand.Clock clock,
                PioneerRootCommand.Sleeper sleep, Interrupts interrupts) {
            while (!exited(child) && clock.getAsLong() < until)
                interrupts.sleep(Math.min(25, until-clock.getAsLong()), clock, sleep);
        }

        private static void forceKill(java.lang.Process child) {
            try {
                // API 26+; reflect to keep this class loadable on the Camry's older ART.
                java.lang.Process.class.getMethod("destroyForcibly").invoke(child);
                return;
            } catch (Exception ignored) { }
            try {
                // Android 23-25 ProcessImpl exposes the PID used by destroy().
                java.lang.reflect.Field pid = child.getClass().getDeclaredField("pid");
                pid.setAccessible(true);
                int value = pid.getInt(child);
                if (value > 1) Process.killProcess(value);
            } catch (Exception ignored) { }
        }
    }

    private static final class Interrupts {
        boolean seen;
        void capture() { seen |= Thread.interrupted(); }
        void sleep(long millis, PioneerRootCommand.Clock clock, PioneerRootCommand.Sleeper sleep) {
            long until = clock.getAsLong()+Math.max(0, millis);
            capture();
            while (true) {
                long remaining = until-clock.getAsLong();
                if (remaining <= 0) break;
                try { sleep.sleep(remaining); }
                catch (InterruptedException ignored) { seen = true; }
            }
            capture();
        }
    }

    public static JSONObject callback(JSONObject payload, JSONObject owner, File files) throws Exception {
        synchronized (callbacks) {
            callbacks.requireHealthy();
            owner(owner,files);
            String mode=payload.getString("mode");
            if(!PioneerCallbackPolicy.callbackAllowed(mode,enabled(owner),payload.optBoolean("cleanupAuthorized")))
                throw new SecurityException("Callback adaptation required");
            File binary=asset(payload.getString("binary"),"blueforce-callback-repair",owner,files);
            File result=scratch(files,owner.getInt("uid"),"pioneer-callback-");
            String text=callbacks.run(() -> new ProcessBuilder(binary.getPath(),mode,
                    Integer.toString(owner.getInt("uid")),result.getPath()).redirectErrorStream(true).start(),
                result, () -> SystemClock.elapsedRealtime(), Thread::sleep, 12_000, 4_000);
            return new JSONObject().put("text",text);
        }
    }
}
