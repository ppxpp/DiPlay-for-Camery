package com.shilapi.xcertplay.pioneer;

/** Isolate the firmware JNI from the app process. Use the same API as the OEM debug menu. */
public final class PioneerAdbHelper {
    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(12000); } catch (InterruptedException ignored) { }
            System.out.println("DIPLAY_ADB_RESULT=ERROR TIMEOUT");
            System.out.flush();
            System.exit(2);
        }, "adb-toggle-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (android.os.Process.myUid() != 0 || args.length != 1 ||
                !("0".equals(args[0]) || "1".equals(args[0]))) throw new IllegalArgumentException();
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
            Class<?> type = Class.forName("jp.pioneer.ceam.widget.DebugModeServiceProxy.DebugModeServiceProxy");
            Object instance = type.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method toggle = type.getMethod("setADBonUSB", int.class);
            boolean accepted = Boolean.TRUE.equals(toggle.invoke(instance,
                "0".equals(args[0]) ? 0 : 1));
            System.out.println("DIPLAY_ADB_RESULT=" + (accepted ? "OK" : "ERROR"));
            System.out.flush();
            System.exit(accepted ? 0 : 1);
        } catch (Throwable error) {
            System.out.println("DIPLAY_ADB_RESULT=ERROR " + error.getClass().getSimpleName());
            System.out.flush();
            System.exit(1);
        }
    }
}
