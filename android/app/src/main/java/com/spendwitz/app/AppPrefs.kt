package com.spendwitz.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate

/** The app's own settings (App settings screen). */
object AppPrefs {

    private const val FILE = "spendwitz_app"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_BUDGET_ALERTS = "budget_alerts"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notification_permission"
    private const val KEY_PUSH_TOKEN = "push_token"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Until the user flips the switch, the app follows the phone's own dark mode. */
    fun isDarkMode(context: Context): Boolean {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, false) else systemIsDark()
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, dark).apply()
        applyNightMode(context)
    }

    fun applyNightMode(context: Context) {
        val mode = when {
            !prefs(context).contains(KEY_DARK_MODE) -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            isDarkMode(context) -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun systemIsDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun budgetAlertsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BUDGET_ALERTS, true)

    fun setBudgetAlertsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUDGET_ALERTS, enabled).apply()
    }

    /** This phone's Firebase token, or null when alerts are off. The web page links it to the account. */
    fun pushToken(context: Context): String? = prefs(context).getString(KEY_PUSH_TOKEN, null)

    fun setPushToken(context: Context, token: String?) {
        prefs(context).edit().putString(KEY_PUSH_TOKEN, token).apply()
    }

    /** The notification permission prompt is shown once automatically; after that only from App settings. */
    fun askedForNotifications(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun setAskedForNotifications(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    }
}
