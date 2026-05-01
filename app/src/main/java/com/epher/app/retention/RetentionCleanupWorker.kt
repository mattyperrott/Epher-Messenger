package com.epher.app.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.epher.app.EpherApplication
import java.util.concurrent.TimeUnit

class RetentionCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val app = applicationContext as EpherApplication
        app.container.repository.purgeExpiredRoomsNow("workmanager cleanup")
        Result.success()
    }.getOrElse {
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "epher-retention-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RetentionCleanupWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
