package com.shilapi.xcertplay.feedback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shilapi.xcertplay.CarPlayBackgroundSession
import java.util.concurrent.TimeUnit

internal class FeedbackUploadWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    override fun doWork(): Result {
        val context = applicationContext
        FeedbackOutbox.cleanup(context)
        val records = FeedbackOutbox.pending(context)
        if (records.isEmpty()) return Result.success()
        if (FeedbackConnectivity.select(context) == null) return Result.retry()

        for (record in records) {
            if (!record.userInitiated &&
                (CarPlayBackgroundSession.hasSession() || CarPlayBackgroundSession.active)
            ) {
                return Result.retry()
            }
            FeedbackOutbox.markUploading(context, record.meta.id)
            val result = FeedbackUploader.upload(
                context = context,
                meta = record.meta,
                bundle = FeedbackOutbox.bundle(context, record.meta.id),
            )
            when (result) {
                is FeedbackUploader.Result.Sent -> FeedbackOutbox.markSent(context, record.meta.id)
                is FeedbackUploader.Result.Retryable -> {
                    FeedbackOutbox.markRetryable(context, record.meta.id, result.reason)
                    return Result.retry()
                }
                is FeedbackUploader.Result.Permanent -> {
                    FeedbackOutbox.markPermanent(context, record.meta.id, result.reason)
                }
            }
        }
        return Result.success()
    }
}

internal object FeedbackScheduler {
    private const val WORK_NAME = "diplay-feedback-upload"

    fun schedule(context: Context, immediate: Boolean = false) {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<FeedbackUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                if (immediate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
