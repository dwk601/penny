package com.dwk.flowmoney

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class SimpleFinSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        runSync(SimpleFinSyncRepository(applicationContext)) {
            PennyWidgetProvider.refreshAll(applicationContext)
        }

    companion object {
        const val PERIODIC_WORK_NAME = "simplefin-sync"
        const val ONE_SHOT_WORK_NAME = "simplefin-sync-now"

        internal suspend fun runSync(
            repository: SimpleFinSyncRepository,
            refreshWidget: suspend () -> Unit = {},
        ): Result =
            when (val result = repository.syncIfStale()) {
                is SimpleFinSyncResult.Success -> {
                    if (result.inserted > 0 || result.updated > 0) {
                        try {
                            refreshWidget()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            // The sync is already committed; a later widget refresh can recover.
                        }
                    }
                    Result.success()
                }

                SimpleFinSyncResult.Throttled -> {
                    Result.success()
                }

                is SimpleFinSyncResult.Failure -> {
                    if (result.retryable) Result.retry() else Result.success()
                }
            }

        suspend fun schedule(
            context: Context,
            automaticSyncsPerDay: Int,
            reanchor: Boolean = false,
        ) = withContext(Dispatchers.IO) {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    if (reanchor) {
                        ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                    } else {
                        ExistingPeriodicWorkPolicy.UPDATE
                    },
                    PeriodicWorkRequestBuilder<SimpleFinSyncWorker>(
                        automaticSyncIntervalMillis(automaticSyncsPerDay),
                        TimeUnit.MILLISECONDS,
                    ).setBackoffCriteria(BackoffPolicy.LINEAR, SIMPLEFIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                        .setConstraints(networkConstraints())
                        .setInitialDelay(
                            initialSyncDelayMillis(readPreferredSyncTime(context), ZonedDateTime.now()),
                            TimeUnit.MILLISECONDS,
                        ).build(),
                ).result
                .get()
        }

        fun enqueueOneShot(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SimpleFinSyncWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, SIMPLEFIN_RETRY_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                    .setConstraints(networkConstraints())
                    .build(),
            )
        }

        suspend fun cancel(context: Context) =
            withContext(Dispatchers.IO) {
                val manager = WorkManager.getInstance(context)
                manager.cancelUniqueWork(PERIODIC_WORK_NAME).result.get()
                manager.cancelUniqueWork(ONE_SHOT_WORK_NAME).result.get()
            }

        private fun networkConstraints() =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
