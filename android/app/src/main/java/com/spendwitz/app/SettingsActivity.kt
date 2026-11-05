package com.spendwitz.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** App settings: dark mode and budget alert switches, photo info, version. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var darkSwitch: MaterialSwitch
    private lateinit var alertsSwitch: MaterialSwitch

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setAlerts(true) else {
                alertsSwitch.isChecked = false
                showNotificationsBlocked()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        root = findViewById(R.id.root)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        darkSwitch = findViewById(R.id.switchDarkMode)
        alertsSwitch = findViewById(R.id.switchBudgetAlerts)

        // Set the current state first, then listen, so restoring the screen doesn't fire the listeners
        darkSwitch.isChecked = AppPrefs.isDarkMode(this)
        darkSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != AppPrefs.isDarkMode(this)) AppPrefs.setDarkMode(this, checked)
        }
        findViewById<View>(R.id.rowDarkMode).setOnClickListener { darkSwitch.toggle() }

        alertsSwitch.isChecked = AppPrefs.budgetAlertsEnabled(this) && notificationsAllowed()
        alertsSwitch.setOnCheckedChangeListener { _, checked -> onAlertsSwitched(checked) }
        findViewById<View>(R.id.rowBudgetAlerts).setOnClickListener { alertsSwitch.toggle() }

        findViewById<TextView>(R.id.versionText).text = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.serverText).text = Uri.parse(BuildConfig.BASE_URL).host ?: BuildConfig.BASE_URL
    }

    override fun onResume() {
        super.onResume()
        // Notifications may have been allowed in system settings meanwhile
        if (AppPrefs.budgetAlertsEnabled(this) && notificationsAllowed() && !alertsSwitch.isChecked) {
            alertsSwitch.isChecked = true
        }
    }

    private fun onAlertsSwitched(checked: Boolean) {
        if (!checked) {
            setAlerts(false)
            return
        }
        when {
            needsPermission() -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            !NotificationManagerCompat.from(this).areNotificationsEnabled() -> {
                alertsSwitch.isChecked = false
                showNotificationsBlocked()
            }
            else -> setAlerts(true)
        }
    }

    private fun setAlerts(enabled: Boolean) {
        AppPrefs.setBudgetAlertsEnabled(this, enabled)
        // On: get a Firebase token (linked to the account on the next page). Off: delete it, so nothing arrives.
        BudgetAlerts.syncToken(applicationContext)
    }

    private fun needsPermission() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    private fun notificationsAllowed() =
        !needsPermission() && NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun showNotificationsBlocked() {
        Snackbar.make(root, R.string.notifications_blocked, Snackbar.LENGTH_LONG)
            .setAction(R.string.open_settings) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            .show()
    }
}
