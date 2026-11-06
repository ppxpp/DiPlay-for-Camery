# Invoked reflectively inside the shared root service.
-keep class com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeHelper { *; }
-keep class com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeHelper$* { *; }
-keep enum com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless.HotspotProbeAction { *; }

# Locally provisioned PAL SDK and its callbacks/DTOs are accessed by reflection and Binder/Gson.
-keep class com.tencent.tai.pal.** { *; }
