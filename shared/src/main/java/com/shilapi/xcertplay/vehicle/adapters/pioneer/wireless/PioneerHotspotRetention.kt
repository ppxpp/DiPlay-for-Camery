package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Keeps only an owned, fully prepared AP; the CarPlay controller owns no idle resources. */
object PioneerHotspotRetention {
    private val worker=Executors.newSingleThreadScheduledExecutor { job -> Thread(job,"pioneer-hotspot-retention").apply { isDaemon=true } }
    @Volatile private var app: Application?=null
    private val slot=HotspotRetentionSlot<OwnedPioneerHotspot>(SystemClock::elapsedRealtime,
        { delay, task -> worker.schedule({
            runCatching { task() }.onFailure { android.util.Log.e("PioneerHotspot","Delayed AP cleanup failed",it) }
            stopIdleService()
        },delay,TimeUnit.MILLISECONDS);Unit }, { it.close() })
    internal fun initialize(context: Context) { app=context.applicationContext as? Application }
    @Synchronized internal fun take(): OwnedPioneerHotspot? = try {
        slot.take()
    } catch(failure: Exception) {
        // close() leaves the journal for the normal cold-start recovery path.
        android.util.Log.e("PioneerHotspot","Expired AP cleanup requires recovery",failure)
        null
    } finally { stopIdleService() }
    @Synchronized internal fun retain(context: Context, hotspot: OwnedPioneerHotspot) {
        initialize(context)
        val delay=seconds(context)
        slot.retain(hotspot,delay)
        android.util.Log.i("PioneerHotspot","Vehicle AP retention seconds=$delay idle=${slot.hasIdle()}")
        if(slot.hasIdle()) {
            // The supported head unit is Android 7. A started service keeps the timer
            // independent of the connection Activity. On restricted systems, clean up.
            runCatching { checkNotNull(context.startService(Intent(context,PioneerHotspotRetentionService::class.java))) }
                .onFailure { clearAsync() }
        } else stopIdleService()
    }
    fun seconds(context: Context) = context.getSharedPreferences("diplay_car_hotspot",Context.MODE_PRIVATE)
        .getInt("camry_disconnect_retention_seconds",20).coerceIn(0,60)
    fun setSeconds(context: Context, seconds: Int) {
        initialize(context)
        context.getSharedPreferences("diplay_car_hotspot",Context.MODE_PRIVATE).edit()
            .putInt("camry_disconnect_retention_seconds",seconds.coerceIn(0,60)).apply()
        worker.execute {
            runCatching { slot.reschedule(seconds(context)) }.onFailure { android.util.Log.e("PioneerHotspot","AP retention update failed",it) }
            stopIdleService()
        }
    }
    fun clearAsync() { val clear=slot.clearAction();worker.execute {
        runCatching { clear() }.onFailure { android.util.Log.e("PioneerHotspot","AP cleanup failed",it) }
        stopIdleService()
    } }
    internal fun serviceStarted(service: Service, startId: Int) {
        worker.execute { if(!slot.hasIdle()) service.stopSelf(startId) }
    }
    @Synchronized private fun stopIdleService() { if(!slot.hasIdle()) app?.stopService(Intent(app,PioneerHotspotRetentionService::class.java)) }
}

/** START_NOT_STICKY: after process death the existing on-disk lease recovery remains authoritative. */
class PioneerHotspotRetentionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PioneerHotspotRetention.serviceStarted(this,startId)
        return START_NOT_STICKY
    }
}
