package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import com.shilapi.xcertplay.network.WirelessHotspotManager

/** Per-connection handle: closing an old handle cannot close a reused hotspot. */
class PioneerVehicleHotspot(context: Context, private var isCancelled: () -> Boolean,
    private val log: (String) -> Unit) : WirelessHotspotManager {
    companion object {
        fun configuredSsid(context: Context): String {
            val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config=PioneerHotspotConfiguration.ensure(wifi)
            return config.SSID?.takeIf { it.isNotBlank() } ?: error("Vehicle hotspot SSID unavailable")
        }
    }
    private val app=context.applicationContext.also { PioneerHotspotRetention.initialize(it) }
    private var delegate=PioneerHotspotRetention.take()?.also {
        it.bindLog(log);it.bindCancellation { isCancelled() }
    }
    private var reused=delegate!=null
    private var closed=false
    private var started=false
    @Synchronized fun bindCancellation(cancelled: () -> Boolean) {
        isCancelled=cancelled
        delegate?.bindCancellation(cancelled)
    }
    @Synchronized fun prewarm() {
        if(!closed && !isCancelled() && !reused) { claimRetained();if(!reused) current().prewarm() }
    }
    private fun claimRetained() {
        if(reused || started) return
        val retained=PioneerHotspotRetention.take() ?: return
        // A previous controller can finish teardown after this handle was prewarmed.
        // Retire only our unexecuted prewarm, then adopt the newly idle AP lease.
        try { delegate?.close() } catch(failure: Exception) {
            try { retained.close() } catch(cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        retained.bindLog(log);retained.bindCancellation { isCancelled() }
        delegate=retained;reused=true
    }
    private fun current(): OwnedPioneerHotspot = delegate ?: OwnedPioneerHotspot(app,{ isCancelled() },log).also { delegate=it }
    @Synchronized override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(!closed && !isCancelled()) { "Vehicle hotspot cancelled" }
        check(!started) { "Vehicle hotspot already started" }
        claimRetained()
        started=true
        if(reused) {
            reused=false
            try {
                return current().resume(timeoutMillis).also { log("Vehicle AP reused; skipped hotspot creation") }
            } catch(failure: Exception) {
                log("Vehicle AP reuse unavailable: ${failure.javaClass.simpleName}; rebuilding")
                current().close();delegate=null
                check(!isCancelled()) { "Vehicle hotspot cancelled" }
            }
        }
        return current().start(timeoutMillis)
    }
    @Synchronized override fun validateReady() { check(!closed);current().validateReady() }
    @Synchronized override fun connectionDiagnosticSnapshot() = delegate?.connectionDiagnosticSnapshot() ?: "vehicle_ap=pending"
    @Synchronized override fun close() {
        if(closed) return
        closed=true
        val owned=delegate ?: return
        delegate=null
        owned.bindCancellation { false }
        if(owned.retainable() && PioneerVehicleWireless.supported()) {
            owned.bindLog { android.util.Log.i("PioneerHotspot",it) }
            PioneerHotspotRetention.retain(app,owned)
        } else owned.close()
    }
}
