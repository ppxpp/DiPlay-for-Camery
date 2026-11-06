package com.shilapi.xcertplay.pioneer;

import java.io.IOException;

/** The complete allowlist for vehicle application management. Package names never come from IPC. */
public enum PioneerManagedApplication {
    AMAP("amap", "com.autonavi.amapauto"),
    TENCENT_MAP("tencent_map", "com.tencent.wecarnavi"),
    TENCENT_SPEECH("tencent_speech", "com.tencent.wecarspeech");

    public final String id, packageName;
    PioneerManagedApplication(String id, String packageName) { this.id = id; this.packageName = packageName; }
    public static PioneerManagedApplication fromId(String id) throws IOException {
        for (PioneerManagedApplication app : values()) if (app.id.equals(id)) return app;
        throw new IOException("APPLICATION_NOT_ALLOWED");
    }
}
