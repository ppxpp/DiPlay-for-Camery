package com.shilapi.xcertplay.pioneer

import jp.pioneer.ceam.MeterService.MeterServiceListener

/** Loaded reflectively only on head units that provide the CEAM Meter SDK. */
class PioneerMeterRefreshListener(private val requested: () -> Unit) : MeterServiceListener() {
    override fun OnNotifySetNaviInfo() = requested()
}
