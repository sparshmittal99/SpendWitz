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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** App settings: dark mode and budget alert switches, photo compression info, version. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var darkSwitch: MaterialSwitch
    private lateinit var alertsSwitch: MaterialSwitch
    private lateinit var checkNowRow: View

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
        checkNowRow = findViewById(R.id.rowCheckNow)

        // Set the current state first, then listen, so restoring the screen doesn't fire the listeners
        darkSwitch.isChecked = AppPrefs.isDarkMode(this)
        darkSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != AppPrefs.isDarkMode(this)) AppPrefs.setDarkMode(this, checked)
        }
        findViewById<View>(R.id.rowDarkMode).setOnClickListener { darkSwitch.toggle() }

        alertsSwitch.isChecked = AppPrefs.budgetAlertsEnabled(this) && notificationsAllowed()
        updateCheckNow()
        alertsSwitch.setOnCheckedChangeListener { _, checked -> onAlertsSwitched(checked) }
        findViewById<View>(R.id.rowBudgetAlerts).setOnClickListener { alertsSwitch.toggle() }
        checkNowRow.setOnClickListener { checkNow() }

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
        if (enabled) {
            BudgetCheckWorker.schedule(this)
            lifecycleScope.launch { BudgetAlerts.check(applicationContext) }
        } else {
            BudgetCheckWorker.cancel(this)
        }
        updateCheckNow()
    }

    private fun updateCheckNow() {
        val enabled = alertsSwitch.isChecked
        checkNowRow.isEnabled = enabled
        checkNowRow.alpha = if (enabled) 1f else 0.45f
    }

    private fun checkNow() {
        val bar = Snackbar.make(root, R.string.checking, Snackbar.LENGTH_INDEFINITE).apply { show() }
        lifecycleScope.launch {
            val message = when (val result = BudgetAlerts.check(applicationContext)) {
                is BudgetAlerts.Result.Checked -> when {
                    result.budgets == 0 -> getString(R.string.check_no_budgets)
                    result.overBudget.isEmpty() -> getString(R.string.check_all_within)
                    else -> getString(R.string.check_over, result.overBudget.joinToString(", "))
                }
                BudgetAlerts.Result.NotSignedIn -> getString(R.string.check_signed_out)
                BudgetAlerts.Result.Disabled -> getString(R.string.notifications_blocked)
                BudgetAlerts.Result.Failed -> getString(R.string.check_failed)
            }
            bar.dismiss()
            Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
        }
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
