package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** A paired address alone is insufficient evidence that a port belongs to this app. */
internal object PioneerSerialOwnership {
    fun matchesPort(selectedPhone: String, actualPhone: String, uuid: String, index: Int): Boolean =
        actualPhone.equals(selectedPhone, true) && index == 0 &&
            uuid.replace("-", "").equals("00000000DECAFADEDECADEAFDECACAFE", true)

    fun mayRecover(recordedPhone: String?, recordedBoot: String?, boot: String,
        selectedPhone: String, actualPhone: String, uuid: String, index: Int,
        connected: Boolean, lowerStatus: Int): Boolean =
        boot.isNotBlank() && recordedBoot==boot && recordedPhone?.equals(selectedPhone,true)==true &&
            matchesPort(selectedPhone, actualPhone, uuid, index) && connected && lowerStatus==0
}
