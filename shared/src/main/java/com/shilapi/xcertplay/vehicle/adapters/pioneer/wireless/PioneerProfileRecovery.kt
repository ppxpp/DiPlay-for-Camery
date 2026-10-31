package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** Called only after verifying the selected device and all native/cached serial ports are idle. */
internal object PioneerProfileRecovery {
    enum class Action { RESTORE_SAVED, ADOPT_CURRENT, BLOCK }
    fun decide(saved: Int, current: Int, recordedBoot: String?, boot: String, pairedCount: Int,
        peersCompatible: Boolean = pairedCount == 1): Action {
        if(saved !in 0..255 || current !in 0..255 || !peersCompatible || boot.isBlank()) return Action.BLOCK
        if(current==saved || current==(saved or 128)) return Action.RESTORE_SAVED
        // Firmware or the driver can establish a new ordinary profile after boot.
        // A legacy journal has no boot identity. Accept only a profile with NO serial
        // bits, leaving the current OEM/user configuration untouched.
        if((recordedBoot.isNullOrBlank() || recordedBoot!=boot) && current and 0xf0==0)
            return Action.ADOPT_CURRENT
        return Action.BLOCK
    }
}
