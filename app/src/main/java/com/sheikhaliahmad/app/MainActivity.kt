package com.sheikhaliahmad.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var loader: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())

    // ───────────── SETTINGS ─────────────
    private val startUrl = "https://ladla-sheikh.vercel.app/"
    private val allowedHost = "ladla-sheikh.vercel.app"

    /** true = har http/https link app ke andar khulega. false = sirf apni site + login (Google/GitHub...) + downloads app mein, baqi websites Chrome mein. */
    private val keepInsideApp = false

    /** true = WebView ki "; wv" nishani hata kar Chrome jaisa user-agent (login providers ke liye behtar). */
    private val chromeLikeUserAgent = true

    /** true = phone ko USB se laga kar Chrome ke chrome://inspect mein page ka console/errors dekh sakte hain. */
    private val enableWebDebugging = false
    // ────────────────────────────────────

    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val clipData = data?.clipData
            var uris: Array<Uri>? = null

            if (clipData != null) {
                uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (data?.data != null) {
                uris = arrayOf(data.data!!)
            }
            fileCallback?.onReceiveValue(uris)
        } else {
            fileCallback?.onReceiveValue(null)
        }
        fileCallback = null
    }

    // ───────────── Android 6-9: storage permission for downloads ─────────────
    private class PendingDownload(val url: String, val userAgent: String?, val contentDisposition: String?, val mimeType: String?)
    private var pendingDownload: PendingDownload? = null

    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingDownload
        pendingDownload = null
        if (granted && p != null) {
            enqueueDownload(p)
        } else {
            Toast.makeText(this, "Download ke liye storage permission zaroori hai", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────── URL helpers ─────────────
    private fun isInternal(host: String?): Boolean {
        if (host == null) return false
        return host == allowedHost || host.endsWith(".$allowedHost")
    }

    private val strongAuthHosts = listOf(
        "firebaseapp.com", "accounts.google.com", "appleid.apple.com",
        "login.microsoftonline.com", "login.live.com"
    )
    private val weakAuthHosts = listOf("github.com", "facebook.com", "twitter.com", "x.com")

    /** Login (Firebase / Google / GitHub / Facebook ...) ke pages jo app ke andar hi khulne chahiye. */
    private fun isAuthUrl(uri: Uri): Boolean {
        val h = uri.host?.lowercase() ?: return false
        val p = uri.path?.lowercase() ?: ""
        if (strongAuthHosts.any { h == it || h.endsWith(".$it") }) return true
        if (weakAuthHosts.any { h == it || h.endsWith(".$it") }) {
            return p.startsWith("/login") || p.startsWith("/sessions") || p.contains("oauth") || p.contains("/dialog")
        }
        return false
    }

    private val downloadExt = setOf("apk", "xapk", "apks", "zip", "rar", "7z", "tar", "gz", "exe", "msi", "iso", "obb")

    /** GitHub release files aur archive/installer files ko app ke andar download karwana hai. */
    private fun isDownloadLink(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""
        if ((host == "github.com" || host == "www.github.com") && path.contains("/releases/download/")) return true
        if (host.endsWith("githubusercontent.com") &&
            (host.startsWith("objects.") || host.startsWith("release-assets.") || host.startsWith("github-releases."))
        ) return true
        val ext = path.substringAfterLast('.', "")
        return ext in downloadExt
    }

    private fun openExternal(uri: Uri) {
        try {
            if (uri.scheme == "intent") {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                startActivity(i)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            if (e is ActivityNotFoundException || e is java.net.URISyntaxException || e is SecurityException) {
                Toast.makeText(this, "Is link ko kholne wali app nahi mili", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───────────── onCreate ─────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)
        rootLayout.setBackgroundColor(Color.parseColor("#00918a"))

        swipeRefresh = SwipeRefreshLayout(this)
        // WebView badal bhi jaye (render crash recovery) to pull-to-refresh sahi chalta rahe
        swipeRefresh.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        swipeRefresh.setOnRefreshListener { web.reload() }

        web = createWebView()
        swipeRefresh.addView(web)
        rootLayout.addView(swipeRefresh, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // loading spinner (taake blank/solid screen ki jagah "loading" nazar aaye)
        loader = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val size = (48 * resources.displayMetrics.density).toInt()
        rootLayout.addView(loader, FrameLayout.LayoutParams(size, size, Gravity.CENTER))

        setContentView(rootLayout)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (enableWebDebugging) WebView.setWebContentsDebuggingEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null) {
            web.loadUrl(startUrl)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    // ───────────── main WebView ─────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            // Firebase login popup (window.open) ke liye
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            if (chromeLikeUserAgent) {
                userAgentString = userAgentString
                    .replace("; wv", "")
                    .replace(Regex("Version/\\d+\\.\\d+ "), "")
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)   // Firebase auth iframe/storage ke liye zaroori
        }

        w.addJavascriptInterface(WebAppInterface(), "AndroidDownloader")

        // Har download (GitHub release, zip, apk ...) app ke andar DownloadManager se, Chrome mein nahi
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownloadRequest(url, userAgent, contentDisposition, mimeType)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        if (isDownloadLink(uri)) {
                            handleDownloadRequest(uri.toString(), view.settings.userAgentString, null, null)
                            return true
                        }
                        if (keepInsideApp || isInternal(uri.host) || isAuthUrl(uri)) return false
                        openExternal(uri)
                        return true
                    }
                    "blob", "data", "about", "javascript", "file" -> return false
                    else -> {              // mailto:, tel:, whatsapp:, tg:, intent: ...
                        openExternal(uri)
                        return true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loader.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                loader.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                loader.visibility = View.GONE

                web.evaluateJavascript("""
                    (function() {
                        if (window.hasDownloaderInstalled) return;
                        window.hasDownloaderInstalled = true;
                        document.addEventListener('click', function(e) {
                            var target = e.target.closest('a');
                            if (target && target.hasAttribute('download') && target.href.startsWith('blob:')) {
                                e.preventDefault();
                                fetch(target.href)
                                    .then(r => r.blob())
                                    .then(blob => {
                                        var reader = new FileReader();
                                        reader.onload = function() {
                                            var base64 = reader.result.split(',')[1];
                                            var filename = target.getAttribute('download') || 'tooldeck_download';
                                            AndroidDownloader.downloadBase64(filename, base64);
                                        };
                                        reader.readAsDataURL(blob);
                                    });
                            }
                        });
                    })();
                """, null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showErrorPage(view, "${error.description} (${error.errorCode})")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    showErrorPage(view, "HTTP ${errorResponse.statusCode} - ${request.url.path}")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                showErrorPage(view, "SSL error")
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // WebView ka render process crash hua tha (isi se screen blank/solid colour reh jati hai)
                recreateWebView()
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"

                if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                return try {
                    chooser.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                return openPopupWindow(resultMsg)
            }
        }

        return w
    }

    private fun showErrorPage(view: WebView, detail: String) {
        loader.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        view.loadUrl("file:///android_asset/offline.html?e=" + Uri.encode(detail))
    }

    private fun recreateWebView() {
        try {
            swipeRefresh.removeView(web)
            web.destroy()
        } catch (e: Exception) {}
        web = createWebView()
        swipeRefresh.addView(web)
        Toast.makeText(this, "Page reload ho raha hai...", Toast.LENGTH_SHORT).show()
        web.loadUrl(startUrl)
    }

    // ───────────── popup (Firebase / Google / GitHub login) ─────────────
    private fun openPopupWindow(resultMsg: Message): Boolean {
        val popup = WebView(this)
        popup.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = web.settings.userAgentString
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.setContentView(
            popup,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        dialog.setOnDismissListener {
            try { popup.destroy() } catch (e: Exception) {}
        }

        var approved = false   // true = login popup, dialog mein dikh raha hai
        var closed = false

        fun showOnce() {
            if (!approved && !closed) {
                approved = true
                if (!isFinishing) dialog.show()
            }
        }
        fun closePopup() {
            if (closed) return
            closed = true
            // callback ke andar hi destroy karna crash kar sakta hai, is liye thori der baad
            ui.post {
                try { if (dialog.isShowing) dialog.dismiss() else popup.destroy() } catch (e: Exception) {}
            }
        }

        /** true = ye navigation yahan rok di (kahin aur handle ho gayi) */
        fun route(uri: Uri): Boolean {
            if (closed) return true
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript") return false
                openExternal(uri)
                if (!approved) closePopup()
                return true
            }
            if (isDownloadLink(uri)) {
                handleDownloadRequest(uri.toString(), popup.settings.userAgentString, null, null)
                if (!approved) closePopup()
                return true
            }
            if (approved) return false   // login flow chal raha hai: sab allow
            return when {
                isAuthUrl(uri) -> { showOnce(); false }
                keepInsideApp || isInternal(uri.host) -> {
                    web.loadUrl(uri.toString())          // apni site ka page: main WebView mein
                    closePopup()
                    true
                }
                else -> {
                    openExternal(uri)                    // koi aur website: Chrome mein
                    closePopup()
                    true
                }
            }
        }

        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return route(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // agar pehla link shouldOverrideUrlLoading se nahi guzra (window.open + JS redirect) to yahan faisla
                if (!approved && !closed && url != null && url != "about:blank") {
                    if (route(Uri.parse(url))) view?.stopLoading()
                }
            }
        }

        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                closePopup()
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                return openPopupWindow(resultMsg)
            }
        }

        // aakhri safety: 1.5 sec baad bhi popup ka faisla na hua to dikha do
        ui.postDelayed({ if (!approved && !closed && !isFinishing) showOnce() }, 1500)

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    // ───────────── downloads (DownloadManager + in-app progress popup) ─────────────
    private fun handleDownloadRequest(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Toast.makeText(this, "Is download ko dobara try karein", Toast.LENGTH_SHORT).show()
            return
        }
        val p = PendingDownload(url, userAgent, contentDisposition, mimeType)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = p
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(p)
    }

    private fun enqueueDownload(p: PendingDownload) {
        try {
            val fileName = URLUtil.guessFileName(p.url, p.contentDisposition, p.mimeType)
            val req = DownloadManager.Request(Uri.parse(p.url)).apply {
                setTitle(fileName)
                setDescription("Downloading...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val mt = p.mimeType
                if (!mt.isNullOrBlank() && mt != "application/octet-stream") setMimeType(mt)
                if (!p.userAgent.isNullOrBlank()) addRequestHeader("User-Agent", p.userAgent)
                CookieManager.getInstance().getCookie(p.url)?.let { addRequestHeader("Cookie", it) }
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(req)
            showDownloadDialog(dm, id, fileName)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Download start nahi ho saka", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDownloadDialog(dm: DownloadManager, id: Long, fileName: String) {
        if (isFinishing) return
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val nameTv = TextView(this).apply { text = fileName; textSize = 15f }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val statusTv = TextView(this).apply { text = "Starting..."; textSize = 13f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameTv)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
            addView(statusTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 3 })
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Download")
            .setView(box)
            .setNegativeButton("Hide", null)
            .setNeutralButton("Cancel", null)
            .setPositiveButton("Open", null)
            .create()
        dialog.show()

        val openBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        openBtn.visibility = View.GONE
        cancelBtn.setOnClickListener {
            try { dm.remove(id) } catch (e: Exception) {}
            Toast.makeText(this, "Download cancel kar diya", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        openBtn.setOnClickListener {
            openDownloaded(dm, id, fileName)
            dialog.dismiss()
        }

        val poll = object : Runnable {
            override fun run() {
                var keepGoing = true
                try {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) {
                            keepGoing = false
                            return@use
                        }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                bar.isIndeterminate = false
                                bar.progress = 100
                                statusTv.text = "Download complete"
                                dialog.setTitle("Download complete")
                                openBtn.visibility = View.VISIBLE
                                cancelBtn.visibility = View.GONE
                                keepGoing = false
                            }
                            DownloadManager.STATUS_FAILED -> {
                                bar.isIndeterminate = false
                                statusTv.text = "Download fail ho gaya"
                                dialog.setTitle("Download failed")
                                cancelBtn.visibility = View.GONE
                                keepGoing = false
                            }
                            DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                                bar.isIndeterminate = true
                                statusTv.text = "Waiting for network..."
                            }
                            else -> {
                                if (total > 0) {
                                    bar.isIndeterminate = false
                                    bar.progress = ((done * 100) / total).toInt()
                                    statusTv.text = "${Formatter.formatShortFileSize(this@MainActivity, done)} / " +
                                        "${Formatter.formatShortFileSize(this@MainActivity, total)}  (${bar.progress}%)"
                                } else {
                                    bar.isIndeterminate = true
                                    statusTv.text = Formatter.formatShortFileSize(this@MainActivity, done)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    keepGoing = false
                }
                if (keepGoing && dialog.isShowing) ui.postDelayed(this, 500)
            }
        }
        dialog.setOnDismissListener { ui.removeCallbacks(poll) }   // "Hide" par download background mein chalta rahta hai (notification mein)
        ui.post(poll)
    }

    private fun openDownloaded(dm: DownloadManager, id: Long, fileName: String) {
        try {
            val uri = dm.getUriForDownloadedFile(id)
            var mime = dm.getMimeTypeForDownloadedFile(id)
            if (fileName.lowercase().endsWith(".apk")) mime = "application/vnd.android.package-archive"
            if (uri == null) throw IllegalStateException("no uri")
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Toast.makeText(this, "File Downloads folder mein save hai", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ───────────── blob downloads (website ke JS se) ─────────────
    inner class WebAppInterface {
        @JavascriptInterface
        fun downloadBase64(filename: String, base64Data: String) {
            try {
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        val outputStream = contentResolver.openOutputStream(uri)
                        outputStream?.write(decodedBytes)
                        outputStream?.close()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val file = File(downloadsDir, filename)
                    val fos = FileOutputStream(file)
                    fos.write(decodedBytes)
                    fos.close()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
