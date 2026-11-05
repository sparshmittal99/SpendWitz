package com.spendwitz.app

import android.app.Application
import android.app.job.JobScheduler

class SpendWitzApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.applyNightMode(this)
        BudgetAlerts.createChannel(this)
        BudgetAlerts.syncToken(this)
        // Earlier test builds checked budgets every 15 minutes in the background: remove that leftover schedule
        getSystemService(JobScheduler::class.java)?.cancelAll()
    }
}
