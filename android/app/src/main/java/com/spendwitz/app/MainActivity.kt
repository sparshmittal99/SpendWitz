package com.spendwitz.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import android.text.format.Formatter
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The SpendWitz web app in a WebView, plus what a browser can't do well on a phone: camera capture with
 * compression, voice input, over-budget notifications, dark mode and app settings.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_PATH = "open_path"
        private const val START_PATH = "/dashboard"
        private const val MAX_FILES = 8 // the scan page accepts up to 8 bills at once
        private const val BRIDGE = "SpendWitzApp"

        /** Closes an open Bootstrap drawer, bottom sheet or dialog. Returns true if one was open. */
        private const val CLOSE_OVERLAY_JS = """(function(){try{
            var el=document.querySelector('.offcanvas.show,.modal.show');
            if(!el||!window.bootstrap)return false;
            var o=bootstrap.Offcanvas.getInstance(el)||bootstrap.Modal.getInstance(el);
            if(o){o.hide();return true;}}catch(e){}return false;})()"""
    }

    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offlineView: View
    private lateinit var introView: View

    private val baseUri: Uri = Uri.parse(BuildConfig.BASE_URL)
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var pageFailed = false
    private var appliedDark: Boolean? = null
    private var introShowing = false

    // ---- Activity results ----

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile
        cameraFile = null
        if (saved && file != null && file.length() > 0) {
            prepareUploads(listOf(Uri.fromFile(file)), cameraOriginal = file)
        } else {
            deliverFiles(null)
        }
    }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_FILES)) { handlePicked(it) }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { handlePicked(listOfNotNull(it)) }

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { handlePicked(it) }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { handlePicked(listOfNotNull(it)) }

    private val voiceInput = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) {
            callPage("spendwitzVoiceResult", text)
        } else {
            callPage("spendwitzVoiceError", getString(R.string.voice_cancelled))
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // ---- Lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash is just the background colour and goes away at once; our intro takes over from it
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        offlineView = findViewById(R.id.offlineView)
        introView = findViewById(R.id.introView)
        findViewById<View>(R.id.retryButton).setOnClickListener { retry() }

        UploadPreparer.deleteAll(this) // leftovers if the app was closed mid-upload
        setUpWebView()
        applyTheme()
        // Only on a fresh start, not when coming back after rotation or a process restart
        if (savedInstanceState == null) playIntro() else introView.visibility = View.GONE
        applyTheme(force = true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack(this)
        })

        val restored = savedInstanceState?.let { webView.restoreState(it) } != null
        if (!restored) {
            webView.loadUrl(urlFor(intent.getStringExtra(EXTRA_OPEN_PATH) ?: START_PATH))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_PATH)?.let { webView.loadUrl(urlFor(it)) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyTheme() // dark mode may have changed in App settings
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (isFinishing) UploadPreparer.deleteAll(this)
        webView.destroy()
        super.onDestroy()
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            // The server gives the app its Android shell (bottom bar, App settings) when it sees this
            userAgentString = "$userAgentString SpendWitzAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.addJavascriptInterface(AppBridge(), BRIDGE)
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun urlFor(path: String) = BuildConfig.BASE_URL + (if (path.startsWith("/")) path else "/$path")

    private fun isOwnSite(uri: Uri) =
        uri.host.equals(baseUri.host, ignoreCase = true) && uri.port == baseUri.port &&
            (uri.scheme == "https" || uri.scheme == "http")

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when {
                uri.scheme == "spendwitz-app" -> {
                    if (uri.host == "settings") openSettings()
                    true
                }
                isOwnSite(uri) -> false
                else -> { // other sites open in the browser or their own app
                    openExternally(uri)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageFailed = false
            progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
            offlineView.visibility = if (pageFailed) View.VISIBLE else View.GONE
            if (!pageFailed) {
                // A page loaded, so any upload has finished: delete the compressed copies right away
                lifecycleScope.launch(Dispatchers.IO) { UploadPreparer.deleteAll(applicationContext) }
                applyTheme(force = true)
                onAppPageLoaded(Uri.parse(url))
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                pageFailed = true
                offlineView.visibility = View.VISIBLE
            }
        }
    }

    private inner class ChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.setProgressCompat(newProgress, true)
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            showFileChooser(params)
            return true
        }
    }

    private fun retry() {
        offlineView.visibility = View.GONE
        if (webView.url.isNullOrBlank()) webView.loadUrl(urlFor(START_PATH)) else webView.reload()
    }

    private fun goBack(callback: OnBackPressedCallback) {
        webView.evaluateJavascript(CLOSE_OVERLAY_JS) { closed ->
            when {
                closed == "true" -> Unit
                webView.canGoBack() -> webView.goBack()
                else -> {
                    callback.isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    callback.isEnabled = true
                }
            }
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Calls a page function with one string argument, e.g. window.spendwitzVoiceResult("chai 20"). */
    private fun callPage(function: String, argument: String) {
        webView.evaluateJavascript("window.$function && window.$function(${JSONObject.quote(argument)})", null)
    }

    // ---- Intro (starting screen) ----

    /** Logo and name ease in, then the screen fades out: about 1.2 seconds in all, while the page loads behind. */
    private fun playIntro() {
        val logo = findViewById<View>(R.id.introLogo)
        val name = findViewById<View>(R.id.introName)
        val tagline = findViewById<View>(R.id.introTagline)
        val rise = 12 * resources.displayMetrics.density
        val ease = DecelerateInterpolator(1.6f)

        introShowing = true
        logo.alpha = 0f
        logo.scaleX = 0.82f
        logo.scaleY = 0.82f
        name.alpha = 0f
        tagline.alpha = 0f
        // Time it from the first frame on screen, so a slow cold start doesn't eat into the intro
        introView.doOnPreDraw { animateIntro(logo, name, tagline, rise, ease) }
    }

    private fun animateIntro(logo: View, name: View, tagline: View, rise: Float, ease: DecelerateInterpolator) {
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(ease).start()
        listOf(name to 120L, tagline to 200L).forEach { (view, delay) ->
            view.alpha = 0f
            view.translationY = rise
            view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(340).setInterpolator(ease).start()
        }
        introView.postDelayed({
            introView.animate().alpha(0f).setDuration(220).withEndAction {
                introView.visibility = View.GONE
                introShowing = false
                applyTheme(force = true) // system bars back to the app's colours
            }.start()
        }, 1_000)
    }

    // ---- Theme ----

    private fun applyTheme(force: Boolean = false) {
        val dark = AppPrefs.isDarkMode(this)
        if (!force && appliedDark == dark) return
        appliedDark = dark

        // This activity handles uiMode changes itself (so the page isn't reloaded): recolour by hand
        val pageBg = if (dark) 0xFF0F1117.toInt() else 0xFFF6F7FB.toInt()
        // While the blue intro shows, the system bars are blue too with white icons
        val barBg = when {
            introShowing -> ContextCompat.getColor(this, R.color.brand)
            dark -> 0xFF181B24.toInt()
            else -> Color.WHITE
        }
        window.statusBarColor = barBg
        // Android 8.0 can't draw dark navigation buttons: keep its navigation bar dark so they stay visible
        val lightNavUnsupported = Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 && barBg == Color.WHITE
        window.navigationBarColor = if (lightNavUnsupported) Color.BLACK else barBg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && !introShowing
            isAppearanceLightNavigationBars = !dark && !introShowing
        }
        root.setBackgroundColor(pageBg)
        webView.setBackgroundColor(pageBg)
        offlineView.setBackgroundColor(pageBg)
        findViewById<TextView>(R.id.offlineTitle)
            .setTextColor(if (dark) 0xFFE5E7F0.toInt() else 0xFF1E1B4B.toInt())
        findViewById<TextView>(R.id.offlineMessage)
            .setTextColor(if (dark) 0xFF9CA3AF.toInt() else 0xFF6B7280.toInt())

        webView.evaluateJavascript("window.spendwitzApplyTheme && window.spendwitzApplyTheme($dark)", null)
    }

    // ---- Budget alerts ----

    private fun onAppPageLoaded(uri: Uri) {
        if (!isOwnSite(uri)) return
        val path = uri.path ?: "/"
        val signedIn = path != "/" && !path.startsWith("/login") && !path.startsWith("/register") &&
            !path.startsWith("/error")
        if (!signedIn) return
        askForNotificationsOnce()
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!AppPrefs.budgetAlertsEnabled(this) || AppPrefs.askedForNotifications(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        AppPrefs.setAskedForNotifications(this)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- File uploads: camera, gallery, files ----

    private fun showFileChooser(params: WebChromeClient.FileChooserParams) {
        val accept = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val mimeTypes = toMimeTypes(accept)
        val wantsImages = accept.isEmpty() || mimeTypes.any { it.startsWith("image/") || it == "*/*" }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        when {
            !wantsImages -> launchDocuments(mimeTypes, multiple) // e.g. bank statements
            params.isCaptureEnabled -> launchCamera()
            else -> showSourceSheet(mimeTypes, multiple)
        }
    }

    @SuppressLint("InflateParams") // bottom sheet content has no parent until setContentView
    private fun showSourceSheet(mimeTypes: Array<String>, multiple: Boolean) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_file_source, null)
        var chosen = false
        fun option(id: Int, action: () -> Unit) = view.findViewById<View>(id).setOnClickListener {
            chosen = true
            sheet.dismiss()
            action()
        }
        option(R.id.optionCamera) { launchCamera() }
        option(R.id.optionGallery) { launchGallery(multiple) }
        option(R.id.optionFiles) { launchDocuments(mimeTypes, multiple) }
        sheet.setOnDismissListener { if (!chosen) deliverFiles(null) }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "bill_$stamp.jpg")
        cameraFile = file
        try {
            takePicture.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
        } catch (e: ActivityNotFoundException) {
            cameraFile = null
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show()
            deliverFiles(null)
        }
    }

    private fun launchGallery(multiple: Boolean) {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (multiple) pickImages.launch(request) else pickImage.launch(request)
    }

    private fun launchDocuments(mimeTypes: Array<String>, multiple: Boolean) {
        try {
            if (multiple) openDocuments.launch(mimeTypes) else openDocument.launch(mimeTypes)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
            deliverFiles(null)
        }
    }

    private fun handlePicked(uris: List<Uri>) {
        if (uris.isEmpty()) deliverFiles(null) else prepareUploads(uris.take(MAX_FILES), cameraOriginal = null)
    }

    /** Compresses photos off the main thread, then hands the files to the page. */
    private fun prepareUploads(uris: List<Uri>, cameraOriginal: File?) {
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                uris.map { UploadPreparer.prepare(applicationContext, it) }.also { cameraOriginal?.delete() }
            }
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            deliverFiles(prepared.map { it.uri }.toTypedArray())

            val compressed = prepared.filter { it.compressed }
            if (compressed.isNotEmpty()) {
                val before = Formatter.formatShortFileSize(this@MainActivity, compressed.sumOf { it.originalBytes.coerceAtLeast(0) })
                val after = Formatter.formatShortFileSize(this@MainActivity, compressed.sumOf { it.finalBytes })
                val message = if (compressed.size == 1) getString(R.string.photo_compressed, before, after)
                else resources.getQuantityString(R.plurals.photos_compressed, compressed.size, compressed.size, before, after)
                Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun deliverFiles(uris: Array<Uri>?) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    /** Turns an accept attribute (".pdf", "image/jpeg", ".csv") into MIME types for the system pickers. */
    private fun toMimeTypes(accept: List<String>): Array<String> {
        val types = linkedSetOf<String>()
        accept.forEach { entry ->
            when {
                entry.contains('/') -> types += entry
                entry.startsWith('.') -> {
                    val ext = entry.removePrefix(".")
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { types += it }
                    if (ext == "csv") types += listOf("text/csv", "text/comma-separated-values", "text/plain")
                    if (ext == "xls") types += "application/vnd.ms-excel"
                    if (ext == "xlsx") types += "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                }
            }
        }
        return if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray()
    }

    // ---- Voice input ----

    private fun startVoiceInput(language: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        try {
            voiceInput.launch(intent)
        } catch (e: ActivityNotFoundException) {
            callPage("spendwitzVoiceError", getString(R.string.voice_unavailable))
        }
    }

    // ---- Downloads (sample bank statements) ----

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            .addRequestHeader("User-Agent", userAgent)
            .setMimeType(mimeType)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        } else {
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name)
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
        Snackbar.make(root, getString(R.string.download_started, name), Snackbar.LENGTH_SHORT).show()
    }

    // ---- window.SpendWitzApp, called by the web pages ----

    inner class AppBridge {

        @JavascriptInterface
        fun isDarkMode(): Boolean = AppPrefs.isDarkMode(this@MainActivity)

        @JavascriptInterface
        fun openSettings() = runOnUiThread { this@MainActivity.openSettings() }

        @JavascriptInterface
        fun startVoiceInput(language: String?) = runOnUiThread {
            this@MainActivity.startVoiceInput(language?.takeIf { it.isNotBlank() } ?: "en-IN")
        }

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        /** This phone's Firebase token ("" when alerts are off), linked to the account by static/js/push.js. */
        @JavascriptInterface
        fun pushToken(): String =
            if (AppPrefs.budgetAlertsEnabled(this@MainActivity)) AppPrefs.pushToken(this@MainActivity) ?: "" else ""
    }
}
