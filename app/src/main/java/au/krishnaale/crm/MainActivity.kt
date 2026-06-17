package au.krishnaale.crm

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import au.krishnaale.crm.databinding.ActivityMainBinding
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_URL = "extra_target_url"
    }

    private lateinit var binding: ActivityMainBinding
    private val webView: WebView get() = binding.webView

    // --- File upload (WebView <input type=file>) plumbing ---
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            cameraImageUri != null -> arrayOf(cameraImageUri!!)
            else -> null
        }
        callback.onReceiveValue(results)
        cameraImageUri = null
    }

    // --- Android 13+ notification permission ---
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — either way we continue */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)

        configureWebView()
        setupSwipeRefresh()
        setupOfflineRetry()
        setupBackNavigation()

        ensureNotificationPermission()
        ensureNotificationEmail()
        fetchAndRegisterToken()

        val target = intent.getStringExtra(EXTRA_TARGET_URL)
        loadStartUrl(target)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TARGET_URL)?.let { url ->
            if (url.isNotBlank()) webView.loadUrl(url)
        }
    }

    // ---------------------------------------------------------------- WebView

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = "$userAgentString KrishnaAleCRMApp/${BuildConfig.VERSION_NAME}"
        }

        webView.webViewClient = portalWebViewClient
        webView.webChromeClient = portalChromeClient
        // Bridge used to save blob: downloads (e.g. invoice PDFs the portal generates client-side).
        webView.addJavascriptInterface(DownloadBridge(this), "AndroidDownloader")
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }
    }

    private val portalWebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()

            // Email / phone links -> hand to the system.
            if (scheme == "mailto" || scheme == "tel") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: ActivityNotFoundException) { /* no handler */ }
                return true
            }

            // Keep portal navigation inside the app; send anything else to a Custom Tab.
            val host = url.host ?: return false
            return if (isPortalHost(host)) {
                false // let the WebView load it
            } else {
                openExternal(url)
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            binding.progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            // Only treat main-frame failures as "offline" so a stray sub-resource
            // does not blank the whole screen.
            if (request.isForMainFrame) showOffline()
        }
    }

    private val portalChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // <input type="file"> support, including camera capture.
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            val contentIntent = params.createIntent().apply {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }

            val cameraIntent = buildCameraIntentOrNull()

            val chooser = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentIntent)
                putExtra(Intent.EXTRA_TITLE, getString(R.string.choose_file))
                if (cameraIntent != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                }
            }

            return try {
                fileChooserLauncher.launch(chooser)
                true
            } catch (_: ActivityNotFoundException) {
                filePathCallback = null
                false
            }
        }

        // Camera/mic requests originating from the portal page itself (getUserMedia).
        override fun onPermissionRequest(request: PermissionRequest) {
            val granted = request.resources.filter { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        hasPermission(Manifest.permission.CAMERA)
                    else -> false
                }
            }.toTypedArray()
            if (granted.isNotEmpty()) request.grant(granted) else request.deny()
        }

        // target="_blank" / window.open: capture the URL and route it ourselves.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(this@MainActivity)
            temp.settings.javaScriptEnabled = true
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    routePopup(request.url)
                    webView.post { temp.destroy() }
                    return true
                }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun buildCameraIntentOrNull(): Intent? {
        if (!hasPermission(Manifest.permission.CAMERA)) return null
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        return try {
            val imagesDir = File(cacheDir, "images").apply { mkdirs() }
            val file = File.createTempFile("upload_", ".jpg", imagesDir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraImageUri = uri
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("blob:")) {
                // DownloadManager can't fetch blob URLs — read it in-page and save via the bridge.
                triggerBlobDownload(url, contentDisposition, mimeType)
                return
            }
            if (url.startsWith("data:")) {
                openExternal(Uri.parse(url))
                return
            }
            val cookies = CookieManager.getInstance().getCookie(url)
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("Cookie", cookies)
                addRequestHeader("User-Agent", webView.settings.userAgentString)
                setTitle(fileName)
                setDescription(getString(R.string.downloading))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                allowScanningByMediaScanner()
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * For blob: downloads, run JS in the page to fetch the blob, base64-encode it, and
     * hand it to the AndroidDownloader bridge, which writes it to the Downloads folder.
     */
    private fun triggerBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val safeUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
        val safeName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val safeMime = (mimeType ?: "").replace("'", "\\'")

        val js = """
            (function(){
              try{
                var xhr=new XMLHttpRequest();
                xhr.open('GET','$safeUrl',true);
                xhr.responseType='blob';
                xhr.onload=function(){
                  if(xhr.status===200){
                    var blob=xhr.response;
                    var reader=new FileReader();
                    reader.onloadend=function(){
                      var res=reader.result||'';
                      var idx=res.indexOf(',');
                      var b64=idx>=0?res.substring(idx+1):res;
                      var mime=(blob&&blob.type)?blob.type:'$safeMime';
                      AndroidDownloader.processBase64Data(b64, mime, '$safeName');
                    };
                    reader.readAsDataURL(blob);
                  }
                };
                xhr.send();
              }catch(e){}
            })()
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Toast.makeText(this, getString(R.string.downloading), Toast.LENGTH_SHORT).show()
    }

    /** Route a popup / target="_blank" URL: portal pages stay in-app, the rest go external. */
    private fun routePopup(uri: Uri) {
        val host = uri.host
        if (host != null && isPortalHost(host)) webView.loadUrl(uri.toString()) else openExternal(uri)
    }

    // ---------------------------------------------------------------- helpers

    private fun isPortalHost(host: String): Boolean {
        val portalHost = BuildConfig.PORTAL_HOST
        // Allow the workspace host and any agencyhandy.com asset/auth subdomain.
        return host.equals(portalHost, ignoreCase = true) ||
            host.endsWith(".agencyhandy.com", ignoreCase = true) ||
            host.equals("agencyhandy.com", ignoreCase = true)
    }

    private fun openExternal(uri: Uri) {
        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadStartUrl(target: String?) {
        val url = if (!target.isNullOrBlank()) target else BuildConfig.PORTAL_URL
        if (isOnline()) {
            hideOffline()
            webView.loadUrl(url)
        } else {
            showOffline()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.accent, R.color.brand_dark)
        binding.swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                hideOffline()
                webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showOffline()
            }
        }
    }

    private fun setupOfflineRetry() {
        binding.offlineRetryButton.setOnClickListener {
            if (isOnline()) {
                hideOffline()
                if (webView.url != null) webView.reload() else loadStartUrl(null)
            } else {
                Toast.makeText(this, getString(R.string.still_offline), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOffline() {
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.offlineView.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        binding.offlineView.visibility = View.GONE
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- push setup

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun ensureNotificationEmail() {
        if (SecurePrefs.getEmail(this) != null) return
        promptForEmail(firstRun = true)
    }

    private fun promptForEmail(firstRun: Boolean) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.email_hint)
            setText(SecurePrefs.getEmail(this@MainActivity) ?: "")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.email_title))
            .setMessage(getString(R.string.email_message))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val email = input.text?.toString()?.trim().orEmpty()
                if (isValidEmail(email)) {
                    SecurePrefs.setEmail(this, email)
                    DeviceRegistration.register(this)
                    Toast.makeText(this, getString(R.string.notifications_on), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.email_invalid), Toast.LENGTH_SHORT).show()
                }
            }
            .apply {
                if (firstRun) setNegativeButton(getString(R.string.skip), null)
                else setNegativeButton(getString(R.string.cancel), null)
            }
            .create()
        dialog.show()
    }

    private fun isValidEmail(email: String): Boolean =
        android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()

    private fun fetchAndRegisterToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                SecurePrefs.setLastToken(this, token)
                DeviceRegistration.register(this)
            }
        }
    }

    // ---------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_toggle_lock)?.isChecked = SecurePrefs.isLockEnabled(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                if (isOnline()) { hideOffline(); webView.reload() } else showOffline()
                true
            }
            R.id.action_home -> {
                loadStartUrl(null); true
            }
            R.id.action_email -> {
                promptForEmail(firstRun = false); true
            }
            R.id.action_toggle_lock -> {
                val newValue = !SecurePrefs.isLockEnabled(this)
                SecurePrefs.setLockEnabled(this, newValue)
                item.isChecked = newValue
                Toast.makeText(
                    this,
                    if (newValue) getString(R.string.lock_enabled) else getString(R.string.lock_disabled),
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
            R.id.action_open_browser -> {
                webView.url?.let { openExternal(Uri.parse(it)) }; true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }
}
