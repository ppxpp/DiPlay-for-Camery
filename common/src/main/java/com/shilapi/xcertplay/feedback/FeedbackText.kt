package com.shilapi.xcertplay.feedback

import android.content.Context
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import java.text.DateFormat
import java.util.Date

internal object FeedbackText {
    @StringRes
    fun category(category: FeedbackCategory): Int = when (category) {
        FeedbackCategory.CONNECTION -> R.string.feedback_category_connection
        FeedbackCategory.DISPLAY -> R.string.feedback_category_display
        FeedbackCategory.AUDIO -> R.string.feedback_category_audio
        FeedbackCategory.NAVIGATION -> R.string.feedback_category_navigation
        FeedbackCategory.VEHICLE -> R.string.feedback_category_vehicle
        FeedbackCategory.OTHER -> R.string.feedback_category_other
    }

    @StringRes
    fun state(state: FeedbackState): Int = when (state) {
        FeedbackState.PENDING -> R.string.feedback_state_pending
        FeedbackState.UPLOADING -> R.string.feedback_state_uploading
        FeedbackState.FAILED_RETRYABLE -> R.string.feedback_state_retryable
        FeedbackState.FAILED_PERMANENT -> R.string.feedback_state_permanent
    }

    fun categoryLabel(context: Context, category: FeedbackCategory): String =
        context.getString(category(category))

    fun stateLabel(context: Context, state: FeedbackState): String =
        context.getString(state(state))

    fun time(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun shortId(id: String): String = id.take(8)
}
