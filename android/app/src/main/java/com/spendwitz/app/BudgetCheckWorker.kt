package com.spendwitz.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Checks budgets in the background every 15 minutes (the shortest interval Android allows), so the phone shows an
 * over-budget notification even when the app is closed, e.g. after spending added on the website or by a
 * statement import. WorkManager keeps this schedule across app closes and phone restarts. While the app is open it
 * also checks right after every page load.
 */
class BudgetCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (BudgetAlerts.check(applicationContext)) {
        // The free server may be asleep or the network flaky: try again soon rather than in 15 minutes
        BudgetAlerts.Result.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
        else -> Result.success()
    }

    companion object {
        private const val NAME = "budget-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BudgetCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            // UPDATE: phones that had the older schedule switch to this one
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
