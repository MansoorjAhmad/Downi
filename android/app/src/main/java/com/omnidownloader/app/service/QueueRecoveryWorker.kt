package com.omnidownloader.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omnidownloader.app.engine.DownloadCoordinator
import java.util.concurrent.TimeUnit

class QueueRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DownloadCoordinator.recoverQueuedJobs(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "downi_queue_recovery"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<QueueRecoveryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
