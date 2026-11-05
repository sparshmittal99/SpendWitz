package com.spendwitz.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Over-budget phone notifications. The server sends them through Firebase Cloud Messaging the moment a saved
 * expense takes a category over its limit; Android shows them even when the app is closed, so nothing of ours runs
 * in the background. This phone's Firebase token is handed to the web page, which links it to the signed-in account.
 */
object BudgetAlerts {

    /** Must match "channel_id" in the server's FcmSender. */
    const val CHANNEL_ID = "budget_alerts"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_budget_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_budget_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Gets (or, when alerts are off, deletes) this phone's Firebase token. */
    fun syncToken(context: Context) {
        val messaging = FirebaseMessaging.getInstance()
        if (AppPrefs.budgetAlertsEnabled(context)) {
            messaging.isAutoInitEnabled = true
            messaging.token.addOnSuccessListener { AppPrefs.setPushToken(context, it) }
        } else {
            // A deleted token stops Firebase delivering to this phone; the server forgets it on its next send
            messaging.isAutoInitEnabled = false
            messaging.deleteToken()
            AppPrefs.setPushToken(context, null)
        }
    }

    /** Shows a message that arrived while the app was open (when closed, Android shows it by itself). */
    fun show(context: Context, title: String, body: String, tag: String?, openPath: String?) {
        if (!AppPrefs.budgetAlertsEnabled(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PATH, openPath ?: "/budgets")
        val id = (tag ?: title).hashCode()
        val tap = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(tag, id, notification)
        } catch (e: SecurityException) {
            // permission withdrawn meanwhile
        }
    }
}
