package com.shilapi.xcertplay.btprobe;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import com.shilapi.xcertplay.pioneer.PioneerRootDispatcher;

/** Fixed-purpose Java facade reusing DiPlay's existing Kotlin root dispatcher.
 * Kotlin's internal visibility is compile-module-only; no duplicate Binder protocol.
 */
public final class PioneerRootCallbackLauncher {
    private PioneerRootCallbackLauncher() {}
    public static void launch(Context context, File binary, File result, String mode) throws Exception {
        if (!mode.equals("--audit") && !mode.equals("--repair") && !mode.equals("--clear-idle"))
            throw new IllegalArgumentException("Unknown callback task");
        if (!binary.getCanonicalFile().getParentFile().equals(context.getCodeCacheDir().getCanonicalFile()) ||
                !result.getCanonicalPath().startsWith(new File(context.getFilesDir(), "blueforce-callback").getCanonicalPath() + "/"))
            throw new SecurityException("Callback task outside app directories");
        for (File file : new File[]{binary, result}) {
            android.system.StructStat info = Os.lstat(file.getPath());
            if (info.st_uid != Process.myUid() || !OsConstants.S_ISREG(info.st_mode) || info.st_nlink != 1)
                throw new SecurityException("Callback task owner/type mismatch");
        }
        String command = PioneerRootDispatcher.INSTANCE.quote(binary.getCanonicalPath()) + " " + mode + " " +
                Process.myUid() + " " + PioneerRootDispatcher.INSTANCE.quote(result.getCanonicalPath()) +
                " </dev/null >/dev/null 2>&1 &";
        PioneerRootDispatcher.INSTANCE.dispatch(command);
    }
}
