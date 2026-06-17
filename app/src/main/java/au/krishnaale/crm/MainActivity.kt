package au.krishnaale.crm

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import au.krishnaale.crm.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            // Camera capture succeeded - handled via filePathCallback
        } else {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeRefresh()
        requestNotificationPermission()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView) {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                databaseEnabled = true
            }

            // Add the blob download bridge
            addJavascriptInterface(DownloadBridge(this@MainActivity, this), "AndroidDownload")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    return when {
                        // Keep portal URLs in-app
                        url.contains("krishnaale.agencyhandy.com") -> false
                        url.contains("agencyhandy.com") -> false
                        // Open all other URLs in Chrome Custom Tabs
                        url.startsWith("http") -> {
                            openInCustomTab(url)
                            true
                        }
                        else -> false
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.offlineView.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    // Inject blob download handler
                    view?.evaluateJavascript(getBlobDownloadScript(), null)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        binding.webView.visibility = View.GONE
                        binding.offlineView.visibility = View.VISIBLE
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.progressBar.progress = newProgress
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    fileChooserLauncher.launch("*/*")
                    return true
                }

                override fun onCreateWindow(
                    view: WebView, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    val newWebView = WebView(this@MainActivity)
                    newWebView.settings.javaScriptEnabled = true
                    newWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView, request: WebResourceRequest
                        ): Boolean {
                            val url = request.url.toString()
                            if (url.startsWith("http")) {
                                if (url.contains("agencyhandy.com")) {
                                    binding.webView.loadUrl(url)
                                } else {
                                    openInCustomTab(url)
                                }
                                // Defer destruction to avoid crash
                                binding.webView.postDelayed({
                                    newWebView.destroy()
                                }, 500)
                            }
                            return true
                        }
                    }
                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    return true
                }
            }

            // Set up download listener
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                startDownload(url, userAgent, contentDisposition, mimeType)
            }

            loadUrl(PORTAL_URL)
        }
    }

    private fun getBlobDownloadScript(): String {
        return """
            (function() {
                // Intercept blob: links
                document.querySelectorAll('a[href^="blob:"]').forEach(function(a) {
                    if (!a.dataset.androidHandled) {
                        a.dataset.androidHandled = 'true';
                        a.addEventListener('click', function(e) {
                            e.preventDefault();
                            var xhr = new XMLHttpRequest();
                            xhr.open('GET', a.href, true);
                            xhr.responseType = 'blob';
                            xhr.onload = function() {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    var base64 = reader.result.split(',')[1];
                                    var filename = a.download || a.getAttribute('data-filename') || 'download.pdf';
                                    var mime = xhr.response.type || 'application/octet-stream';
                                    AndroidDownload.downloadBlob(base64, filename, mime);
                                };
                                reader.readAsDataURL(xhr.response);
                            };
                            xhr.send();
                        });
                    }
                });
            })();
        """.trimIndent()
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        if (url.startsWith("blob:")) {
            // Handled by DownloadBridge JS interface
            return
        }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("User-Agent", userAgent)
            setMimeType(mimeType)
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            setTitle(filename)
            setDescription("Downloading...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(R.color.portal_blue)
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
    }

    private fun openInCustomTab(url: String) {
        try {
            val intent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            intent.launchUrl(this, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }

    companion object {
        const val PORTAL_URL = "https://krishnaale.agencyhandy.com/"
    }
}
