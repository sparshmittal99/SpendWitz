package com.spendwitz.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

/**
 * Over-budget notifications. Asks the server for this month's budget status (GET /budgets/alerts, using the
 * WebView's login cookies) and notifies once per category per month when it goes over its limit. If the category
 * drops back under (say the limit was raised), it can notify again the next time it goes over.
 */
object BudgetAlerts {

    const val CHANNEL_ID = "budget_alerts"
    private const val KEY_NOTIFIED = "notified_over_budget" // entries are "2026-11|FOOD_DINING"
    private val lock = Mutex()

    sealed interface Result {
        data object Disabled : Result
        data object NotSignedIn : Result
        data object Failed : Result
        data class Checked(val budgets: Int, val overBudget: List<String>) : Result
    }

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_budget_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_budget_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun check(context: Context): Result = lock.withLock {
        if (!AppPrefs.budgetAlertsEnabled(context)) return Result.Disabled
        val base = BuildConfig.BASE_URL
        // CookieManager belongs to the WebView: use it on the main thread
        val cookie = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie(base) }
        if (cookie.isNullOrBlank()) return Result.NotSignedIn

        val response = withContext(Dispatchers.IO) { fetch("$base/budgets/alerts", cookie) }
            ?: return Result.Failed
        if (response.setCookies.isNotEmpty()) {
            // A remember-me login started a new session: keep it for the WebView too
            withContext(Dispatchers.Main) {
                val cookies = CookieManager.getInstance()
                response.setCookies.forEach { cookies.setCookie(base, it) }
                cookies.flush()
            }
        }
        when {
            response.code in 300..399 || response.code == 401 || response.code == 403 -> return Result.NotSignedIn
            response.code != 200 || response.body == null -> return Result.Failed
        }

        return try {
            process(context, JSONObject(response.body!!))
        } catch (e: Exception) {
            Result.Failed
        }
    }

    private fun process(context: Context, json: JSONObject): Result {
        val month = json.getString("month")
        val symbol = json.optString("currencySymbol", "₹")
        val categories = json.getJSONArray("categories")
        val prefs = AppPrefs.prefs(context)
        // Forget earlier months: a new month starts with a clean slate
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet())!!
            .filter { it.startsWith("$month|") }.toMutableSet()
        val over = mutableListOf<String>()

        for (i in 0 until categories.length()) {
            val item = categories.getJSONObject(i)
            val code = item.getString("code")
            val key = "$month|$code"
            if (!item.optBoolean("overBudget")) {
                notified.remove(key)
                continue
            }
            val label = item.optString("label", code)
            over += label
            if (key !in notified && notify(context, code, label, symbol, item)) {
                notified += key
            }
        }
        prefs.edit().putStringSet(KEY_NOTIFIED, notified).apply()
        return Result.Checked(categories.length(), over)
    }

    private fun notify(context: Context, code: String, label: String, symbol: String, item: JSONObject): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        val format = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
        val spent = symbol + format.format(item.optDouble("spent"))
        val limit = symbol + format.format(item.optDouble("limit"))
        val text = context.getString(R.string.budget_alert_text, spent, limit, item.optInt("percentUsed"))

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_PATH, "/budgets")
        val tap = PendingIntent.getActivity(
            context, code.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(context.getString(R.string.budget_alert_title, label))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(code.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private class Response(val code: Int, val body: String?, val setCookies: List<String>)

    private fun fetch(url: String, cookie: String): Response? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false // a redirect to /login means "signed out"
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000 // the free server can take a while to wake up
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "SpendWitzAndroid/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            val setCookies = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
            val isJson = connection.contentType?.contains("json", ignoreCase = true) == true
            val body = if (code == 200 && isJson) connection.inputStream.bufferedReader().use { it.readText() } else null
            Response(code, body, setCookies)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
