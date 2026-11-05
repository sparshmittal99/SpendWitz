package com.spendwitz.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives Firebase messages. Only runs when Firebase delivers one; nothing is scheduled or kept running. */
class PushMessagingService : FirebaseMessagingService() {

    /** Firebase rotates tokens now and then; the next page load links the new one to the account. */
    override fun onNewToken(token: String) {
        if (AppPrefs.budgetAlertsEnabled(this)) AppPrefs.setPushToken(this, token)
    }

    /** Called for messages that arrive while the app is open; when it's closed Android shows them directly. */
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        BudgetAlerts.show(
            this,
            title = notification.title ?: getString(R.string.app_name),
            body = notification.body ?: "",
            tag = notification.tag,
            openPath = message.data["open_path"],
        )
    }
}
