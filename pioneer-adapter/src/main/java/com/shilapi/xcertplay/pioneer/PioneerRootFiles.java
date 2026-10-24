package com.shilapi.xcertplay.pioneer;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;

/** Streams explicitly own their duplicated descriptor; the original Os.open fd is always closed. */
final class PioneerRootFiles {
    static FileInputStream input(String path, int flags, int mode) throws Exception {
        FileDescriptor raw = Os.open(path, flags | OsConstants.O_CLOEXEC, mode);
        ParcelFileDescriptor owned;
        try { owned = ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
        return new ParcelFileDescriptor.AutoCloseInputStream(owned);
    }
    static FileOutputStream output(String path, int flags, int mode) throws Exception {
        FileDescriptor raw = Os.open(path, flags | OsConstants.O_CLOEXEC, mode);
        ParcelFileDescriptor owned;
        try { owned = ParcelFileDescriptor.dup(raw); } finally { Os.close(raw); }
        return new ParcelFileDescriptor.AutoCloseOutputStream(owned);
    }
}
