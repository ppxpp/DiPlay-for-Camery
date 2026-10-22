package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime

/** Resolves the destination before Android exposes a previously rendered Home screen. */
class DiPlayLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resumeProjection = Disclaimer.acceptedForCurrentVersion(this) &&
            (CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.hasHost())
        val target = if (resumeProjection) CarPlayHostActivity::class.java else DiPlayActivity::class.java
        PioneerDebugRuntime.log(this, "UI_ROUTE launcher dispatch=${target.simpleName} session=${CarPlayBackgroundSession.hasSession()} host=${CarPlayBackgroundSession.hasHost()}")
        val next = Intent(intent).setClass(this, target)
        // Do not carry the launcher's RESET_TASK/CLEAR_TOP semantics into either destination.
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
            (if (resumeProjection) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0)
        try {
            OwnScreenStart.start(this, next)
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
