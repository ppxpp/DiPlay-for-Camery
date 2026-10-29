package com.shilapi.xcertplay.installer.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

// Process-lifetime holder for the newest known installer update, shared by the
// home banner and the settings page. Auto checks run at most once per day and
// only a successful server round-trip counts against that limit.
object AppUpdateStatus {
    var available by mutableStateOf<AppUpdate?>(null)

    private const val PREFS = "app_update"
    private const val KEY_LAST_AUTO_CHECK = "last_auto_check_success"

    suspend fun autoCheckOncePerDay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_AUTO_CHECK, null) == today) return

        val result = AppUpdateRepository.check(context)
        result.onSuccess { update ->
            prefs.edit().putString(KEY_LAST_AUTO_CHECK, today).apply()
            available = update
        }
    }
}
