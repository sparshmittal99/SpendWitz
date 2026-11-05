package com.spendwitz.app

import android.app.Application

class SpendWitzApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.applyNightMode(this)
        BudgetAlerts.createChannel(this)
        if (AppPrefs.budgetAlertsEnabled(this)) {
            BudgetCheckWorker.schedule(this)
        }
    }
}
