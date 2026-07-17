package com.example.tailscaletoggle

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class DownloadItem(
    val name: String,
    val file: File? = null,
    val artworkFile: File? = null,
    val downloadId: Long? = null,
    val progress: Int = 0,
    val status: Int = -1 // DownloadManager.STATUS_*
)

class MainActivity : AppCompatActivity() {

    private lateinit var serviceErrorLayout: LinearLayout
    private lateinit var serviceProgressBar: ProgressBar
    private lateinit var serviceStatusText: TextView
    
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var videoView: VideoView
    private lateinit var playerBackButton: View
    private lateinit var tabLayout: TabLayout
    private lateinit var webviewSeerr: WebView
    private lateinit var webviewJellyfin: WebView
    private lateinit var webviewYoutube: WebView
    private lateinit var youtubeDownloadBar: LinearLayout
    private lateinit var btnDownloadYoutube: com.google.android.material.button.MaterialButton
    private lateinit var youtubeDownloadProgress: ProgressBar
    private lateinit var youtubeDownloadStatus: TextView
    private lateinit var downloadsList: RecyclerView
    private lateinit var noDownloadsText: TextView

    private val SEERR_URL = "http://100.112.127.74:5055"
    private val JELLYFIN_URL = "http://100.112.127.74:8096"
    private val YOUTUBE_URL = "https://www.youtube.com"
    private val YOUTUBE_DOWNLOAD_API = "http://100.112.127.74:8000/download"

    private var isServerConfirmed = false
    private var isDownloadingYoutube = false
    private var isCheckingReachability = false
    private var reachabilityFailureCount = 0
    private var lastRetryTimestamp = 0L
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var progressPollingJob: Job? = null
    private var isActivityVisible = false
    private val uiUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiUpdateRunnable = Runnable { updateUIState() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            runOnUiThread { updateUIState() }
        }
        override fun onLost(network: android.net.Network) {
            runOnUiThread { updateUIState() }
        }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            runOnUiThread { updateUIState() }
        }
    }

    private val moviesFolder: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Movies")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(networkCallback)

        // Initialize Views
        serviceErrorLayout = findViewById(R.id.serviceErrorLayout)
        serviceProgressBar = findViewById(R.id.serviceProgressBar)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        videoView = findViewById(R.id.videoView)
        playerBackButton = findViewById<View>(R.id.playerBackButton)

        tabLayout = findViewById(R.id.tabLayout)
        webviewSeerr = findViewById(R.id.webviewSeerr)
        webviewJellyfin = findViewById(R.id.webviewJellyfin)
        webviewYoutube = findViewById(R.id.webviewYoutube)
        youtubeDownloadBar = findViewById(R.id.youtubeDownloadBar)
        btnDownloadYoutube = findViewById(R.id.btnDownloadYoutube)
        youtubeDownloadProgress = findViewById(R.id.youtubeDownloadProgress)
        youtubeDownloadStatus = findViewById(R.id.youtubeDownloadStatus)
        downloadsList = findViewById(R.id.downloadsList)
        noDownloadsText = findViewById(R.id.noDownloadsText)

        downloadsList.layoutManager = LinearLayoutManager(this)

        btnDownloadYoutube.setOnClickListener {
            downloadYoutubeVideo()
        }

        // Setup WebViews
        setupWebView(webviewSeerr, SEERR_URL)
        setupWebView(webviewJellyfin, JELLYFIN_URL)
        setupWebView(webviewYoutube, YOUTUBE_URL)

        // Setup Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                handleTabSelection(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Startup logic: Must have actual internet AND VPN to start on Watch.
        if (isNetworkAvailable() && isVpnActive()) {
            tabLayout.getTabAt(1)?.select()
        } else {
            tabLayout.getTabAt(3)?.select()
        }

        // Start VPN monitoring is now handled in onStart
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        updateUIState()
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback might not be registered
        }
    }

    private fun handleTabSelection(position: Int) {
        val showSeerr = position == 0 && isServerConfirmed
        val showJellyfin = position == 1 && isServerConfirmed
        val showDownloads = position == 3
        val showYoutube = position == 2
        val showError = (position == 0 || position == 1) && !isServerConfirmed

        // Only update visibility if it actually changed to prevent focus loss/keyboard flickers
        if (webviewSeerr.isVisible != showSeerr) webviewSeerr.isVisible = showSeerr
        if (webviewJellyfin.isVisible != showJellyfin) webviewJellyfin.isVisible = showJellyfin
        if (webviewYoutube.isVisible != showYoutube) webviewYoutube.isVisible = showYoutube
        
        updateYoutubeDownloadBarVisibility()
        
        if (showDownloads) {
            checkStoragePermissionAndLoadDownloads()
            if (progressPollingJob == null) startProgressPolling()
        } else {
            stopProgressPolling()
        }

        // Handle downloads list and no downloads text visibility inside loadDownloads() 
        // but hide them if we aren't on the downloads tab
        if (!showDownloads) {
            downloadsList.isVisible = false
            noDownloadsText.isVisible = false
        }

        if (serviceErrorLayout.isVisible != showError) serviceErrorLayout.isVisible = showError
    }

    private fun startProgressPolling() {
        progressPollingJob = lifecycleScope.launch {
            while (true) {
                loadDownloads()
                delay(1000)
            }
        }
    }

    private fun stopProgressPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = null
    }

    private fun checkStoragePermissionAndLoadDownloads() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadDownloads()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 102)
        }
    }

    private fun loadDownloads() {
        if (!moviesFolder.exists()) {
            moviesFolder.mkdirs()
        }

        // 1. Get all active and pending downloads from the system
        val activeDownloads = getActiveDownloads()
        
        // 2. Identify all paths currently being managed by DownloadManager
        val managedPaths = activeDownloads.mapNotNull { it.file?.absolutePath }.toSet()

        // 3. Scan the folder for files that are NOT currently being downloaded
        val completedFiles = moviesFolder.listFiles { file ->
            val name = file.name.lowercase()
            val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || 
                         name.endsWith(".avi") || name.endsWith(".mov")
            
            // Only include if it's a video AND not in the "managed" list of active downloads
            isVideo && !managedPaths.contains(file.absolutePath)
        }?.map { DownloadItem(it.name, file = it) } ?: emptyList()

        val allItems = (activeDownloads + completedFiles).sortedBy { it.name }

        runOnUiThread {
            if (allItems.isEmpty()) {
                downloadsList.isVisible = false
                noDownloadsText.isVisible = true
            } else {
                downloadsList.isVisible = true
                noDownloadsText.isVisible = false
                val adapter = downloadsList.adapter as? DownloadsAdapter
                if (adapter == null) {
                    downloadsList.adapter = DownloadsAdapter(allItems, 
                        onClick = { item -> item.file?.let { playVideo(it) } },
                        onDelete = { item -> item.file?.let { showDeleteConfirmation(it) } }
                    )
                } else {
                    adapter.updateItems(allItems)
                }
            }
        }
    }

    private fun getActiveDownloads(): List<DownloadItem> {
        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PAUSED or DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING
        )
        
        val items = mutableListOf<DownloadItem>()
        var cursor: Cursor? = null
        try {
            cursor = downloadManager.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    
                    val file = localUri?.let { Uri.parse(it).path?.let { path -> File(path) } }
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    
                    items.add(DownloadItem(name = title, file = file, downloadId = id, progress = progress, status = status))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return items
    }

    private fun showDeleteConfirmation(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete Movie")
            .setMessage("Are you sure you want to delete '${file.name}' permanently?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                    loadDownloads()
                } else {
                    Toast.makeText(this, "Failed to delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        
        fullscreenContainer.isVisible = true
        videoView.isVisible = true
        playerBackButton.isVisible = false // Hidden initially, will sync with controller

        playerBackButton.setOnClickListener {
            stopInAppPlayback()
        }
        
        val mediaController = SimplifiedMediaController(this)
        mediaController.setAnchorView(videoView)
        
        videoView.setMediaController(mediaController)
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewWidth = videoView.width
                val tapX = e.x
                val currentPos = videoView.currentPosition
                
                if (tapX < viewWidth / 2) {
                    // Left side: Rewind 10s
                    videoView.seekTo((currentPos - 10000).coerceAtLeast(0))
                    Toast.makeText(this@MainActivity, "-10s", Toast.LENGTH_SHORT).show()
                } else {
                    // Right side: Forward 10s
                    videoView.seekTo((currentPos + 10000).coerceAtMost(videoView.duration))
                    Toast.makeText(this@MainActivity, "+10s", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mediaController.isShowing) {
                    mediaController.hide()
                } else {
                    mediaController.show()
                }
                return true
            }
        })

        videoView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
        
        videoView.setOnPreparedListener {
            it.start()
        }
        
        videoView.setOnCompletionListener {
            stopInAppPlayback()
        }

        // Hide system bars for immersive playback
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun stopInAppPlayback() {
        videoView.stopPlayback()
        videoView.isVisible = false
        playerBackButton.isVisible = false
        fullscreenContainer.isVisible = false
        
        // Restore system bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupWebView(webView: WebView, url: String) {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        webView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
            }
            false
        }
        
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    onHideCustomView()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenContainer.isVisible = true
                
                // Immersive mode: hide status and navigation bars
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                fullscreenContainer.isVisible = false
                
                // Restore system bars
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            private fun isServerUrl(url: String?): Boolean {
                return url?.startsWith(SEERR_URL) == true || url?.startsWith(JELLYFIN_URL) == true
            }

            private fun shouldRetry(errorCode: Int): Boolean {
                return errorCode == ERROR_CONNECT ||
                       errorCode == ERROR_TIMEOUT ||
                       errorCode == ERROR_HOST_LOOKUP ||
                       errorCode == ERROR_IO ||
                       errorCode == -21 // ERR_NETWORK_CHANGED
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // If the main page fails to load, trigger a reconnect
                if (isServerUrl(failingUrl)) {
                    isServerConfirmed = false
                    updateUIState()
                }

                if (shouldRetry(errorCode)) {
                    view?.postDelayed({ view.reload() }, 2000)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Modern version for newer Android levels
                if (request?.isForMainFrame == true) {
                    val url = request.url.toString()
                    if (isServerUrl(url)) {
                        isServerConfirmed = false
                        updateUIState()
                    }

                    if (shouldRetry(error?.errorCode ?: 0)) {
                        view?.postDelayed({ view.reload() }, 2000)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == webviewYoutube) {
                    updateYoutubeDownloadBarVisibility()
                }
            }
        }

        webView.setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(downloadUrl, userAgent, contentDisposition, mimetype)
        }

        webView.loadUrl(url)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url))
            val cookie = CookieManager.getInstance().getCookie(url)
            
            // Capture the current WebView title to help with naming
            val currentWebView = when (tabLayout.selectedTabPosition) {
                0 -> webviewSeerr
                1 -> webviewJellyfin
                2 -> webviewYoutube
                else -> null
            }
            val pageTitle = currentWebView?.title
            
            val fileName = getPrettyFileName(url, contentDisposition, mimetype, pageTitle)
            
            if (!moviesFolder.exists()) {
                moviesFolder.mkdirs()
            }

            request.apply {
                setMimeType(mimetype)
                addRequestHeader("cookie", cookie)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading movie...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Movies/$fileName")
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(applicationContext, "Download started...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getPrettyFileName(url: String, contentDisposition: String?, mimetype: String?, pageTitle: String?): String {
        var fileName: String? = null

        // 1. Try Content-Disposition filename* (modern UTF-8)
        if (contentDisposition != null && contentDisposition.contains("filename*=")) {
            try {
                val start = contentDisposition.indexOf("filename*=") + 10
                val encodedName = contentDisposition.substring(start).split(";")[0].trim()
                if (encodedName.startsWith("UTF-8''")) {
                    fileName = URLDecoder.decode(encodedName.substring(7), "UTF-8")
                }
            } catch (e: Exception) { }
        }

        // 2. Try standard Content-Disposition filename
        if (fileName == null && contentDisposition != null && contentDisposition.contains("filename=")) {
            try {
                val start = contentDisposition.indexOf("filename=") + 9
                fileName = contentDisposition.substring(start).split(";")[0].trim().replace("\"", "")
            } catch (e: Exception) { }
        }

        // 3. Try page title if the filename is generic (like "download.mp4" or "video.mp4")
        val isGeneric = fileName == null || fileName.lowercase().startsWith("download") || 
                        fileName.lowercase().startsWith("video") || fileName.lowercase().startsWith("stream")
        
        if (isGeneric && pageTitle != null && pageTitle.isNotBlank()) {
            // Jellyfin titles often look like "Movie Name - Jellyfin" or "Episode Name - Show Name"
            val cleanTitle = pageTitle.replace(" - Jellyfin", "").trim()
            if (cleanTitle.isNotEmpty()) {
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype) ?: "mp4"
                fileName = "$cleanTitle.$ext"
            }
        }

        // 4. Final fallback
        if (fileName == null) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        }

        // Cleanup: remove technical tags and junk
        val technicalTags = listOf(
            "1080p", "720p", "480p", "2160p", "4k", 
            "x264", "x265", "HEVC", "H264", "H265",
            "WEBRip", "WEB-DL", "BDRip", "BRRip", "BluRay", 
            "HDR", "REPACK", "DTS", "AAC", "AC3", "DD5.1",
            "RARBG", "YIFY", "YTS", "EVO", "PSA", "PROPER", "INTERNAL", "AMZN"
        )
        
        // Remove extension temporarily for cleaning
        val lastDot = fileName!!.lastIndexOf(".")
        var namePart = if (lastDot != -1) fileName.substring(0, lastDot) else fileName
        val extPart = if (lastDot != -1) fileName.substring(lastDot) else ".mp4"
        
        // Replace dots and underscores with spaces BEFORE technical tag removal
        namePart = namePart.replace(".", " ").replace("_", " ")

        technicalTags.forEach { tag ->
            namePart = namePart.replace(Regex("(?i)\\b$tag\\b"), "")
        }

        // Additional cleanup for common patterns like " - GroupName" or "(TechnicalInfo)"
        namePart = namePart.replace(Regex(" -\\s*\\w+\\s*$"), "") // Remove trailing dash and group name
        namePart = namePart.replace(Regex("\\s-\\s*$"), "")      // Remove any lingering trailing dash
        
        // Clean up double spaces and trim
        namePart = namePart.replace(Regex("\\s+"), " ").trim()
        
        return namePart + extPart
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
               caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun isVpnActive(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun updateUIState() {
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        if (!isActivityVisible) return
        
        val networkAvailable = isNetworkAvailable()
        val vpnActive = isVpnActive()
        val tailscaleInstalled = TailscaleController.isTailscaleInstalled(this)
        val withinRetryWindow = System.currentTimeMillis() - lastRetryTimestamp < 10000L
        
        val currentTab = tabLayout.selectedTabPosition
        
        when {
            // Case 1: Tailscale is missing
            !tailscaleInstalled -> {
                isServerConfirmed = false
                serviceStatusText.text = getString(R.string.status_missing)
                serviceProgressBar.isVisible = false
            }
            
            // Case 2: Completely Offline (No WiFi/Data)
            !networkAvailable -> {
                isServerConfirmed = false
                serviceStatusText.text = getString(R.string.status_not_connected)
                serviceProgressBar.isVisible = false
            }
            
            // Case 3: VPN active, check server
            vpnActive -> {
                if (isServerConfirmed) {
                    serviceErrorLayout.isVisible = false
                } else {
                    if (reachabilityFailureCount >= 3) {
                        serviceStatusText.text = getString(R.string.status_not_connected)
                        serviceProgressBar.isVisible = false
                    } else {
                        serviceStatusText.text = getString(R.string.status_loading)
                        serviceProgressBar.isVisible = true
                    }
                    
                    if (!isCheckingReachability) {
                        checkServerReachability()
                    }
                }
            }

            // Case 4: Within retry window - show "Starting Tailscale..." while waiting for VPN
            withinRetryWindow -> {
                isServerConfirmed = false
                serviceStatusText.text = getString(R.string.status_starting_vpn)
                serviceProgressBar.isVisible = true
            }
            
            // Case 5: Network available but VPN not active (and not retrying)
            else -> {
                isServerConfirmed = false
                serviceStatusText.text = getString(R.string.status_starting_vpn)
                serviceProgressBar.isVisible = true
                
                // Auto-connect
                lastRetryTimestamp = System.currentTimeMillis()
                TailscaleController.connect(this)
            }
        }

        handleTabSelection(currentTab)
        uiUpdateHandler.postDelayed(uiUpdateRunnable, 2000)
    }

    private fun checkServerReachability() {
        isCheckingReachability = true
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    val connection = URL(SEERR_URL).openConnection() as HttpURLConnection
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000
                    val responseCode = connection.responseCode
                    responseCode in 200..399
                } catch (e: Exception) {
                    false
                }
            }
            
            if (reachable) {
                isServerConfirmed = true
                reachabilityFailureCount = 0
                Toast.makeText(this@MainActivity, "Connected to server!", Toast.LENGTH_SHORT).show()
                webviewSeerr.reload()
                webviewJellyfin.reload()
            } else {
                reachabilityFailureCount++
                if (reachabilityFailureCount >= 3) {
                    // Periodic "poke" to Tailscale in case the VPN tunnel is hung
                    if (reachabilityFailureCount % 5 == 0) {
                        TailscaleController.connect(this@MainActivity)
                    }
                    
                    if (reachabilityFailureCount == 3) {
                        Toast.makeText(this@MainActivity, "Server unreachable. Retrying...", Toast.LENGTH_LONG).show()
                    }
                    delay(5000) // Back off slightly when failing
                } else {
                    delay(2000)
                }
            }
            isCheckingReachability = false
        }
    }

    private fun downloadYoutubeVideo() {
        val url = webviewYoutube.url ?: return
        if (!url.contains("youtube.com/watch")) {
            Toast.makeText(this, "Please navigate to a video first", Toast.LENGTH_SHORT).show()
            return
        }

        btnDownloadYoutube.isEnabled = false
        youtubeDownloadProgress.isVisible = true
        youtubeDownloadStatus.isVisible = true
        youtubeDownloadStatus.text = getString(R.string.download_started)
        isDownloadingYoutube = true
        updateYoutubeDownloadBarVisibility()

        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                val connection = URL(YOUTUBE_DOWNLOAD_API).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                
                val jsonInputString = "{\"url\": \"$url\"}"
                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                responseCode in 200..299
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                youtubeDownloadProgress.isVisible = false
                if (success) {
                    youtubeDownloadStatus.text = getString(R.string.download_success)
                } else {
                    youtubeDownloadStatus.text = getString(R.string.download_failed)
                    btnDownloadYoutube.isEnabled = true
                    isDownloadingYoutube = false
                    updateYoutubeDownloadBarVisibility()
                }
                
                // Reset status after a delay
                delay(7000)
                if (success) {
                    youtubeDownloadStatus.isVisible = false
                    btnDownloadYoutube.isEnabled = true
                    isDownloadingYoutube = false
                    updateYoutubeDownloadBarVisibility()
                }
            }
        }
    }

    private fun updateYoutubeDownloadBarVisibility() {
        val isYoutubeTab = tabLayout.selectedTabPosition == 2
        val isWatchingVideo = webviewYoutube.url?.contains("watch") == true
        val shouldShow = isYoutubeTab && (isWatchingVideo || isDownloadingYoutube)
        
        if (youtubeDownloadBar.isVisible != shouldShow) {
            youtubeDownloadBar.isVisible = shouldShow
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (videoView.isVisible) {
            stopInAppPlayback()
            return
        }

        if (customView != null) {
            val chromeClient = when {
                webviewSeerr.isVisible -> webviewSeerr.webChromeClient
                webviewJellyfin.isVisible -> webviewJellyfin.webChromeClient
                webviewYoutube.isVisible -> webviewYoutube.webChromeClient
                else -> null
            }
            chromeClient?.onHideCustomView()
            return
        }

        if (webviewSeerr.isVisible && webviewSeerr.canGoBack()) {
            webviewSeerr.goBack()
            return
        }
        if (webviewJellyfin.isVisible && webviewJellyfin.canGoBack()) {
            webviewJellyfin.goBack()
            return
        }
        if (webviewYoutube.isVisible && webviewYoutube.canGoBack()) {
            webviewYoutube.goBack()
            return
        }

        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadDownloads()
        }
    }

    private inner class DownloadsAdapter(
        private var items: List<DownloadItem>,
        private val onClick: (DownloadItem) -> Unit,
        private val onDelete: (DownloadItem) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val fileNameText: TextView = view.findViewById(R.id.fileNameText)
            val deleteButton: View = view.findViewById(R.id.deleteButton)
            val playIcon: View = view.findViewById(R.id.playIcon)
            val posterCard: View = view.findViewById(R.id.posterCard)
            val posterThumbnail: android.widget.ImageView = view.findViewById(R.id.posterThumbnail)
            val progressBar: ProgressBar = view.findViewById(R.id.downloadProgressBar)
            val statusText: TextView = view.findViewById(R.id.statusText)
        }

        fun updateItems(newItems: List<DownloadItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            
            if (item.downloadId != null) {
                // Active Download
                holder.fileNameText.isVisible = false // User wants title hidden until finished
                holder.playIcon.isVisible = true // Show play icon as placeholder
                holder.posterCard.isVisible = false
                holder.deleteButton.isVisible = false
                holder.progressBar.isVisible = true
                holder.statusText.isVisible = true
                holder.progressBar.progress = item.progress
                holder.statusText.text = when (item.status) {
                    DownloadManager.STATUS_PENDING -> "Pending: ${item.name}"
                    DownloadManager.STATUS_PAUSED -> "Paused: ${item.name}"
                    else -> "${item.progress}% downloading: ${item.name}"
                }
            } else {
                // Completed File
                holder.fileNameText.isVisible = true
                holder.fileNameText.text = item.name
                
                if (item.artworkFile != null) {
                    holder.playIcon.isVisible = false
                    holder.posterCard.isVisible = true
                    Glide.with(holder.itemView.context)
                        .load(item.artworkFile)
                        .centerCrop()
                        .into(holder.posterThumbnail)
                } else {
                    holder.playIcon.isVisible = true
                    holder.posterCard.isVisible = false
                }

                holder.deleteButton.isVisible = true
                holder.progressBar.isVisible = false
                holder.statusText.isVisible = false
                holder.itemView.setOnClickListener { onClick(item) }
                holder.deleteButton.setOnClickListener { onDelete(item) }
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class SimplifiedMediaController(context: Context) : MediaController(context) {
        override fun setAnchorView(view: View?) {
            super.setAnchorView(view)
            
            // Hide unwanted buttons from the standard controller
            val ffwdId = Resources.getSystem().getIdentifier("ffwd", "id", "android")
            val rewId = Resources.getSystem().getIdentifier("rew", "id", "android")
            val nextId = Resources.getSystem().getIdentifier("next", "id", "android")
            val prevId = Resources.getSystem().getIdentifier("prev", "id", "android")

            findViewById<View>(ffwdId)?.isVisible = false
            findViewById<View>(rewId)?.isVisible = false
            findViewById<View>(nextId)?.isVisible = false
            findViewById<View>(prevId)?.isVisible = false

            // Set a semi-transparent black background to "extend the shading" to the bottom
            this.setBackgroundColor(Color.argb(180, 0, 0, 0))

            // Add safe bottom padding to fix the cut-off time display
            // 64dp is safe for almost all device notch/gesture combinations
            val bottomPadding = (64 * Resources.getSystem().displayMetrics.density).toInt()
            this.setPadding(0, 0, 0, bottomPadding)
        }

        override fun show(timeout: Int) {
            super.show(timeout)
            playerBackButton.isVisible = true
        }

        override fun hide() {
            super.hide()
            playerBackButton.isVisible = false
        }
    }
}