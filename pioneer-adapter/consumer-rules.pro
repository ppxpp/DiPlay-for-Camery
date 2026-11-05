-keep class com.shilapi.xcertplay.pioneer.PioneerUsbNative { *; }
-keep class com.shilapi.xcertplay.pioneer.PioneerLwipNative { *; }
-keep class com.shilapi.xcertplay.pioneer.PioneerNativeCrypto { *; }
-keep class com.shilapi.xcertplay.pioneer.CamryDayNightListenerBridge { *; }
-dontwarn jp.pioneer.ceam.widget.MiscManager.**
-keep class com.shilapi.xcertplay.pioneer.PioneerMeterRefreshListener { *; }
-dontwarn jp.pioneer.ceam.MeterService.**

-keep class com.shilapi.xcertplay.pioneer.PioneerUsbPermissionHelper { public static void main(java.lang.String[]); }

-keep class com.shilapi.xcertplay.pioneer.PioneerVpnPermissionHelper { public static void main(java.lang.String[]); }


-keep class com.shilapi.xcertplay.pioneer.PioneerRootSessionHelper { public static void main(java.lang.String[]); }
-keep class com.shilapi.xcertplay.pioneer.PioneerHudFilterHelper { public static void main(java.lang.String[]); }

-keep class com.shilapi.xcertplay.pioneer.PioneerAdbHelper { public static void main(java.lang.String[]); }
