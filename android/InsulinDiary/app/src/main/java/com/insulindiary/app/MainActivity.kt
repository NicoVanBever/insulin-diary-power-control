package com.insulindiary.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACTION = "alarmAction"
        const val EXTRA_KEY = "alarmKey"
        private const val APP_URL = "https://appassets.androidplatform.net/assets/index.html"
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var pageReady = false
    private var pendingAction: String? = null
    @Volatile private var syncResult: String = ""
    private var pendingLink: String? = null
    private var lastScan: Pair<String, String>? = null // code + state, kept for "sync anyway"
    @Volatile private var offeredUpdate: JSONObject? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) { }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickFile.launch("*/*")
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        handleAlarmIntent(intent)
        handleLinkIntent(intent)
        web.loadUrl(APP_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val js = "(function(){try{if(dlg.open){dlg.close();return 1}" +
                    "if(!document.getElementById('tab-today').classList.contains('on')){showTab('today');return 1}}catch(e){}return 0})()"
                web.evaluateJavascript(js) { r -> if (r == "0") finish() }
            }
        })

        Alarms.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlarmIntent(intent)
        handleLinkIntent(intent)
    }

    /** The phone camera opened a sync code (http://<pc>:47622/p/…) and the user chose Insulin Diary. */
    private fun handleLinkIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val url = i.dataString ?: return
        i.data = null
        if (pageReady) deliverLink(url) else pendingLink = url
    }

    private fun deliverLink(url: String) {
        web.evaluateJavascript("window.onSyncLink && window.onSyncLink(" + JSONObject.quote(url) + ")", null)
    }

    private fun appVersionCode(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun appVersionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private val syncPrefs get() = getSharedPreferences("sync", MODE_PRIVATE)

    /** "I took it" / "Log glucose" on a ringing alarm: stop ringing, snooze it, and open the right log screen. */
    private fun handleAlarmIntent(i: Intent?) {
        if (i == null) return
        val action = i.getStringExtra(EXTRA_ACTION) ?: return
        i.getStringExtra(EXTRA_KEY)?.let { key ->
            Alarms.snooze(this, key)
            AlarmService.stop(this)
        }
        i.removeExtra(EXTRA_ACTION)
        if (pageReady) deliverAction(action) else pendingAction = action
    }

    private fun deliverAction(a: String) {
        web.evaluateJavascript("window.onNativeAlarmAction(" + JSONObject.quote(a) + ")", null)
    }

    override fun onResume() {
        super.onResume()
        Alarms.reschedule(this)
    }

    // ---------------------------------------------------------------- bridge for the page

    inner class Bridge {
        private val dataFile get() = File(filesDir, "diary.json")

        @JavascriptInterface
        fun loadData(): String = try { if (dataFile.exists()) dataFile.readText() else "" } catch (e: Exception) { "" }

        @JavascriptInterface
        fun saveData(s: String) {
            try {
                val dir = File(filesDir, "backups").apply { mkdirs() }
                val day = File(dir, "diary-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json")
                if (!day.exists() && dataFile.exists()) {
                    dataFile.copyTo(day, overwrite = true)
                    dir.listFiles()?.sortedBy { it.name }?.dropLast(14)?.forEach { it.delete() }
                }
                val tmp = File(filesDir, "diary.json.tmp")
                tmp.writeText(s)
                if (!tmp.renameTo(dataFile)) { dataFile.delete(); tmp.renameTo(dataFile) }
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun setAlarms(json: String) = Alarms.update(this@MainActivity, json)

        @JavascriptInterface
        fun ready() = runOnUiThread {
            pageReady = true
            pendingAction?.let { deliverAction(it); pendingAction = null }
            pendingLink?.let { deliverLink(it); pendingLink = null }
        }

        @JavascriptInterface
        fun setDarkBars(dark: Boolean) = runOnUiThread {
            root.setBackgroundColor(if (dark) Color.parseColor("#171B21") else Color.WHITE)
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }

        /** Status/navigation bar background follows the app's own colours. */
        @JavascriptInterface
        fun setBars(color: String, dark: Boolean) = runOnUiThread {
            try { root.setBackgroundColor(Color.parseColor(color)) } catch (_: Exception) { root.setBackgroundColor(if (dark) Color.parseColor("#171B21") else Color.WHITE) }
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }

        @JavascriptInterface
        fun setEmergencyNotice(text: String) = Emergency.set(this@MainActivity, text)

        @JavascriptInterface
        fun scanAndSync(state: String) = runOnUiThread { startScan(state) }

        @JavascriptInterface
        fun syncCode(url: String, state: String) = runOnUiThread { syncWithPc(url, state, checkPairing = true) }

        /** "Sync anyway" after the phone warned that the code comes from a different diary. */
        @JavascriptInterface
        fun confirmSync() = runOnUiThread { lastScan?.let { syncWithPc(it.first, it.second, checkPairing = false) } }

        @JavascriptInterface
        fun takeSyncResult(): String = syncResult

        @JavascriptInterface
        fun appVersion(): String = appVersionName()

        @JavascriptInterface
        fun pairedId(): String = syncPrefs.getString("diaryId", "") ?: ""

        @JavascriptInterface
        fun installUpdate() = runOnUiThread { startUpdate() }

        @JavascriptInterface
        fun saveFile(name: String, text: String, mime: String) = runOnUiThread { saveToDownloads(name, text, mime) }

        @JavascriptInterface
        fun print() = runOnUiThread {
            val pm = getSystemService(PRINT_SERVICE) as PrintManager
            pm.print("Insulin Diary report", web.createPrintDocumentAdapter("Insulin Diary report"), PrintAttributes.Builder().build())
        }
    }

    // ---------------------------------------------------------------- QR scan + Wi-Fi sync

    private fun err(msg: String) = SyncClient.err(msg)

    private fun finishSync(result: String) {
        syncResult = result
        runOnUiThread { web.evaluateJavascript("window.onSyncDone()", null) }
    }

    private fun progress(msg: String) = runOnUiThread {
        web.evaluateJavascript("window.onSyncProgress(" + JSONObject.quote(msg) + ")", null)
    }

    private fun startScan(state: String) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> syncWithPc(code.rawValue ?: "", state, checkPairing = true) }
            .addOnCanceledListener { finishSync(JSONObject().put("ok", false).put("cancelled", true).toString()) }
            .addOnFailureListener { e ->
                finishSync(err("The scanner couldn't start (${e.message}). Make sure Google Play services is up to date and try again."))
            }
    }

    private fun syncWithPc(raw: String, state: String, checkPairing: Boolean) {
        lastScan = raw to state
        val device = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL
        val paired = if (checkPairing) syncPrefs.getString("diaryId", null) else null
        val version = appVersionCode()
        thread {
            val result = SyncClient.sync(raw, state, device, version, paired) { progress(it) }
            try {
                val r = JSONObject(result)
                if (r.optBoolean("ok")) {
                    lastScan = null
                    r.optString("diaryId").takeIf { it.isNotEmpty() }?.let { syncPrefs.edit().putString("diaryId", SyncClient.normId(it)).apply() }
                    offeredUpdate = r.optJSONObject("update")?.put("base", r.optString("base"))
                }
            } catch (_: Exception) { }
            finishSync(result)
        }
    }

    // ---------------------------------------------------------------- app update from the PC

    private fun updateStatus(msg: String) = runOnUiThread {
        web.evaluateJavascript("window.onUpdateStatus && window.onUpdateStatus(" + JSONObject.quote(msg) + ")", null)
    }

    private fun startUpdate() {
        val u = offeredUpdate ?: run { updateStatus("Sync with your PC first to check for a newer version."); return }
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatus("Allow \"Install unknown apps\" for Insulin Diary, then come back and tap Update again.")
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) { }
            return
        }
        updateStatus("Downloading version ${u.optString("versionName")} from your PC…")
        val dest = File(File(cacheDir, "updates"), "InsulinDiary.apk")
        thread {
            val ok = SyncClient.download(u.optString("base"), u.optString("path"), u.optString("sha256"), dest) { pct ->
                if (pct % 10 == 0) updateStatus("Downloading… $pct%")
            }
            if (!ok) { updateStatus("The download failed or didn't match what the PC announced. Sync again and retry."); return@thread }
            runOnUiThread {
                updateStatus("Ready — confirm the update on the next screen. Your diary stays as it is.")
                val uri = FileProvider.getUriForFile(this, "$packageName.files", dest)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(i) } catch (e: Exception) { updateStatus("Couldn't open the installer: ${e.message}") }
            }
        }
    }

    // ---------------------------------------------------------------- files

    private fun saveToDownloads(name: String, text: String, mime: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no space")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Toast.makeText(this, getString(R.string.saved_downloads, name), Toast.LENGTH_LONG).show()
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val f = File(dir, name)
                f.writeBytes(bytes)
                Toast.makeText(this, getString(R.string.saved_at, f.absolutePath), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
