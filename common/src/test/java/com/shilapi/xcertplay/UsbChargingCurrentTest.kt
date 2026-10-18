package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbChargingCurrentTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun chargingOptionsKeepTheirExistingValues() {
        assertEquals(CarPlayRuntimeConfig.DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS, UsbChargingCurrent.LOW.milliAmps)
        assertEquals(listOf(2400, 1500, 500), UsbChargingCurrent.entries.map { it.milliAmps })
    }

    @Test fun storedValuesAreStableKeysAndUnknownOnesFallBackToLow() {
        assertEquals(listOf("normal", "reduced", "low"), UsbChargingCurrent.entries.map { it.key })
        assertEquals(UsbChargingCurrent.LOW, UsbChargingCurrent.fromKey(null))
        assertEquals(UsbChargingCurrent.LOW, UsbChargingCurrent.fromKey("2"))
        assertEquals(UsbChargingCurrent.LOW, UsbChargingCurrent.fromKey("one_amp"))
    }

    @Test fun theChoiceIsSavedAndDefaultsToLow() {
        assertEquals(UsbChargingCurrent.LOW, AirPlayPersistence.loadUsbChargingCurrent(context))
        AirPlayPersistence.saveUsbChargingCurrent(context, UsbChargingCurrent.NORMAL)
        assertEquals(UsbChargingCurrent.NORMAL, AirPlayPersistence.loadUsbChargingCurrent(context))
    }

    @Test fun recommendedReducedCurrentRoundTripsAs1500Milliamps() {
        AirPlayPersistence.saveUsbChargingCurrent(context, UsbChargingCurrent.REDUCED)
        assertEquals(1500, AirPlayPersistence.loadUsbChargingCurrent(context).milliAmps)
        assertEquals(UsbChargingCurrent.REDUCED, UsbChargingCurrent.fromKey("reduced"))
    }
}
