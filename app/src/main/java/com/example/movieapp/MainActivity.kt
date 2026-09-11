package com.example.movieapp

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Rational
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.text.method.ScrollingMovementMethod
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class DownloadItem(
    val name: String,
    val file: File? = null,
    val artworkFile: File? = null,
    val downloadId: Long? = null,
    val movieDownloadId: String? = null,
    val progress: Int = 0,
    val statusText: String = "",
    val status: Int = -1 // DownloadManager.STATUS_*
)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private val ACTION_PIP_TOGGLE_PLAY_PAUSE = "com.example.movieapp.PIP_TOGGLE_PLAY_PAUSE"

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_PLAY_PAUSE) {
                toggleCenterPlayPause()
            }
        }
    }

    private var isWebVideoPlaying = false

    inner class WebVideoInterface {
        @JavascriptInterface
        fun onVideoStateChanged(playing: Boolean) {
            runOnUiThread {
                isWebVideoPlaying = playing
                updatePipParams()
            }
        }
    }

    private var downloadsTabTapCount = 0
    private var lastDownloadsTabTapTime = 0L
    private var jellyfinTabTapCount = 0
    private var lastJellyfinTabTapTime = 0L

    private lateinit var serviceErrorLayout: View
    private lateinit var servicePenguinImage: android.widget.ImageView
    private lateinit var serviceProgressBar: ProgressBar
    private lateinit var serviceStatusText: TextView
    
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var playerBackButton: View
    private lateinit var playerControlsOverlay: View
    private lateinit var playerTitleText: TextView
    private lateinit var btnSubtitles: ImageButton
    private lateinit var btnCenterPlayPause: View
    private lateinit var imgCenterPlayPause: android.widget.ImageView
    private lateinit var seekFeedbackLeft: View
    private lateinit var seekFeedbackRight: View
    private lateinit var playerSeekBar: android.widget.SeekBar
    private lateinit var playerCurrentTimeText: TextView
    private lateinit var playerDurationText: TextView
    private var isScrubbingSeekBar = false
    private var lastScrubSeekTimeMs = 0L

    private val overlayHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val overlayHideRunnable = Runnable { hidePlayerOverlay() }
    private val seekFeedbackLeftRunnable = Runnable { hideSeekFeedback(seekFeedbackLeft) }
    private val seekFeedbackRightRunnable = Runnable { hideSeekFeedback(seekFeedbackRight) }
    private val seekBarUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seekBarUpdateRunnable = object : Runnable {
        override fun run() {
            updateSeekBarProgress()
            seekBarUpdateHandler.postDelayed(this, 500)
        }
    }
    private lateinit var tabLayout: TabLayout
    private lateinit var webviewSeerr: WebView
    private lateinit var webviewJellyfin: WebView
    private lateinit var webviewQbittorrent: WebView
    private lateinit var webviewRadarr: WebView
    private lateinit var webviewSonarr: WebView
    private lateinit var webviewProwlarr: WebView
    private lateinit var webviewYoutube: WebView
    private lateinit var youtubeDownloadBar: View
    private lateinit var btnDownloadYoutube: com.google.android.material.button.MaterialButton
    private lateinit var youtubeDownloadProgress: ProgressBar
    private lateinit var youtubeDownloadStatus: TextView
    private lateinit var downloadsList: RecyclerView
    private lateinit var emptyDownloadsContainer: LinearLayout
    private lateinit var noDownloadsText: TextView
    private lateinit var logsContainer: LinearLayout
    private lateinit var logsTextView: TextView
    private lateinit var btnCopyLogs: com.google.android.material.button.MaterialButton
    private lateinit var btnClearLogs: com.google.android.material.button.MaterialButton

    private val SEERR_URL = "http://100.112.127.74:5055"
    private val JELLYFIN_URL = "http://100.112.127.74:8096"
    private val QBITTORRENT_URL = "http://100.112.127.74:5080"
    private val RADARR_URL = "http://100.112.127.74:7878"
    private val SONARR_URL = "http://100.112.127.74:8989"
    private val PROWLARR_URL = "http://100.112.127.74:9696"
    private val YOUTUBE_URL = "https://www.youtube.com"
    private val YOUTUBE_DOWNLOAD_API = "http://100.112.127.74:8000/download"

    private var currentlyPlayingFile: File? = null
    private var activeMediaController: SimplifiedMediaController? = null
    private var isInitialStartup = true
    private val MAX_REACHABILITY_ATTEMPTS = 10
    private var isServerConfirmed = false
    private var isSeerrError = false
    private var isJellyfinError = false
    private var isQbittorrentError = false
    private var isRadarrError = false
    private var isSonarrError = false
    private var isProwlarrError = false
    private var isYoutubeError = false
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
        servicePenguinImage = findViewById(R.id.servicePenguinImage)
        serviceProgressBar = findViewById(R.id.serviceProgressBar)
        serviceStatusText = findViewById(R.id.serviceStatusText)
        
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        playerView = findViewById(R.id.playerView)
        playerBackButton = findViewById<View>(R.id.playerBackButton)
        playerControlsOverlay = findViewById(R.id.playerControlsOverlay)
        playerTitleText = findViewById(R.id.playerTitleText)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnSubtitles.setOnClickListener {
            handleSubtitleButtonClick()
        }
        btnCenterPlayPause = findViewById(R.id.btnCenterPlayPause)
        imgCenterPlayPause = findViewById(R.id.imgCenterPlayPause)
        seekFeedbackLeft = findViewById(R.id.seekFeedbackLeft)
        seekFeedbackRight = findViewById(R.id.seekFeedbackRight)
        playerSeekBar = findViewById(R.id.playerSeekBar)
        playerCurrentTimeText = findViewById(R.id.playerCurrentTimeText)
        playerDurationText = findViewById(R.id.playerDurationText)

        playerSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val player = exoPlayer ?: return
                    val duration = player.duration
                    if (duration > 0) {
                        val newTimeMs = (progress.toLong() * duration) / 1000L
                        playerCurrentTimeText.text = formatTimeMs(newTimeMs)
                        
                        val now = System.currentTimeMillis()
                        if (now - lastScrubSeekTimeMs > 80L || kotlin.math.abs(newTimeMs - player.currentPosition) > 5000L) {
                            lastScrubSeekTimeMs = now
                            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                            player.seekTo(newTimeMs)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isScrubbingSeekBar = true
                overlayHideHandler.removeCallbacks(overlayHideRunnable)
                exoPlayer?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                isScrubbingSeekBar = false
                val player = exoPlayer
                val sb = seekBar
                if (player != null && sb != null) {
                    val duration = player.duration
                    if (duration > 0) {
                        val seekTarget = (sb.progress.toLong() * duration) / 1000L
                        player.setSeekParameters(SeekParameters.DEFAULT)
                        player.seekTo(seekTarget)
                    }
                }
                showPlayerOverlay()
            }
        })

        tabLayout = findViewById(R.id.tabLayout)
        webviewSeerr = findViewById(R.id.webviewSeerr)
        webviewJellyfin = findViewById(R.id.webviewJellyfin)
        webviewQbittorrent = findViewById(R.id.webviewQbittorrent)
        webviewRadarr = findViewById(R.id.webviewRadarr)
        webviewSonarr = findViewById(R.id.webviewSonarr)
        webviewProwlarr = findViewById(R.id.webviewProwlarr)
        webviewYoutube = findViewById(R.id.webviewYoutube)
        youtubeDownloadBar = findViewById(R.id.youtubeDownloadBar)
        btnDownloadYoutube = findViewById(R.id.btnDownloadYoutube)
        youtubeDownloadProgress = findViewById(R.id.youtubeDownloadProgress)
        youtubeDownloadStatus = findViewById(R.id.youtubeDownloadStatus)
        downloadsList = findViewById(R.id.downloadsList)
        emptyDownloadsContainer = findViewById(R.id.emptyDownloadsContainer)
        noDownloadsText = findViewById(R.id.noDownloadsText)
        logsContainer = findViewById(R.id.logsContainer)
        logsTextView = findViewById(R.id.logsTextView)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        downloadsList.layoutManager = LinearLayoutManager(this)

        // Assign tags for programmatic identification without needing visible text labels
        tabLayout.getTabAt(0)?.tag = "discover"
        tabLayout.getTabAt(1)?.tag = "watch"
        tabLayout.getTabAt(2)?.tag = "youtube"
        tabLayout.getTabAt(3)?.tag = "downloads"

        btnDownloadYoutube.setOnClickListener {
            downloadYoutubeVideo()
        }

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", logsTextView.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            try {
                Runtime.getRuntime().exec("logcat -c")
                logsTextView.text = ""
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to clear logs", Toast.LENGTH_SHORT).show()
            }
        }

        // Setup WebViews
        setupWebView(webviewSeerr, SEERR_URL)
        setupWebView(webviewJellyfin, JELLYFIN_URL)
        setupWebView(webviewQbittorrent, QBITTORRENT_URL)
        setupWebView(webviewRadarr, RADARR_URL)
        setupWebView(webviewSonarr, SONARR_URL)
        setupWebView(webviewProwlarr, PROWLARR_URL)
        setupWebView(webviewYoutube, YOUTUBE_URL)

        // Observe DownloadRepository updates
        lifecycleScope.launch {
            DownloadRepository.downloads.collect {
                if (tabLayout.getTabAt(tabLayout.selectedTabPosition)?.tag == "downloads") {
                    loadDownloads()
                }
            }
        }

        // Setup Tabs
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                handleTabSelection(tab)
                when (tab?.tag) {
                    "watch" -> handleJellyfinTabTap()
                    "downloads" -> handleDownloadsTabTap()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                when (tab?.tag) {
                    "watch" -> handleJellyfinTabTap()
                    "downloads" -> handleDownloadsTabTap()
                }
            }
        })

        // Startup logic: Start on Watch tab by default while Tailscale initializes.
        tabLayout.getTabAt(1)?.select()

        // Start VPN monitoring is now handled in onStart
        
        observeOngoingYoutubeDownloads()

        val pipFilter = IntentFilter(ACTION_PIP_TOGGLE_PLAY_PAUSE)
        ContextCompat.registerReceiver(this, pipReceiver, pipFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        updateUIState()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode && currentlyPlayingFile != null && exoPlayer?.isPlaying == true) {
            savePlaybackPosition()
            exoPlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
        uiUpdateHandler.removeCallbacks(uiUpdateRunnable)
        if (!isInPictureInPictureMode) {
            if (currentlyPlayingFile != null) {
                savePlaybackPosition()
            }
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentlyPlayingFile != null) {
            savePlaybackPosition()
        }
        exoPlayer?.release()
        exoPlayer = null
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback might not be registered
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = (exoPlayer?.isPlaying == true) || isWebVideoPlaying
            val canEnterPip = isPlaying || customView != null
            if (canEnterPip && !isInPictureInPictureMode) {
                val width = exoPlayer?.videoSize?.width ?: 16
                val height = exoPlayer?.videoSize?.height ?: 9
                val rational = if (width > 0 && height > 0) {
                    val r = Rational(width, height)
                    val floatVal = r.toFloat()
                    if (floatVal in 0.418f..2.39f) r else Rational(16, 9)
                } else {
                    Rational(16, 9)
                }
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                if (playerView.isVisible && playerView.width > 0 && playerView.height > 0) {
                    val sourceRect = Rect()
                    playerView.getGlobalVisibleRect(sourceRect)
                    builder.setSourceRectHint(sourceRect)
                } else if (fullscreenContainer.isVisible && fullscreenContainer.width > 0 && fullscreenContainer.height > 0) {
                    val sourceRect = Rect()
                    fullscreenContainer.getGlobalVisibleRect(sourceRect)
                    builder.setSourceRectHint(sourceRect)
                }

                val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_overlay
                val title = if (isPlaying) "Pause" else "Play"
                val intent = Intent(ACTION_PIP_TOGGLE_PLAY_PAUSE).setPackage(packageName)
                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    1001,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val action = RemoteAction(
                    Icon.createWithResource(this, iconRes),
                    title,
                    title,
                    pendingIntent
                )
                builder.setActions(listOf(action))

                try {
                    enterPictureInPictureMode(builder.build())
                } catch (e: Exception) {
                    Log.e(TAG, "Error entering PiP mode", e)
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            tabLayout.isVisible = false
            playerControlsOverlay.isVisible = false
            playerBackButton.isVisible = false
            seekFeedbackLeft.isVisible = false
            seekFeedbackRight.isVisible = false
            youtubeDownloadBar.isVisible = false
            serviceErrorLayout.isVisible = false
        } else {
            tabLayout.isVisible = true
            if (currentlyPlayingFile != null) {
                fullscreenContainer.isVisible = true
                playerView.isVisible = true
                playerBackButton.isVisible = true
                showPlayerOverlay()
            } else if (customView != null) {
                fullscreenContainer.isVisible = true
            } else {
                fullscreenContainer.isVisible = false
                playerView.isVisible = false
            }
            updateYoutubeDownloadBarVisibility()
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isExoPlaying = currentlyPlayingFile != null &&
                    exoPlayer?.playWhenReady == true &&
                    exoPlayer?.playbackState != Player.STATE_ENDED
            val isPlaying = if (currentlyPlayingFile != null) isExoPlaying else isWebVideoPlaying
            val builder = PictureInPictureParams.Builder()

            val width = exoPlayer?.videoSize?.width ?: 16
            val height = exoPlayer?.videoSize?.height ?: 9
            val rational = if (width > 0 && height > 0) {
                val r = Rational(width, height)
                val floatVal = r.toFloat()
                if (floatVal in 0.418f..2.39f) r else Rational(16, 9)
            } else {
                Rational(16, 9)
            }
            builder.setAspectRatio(rational)

            if (playerView.isVisible && playerView.width > 0 && playerView.height > 0) {
                val sourceRect = Rect()
                playerView.getGlobalVisibleRect(sourceRect)
                builder.setSourceRectHint(sourceRect)
            } else if (fullscreenContainer.isVisible && fullscreenContainer.width > 0 && fullscreenContainer.height > 0) {
                val sourceRect = Rect()
                fullscreenContainer.getGlobalVisibleRect(sourceRect)
                builder.setSourceRectHint(sourceRect)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val autoEnter = isPlaying || customView != null
                builder.setAutoEnterEnabled(autoEnter)
            }

            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_overlay
            val title = if (isPlaying) "Pause" else "Play"
            val intent = Intent(ACTION_PIP_TOGGLE_PLAY_PAUSE).setPackage(packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val action = RemoteAction(
                Icon.createWithResource(this, iconRes),
                title,
                title,
                pendingIntent
            )
            builder.setActions(listOf(action))

            try {
                setPictureInPictureParams(builder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Error setting PiP params", e)
            }
        }
    }

    private fun observeOngoingYoutubeDownloads() {
        val workManager = WorkManager.getInstance(this)
        workManager.getWorkInfosByTagLiveData("youtube_download").observe(this) { workInfos ->
            val activeWork = workInfos?.filter { !it.state.isFinished }?.maxByOrNull { it.id.toString() }
            
            if (activeWork != null) {
                if (!isDownloadingYoutube) {
                    btnDownloadYoutube.isEnabled = false
                    youtubeDownloadProgress.isVisible = true
                    youtubeDownloadStatus.isVisible = true
                    youtubeDownloadStatus.text = getString(R.string.download_started)
                    isDownloadingYoutube = true
                    updateYoutubeDownloadBarVisibility()
                }

                // Remove existing observers for this ID to avoid duplication if any (unlikely with this setup but safe)
                workManager.getWorkInfoByIdLiveData(activeWork.id).removeObservers(this)
                workManager.getWorkInfoByIdLiveData(activeWork.id).observe(this) { workInfo ->
                    if (workInfo != null && workInfo.state.isFinished) {
                        youtubeDownloadProgress.isVisible = false
                        if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                            youtubeDownloadStatus.text = getString(R.string.download_success)
                            resetYoutubeDownloadStatusAfterDelay(true)
                        } else {
                            youtubeDownloadStatus.text = getString(R.string.download_failed)
                            resetYoutubeDownloadStatusAfterDelay(false)
                        }
                    }
                }
            }
        }
    }

    private fun handleTabSelection(tab: TabLayout.Tab?) {
        val tabTag = tab?.tag?.toString() ?: ""
        
        val showSeerr = tabTag == "discover" && isServerConfirmed && !isSeerrError
        val showJellyfin = tabTag == "watch" && isServerConfirmed && !isJellyfinError
        val showQbittorrent = tabTag == "qbittorrent" && isServerConfirmed && !isQbittorrentError
        val showRadarr = tabTag == "radarr" && isServerConfirmed && !isRadarrError
        val showSonarr = tabTag == "sonarr" && isServerConfirmed && !isSonarrError
        val showProwlarr = tabTag == "prowlarr" && isServerConfirmed && !isProwlarrError
        val showYoutube = tabTag == "youtube" && !isYoutubeError
        val showDownloads = tabTag == "downloads"
        val showLogs = tabTag == "logs"
        
        val showError = when (tabTag) {
            "discover" -> !isServerConfirmed || isSeerrError
            "watch" -> !isServerConfirmed || isJellyfinError
            "qbittorrent" -> !isServerConfirmed || isQbittorrentError
            "radarr" -> !isServerConfirmed || isRadarrError
            "sonarr" -> !isServerConfirmed || isSonarrError
            "prowlarr" -> !isServerConfirmed || isProwlarrError
            "youtube" -> isYoutubeError
            else -> false
        }

        // Only update visibility if it actually changed to prevent focus loss/keyboard flickers
        if (webviewSeerr.isVisible != showSeerr) {
            webviewSeerr.isVisible = showSeerr
            if (showSeerr) webviewSeerr.onResume() else webviewSeerr.onPause()
        }
        if (webviewJellyfin.isVisible != showJellyfin) {
            webviewJellyfin.isVisible = showJellyfin
            if (showJellyfin) webviewJellyfin.onResume() else webviewJellyfin.onPause()
        }
        if (webviewQbittorrent.isVisible != showQbittorrent) {
            webviewQbittorrent.isVisible = showQbittorrent
            if (showQbittorrent) webviewQbittorrent.onResume() else webviewQbittorrent.onPause()
        }
        if (webviewRadarr.isVisible != showRadarr) {
            webviewRadarr.isVisible = showRadarr
            if (showRadarr) webviewRadarr.onResume() else webviewRadarr.onPause()
        }
        if (webviewSonarr.isVisible != showSonarr) {
            webviewSonarr.isVisible = showSonarr
            if (showSonarr) webviewSonarr.onResume() else webviewSonarr.onPause()
        }
        if (webviewProwlarr.isVisible != showProwlarr) {
            webviewProwlarr.isVisible = showProwlarr
            if (showProwlarr) webviewProwlarr.onResume() else webviewProwlarr.onPause()
        }
        if (webviewYoutube.isVisible != showYoutube) {
            webviewYoutube.isVisible = showYoutube
            if (showYoutube) webviewYoutube.onResume() else webviewYoutube.onPause()
        }
        
        if (logsContainer.isVisible != showLogs) {
            logsContainer.isVisible = showLogs
            if (showLogs) refreshLogs()
        }
        
        // Pause downloaded video if switching away from downloads tab
        if (!showDownloads && exoPlayer?.isPlaying == true) {
            exoPlayer?.pause()
        }
        
        updateYoutubeDownloadBarVisibility()
        
        if (showDownloads) {
            checkStoragePermissionAndLoadDownloads()
            if (progressPollingJob == null) startProgressPolling()
        } else {
            stopProgressPolling()
        }

        // Handle downloads list and empty downloads container visibility inside loadDownloads() 
        // but hide them if we aren't on the downloads tab
        if (!showDownloads) {
            downloadsList.isVisible = false
            emptyDownloadsContainer.isVisible = false
        }

        if (serviceErrorLayout.isVisible != showError) serviceErrorLayout.isVisible = showError
    }

    private fun startProgressPolling() {
        progressPollingJob = lifecycleScope.launch {
            while (true) {
                if (!fullscreenContainer.isVisible) {
                    loadDownloads()
                }
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

    private fun cleanTitle(name: String): String {
        var cleanName = name
        val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm", ".flv", ".mpk", ".m4v", ".3gp")
        for (ext in videoExtensions) {
            if (cleanName.endsWith(ext, ignoreCase = true)) {
                cleanName = cleanName.substring(0, cleanName.length - ext.length)
                break
            }
        }
        return cleanName.trim()
    }

    private fun loadDownloads() {
        if (!moviesFolder.exists()) {
            moviesFolder.mkdirs()
        }

        // 1. Get active downloads from internal DownloadRepository
        val internalDownloads = DownloadRepository.downloads.value.mapNotNull { active ->
            if (active.status == DownloadStatus.COMPLETED) null
            else {
                val st = when (active.status) {
                    DownloadStatus.QUEUED -> "Queued..."
                    DownloadStatus.RETRYING -> "Retrying... (${active.progress}%)"
                    DownloadStatus.PAUSED -> "Paused (${active.progress}%)"
                    DownloadStatus.FAILED -> "Failed: ${active.errorMessage ?: "Network Error"}"
                    else -> "Downloading... ${active.progress}% (${formatSpeed(active.speedBytesPerSec)})"
                }
                DownloadItem(
                    name = cleanTitle(active.title),
                    file = active.destinationFile,
                    movieDownloadId = active.id,
                    progress = active.progress,
                    statusText = st
                )
            }
        }

        // 2. Get active downloads from system DownloadManager
        val activeDownloads = getActiveDownloads()
        
        // 3. Identify all paths currently being managed by active downloads
        val managedPaths = (internalDownloads.mapNotNull { it.file?.absolutePath } + activeDownloads.mapNotNull { it.file?.absolutePath }).toSet()

        // 4. Scan folder for completed video files not currently being downloaded
        val completedFiles = moviesFolder.listFiles { file ->
            val name = file.name.lowercase()
            val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || 
                         name.endsWith(".avi") || name.endsWith(".mov")
            
            isVideo && !managedPaths.contains(file.absolutePath)
        }?.map { file ->
            val baseName = file.nameWithoutExtension
            val parent = file.parentFile
            val possibleArtwork = listOf(
                File(parent, "$baseName.jpg"),
                File(parent, "$baseName.png"),
                File(parent, "$baseName.jpeg"),
                File(parent, "$baseName-poster.jpg"),
                File(parent, "$baseName.poster.jpg")
            ).firstOrNull { it.exists() }

            DownloadItem(
                name = cleanTitle(file.name),
                file = file,
                artworkFile = possibleArtwork
            )
        } ?: emptyList()

        val allItems = (internalDownloads + activeDownloads + completedFiles).sortedBy { it.name }

        runOnUiThread {
            if (allItems.isEmpty()) {
                downloadsList.isVisible = false
                emptyDownloadsContainer.isVisible = true
            } else {
                downloadsList.isVisible = true
                emptyDownloadsContainer.isVisible = false
                val adapter = downloadsList.adapter as? DownloadsAdapter
                if (adapter == null) {
                    downloadsList.adapter = DownloadsAdapter(allItems, 
                        onClick = { item -> item.file?.let { playVideo(it) } },
                        onDelete = { item -> onDownloadItemDelete(item) }
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
        val cursor = try {
            downloadManager.query(query)
        } catch (e: Exception) {
            null
        }

        cursor?.use { c ->
            if (c.moveToFirst()) {
                do {
                    val id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                    val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    
                    val file = localUri?.let { Uri.parse(it).path?.let { path -> File(path) } }
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                    
                    items.add(DownloadItem(name = cleanTitle(title), file = file, downloadId = id, progress = progress, status = status))
                } while (c.moveToNext())
            }
        }
        return items
    }

    private fun onDownloadItemDelete(item: DownloadItem) {
        if (item.movieDownloadId != null) {
            showCancelMovieDownloadConfirmation(item)
        } else if (item.downloadId != null) {
            showCancelConfirmation(item)
        } else {
            item.file?.let { showDeleteConfirmation(it) }
        }
    }

    private fun showCancelMovieDownloadConfirmation(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Download")
            .setMessage("Are you sure you want to cancel the download of '${item.name}'?")
            .setPositiveButton("Cancel Download") { _, _ ->
                item.movieDownloadId?.let { id ->
                    MovieDownloadService.cancelDownload(this, id)
                }
                Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
                loadDownloads()
            }
            .setNegativeButton("Keep Download", null)
            .show()
    }

    private fun showCancelConfirmation(item: DownloadItem) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Download")
            .setMessage("Are you sure you want to cancel the download of '${item.name}'?")
            .setPositiveButton("Cancel Download") { _, _ ->
                cancelDownload(item)
            }
            .setNegativeButton("Keep Download", null)
            .show()
    }

    private fun cancelDownload(item: DownloadItem) {
        val downloadId = item.downloadId ?: return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // remove() stops the download and deletes the partial file
        dm.remove(downloadId)
        Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
        loadDownloads()
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

    private fun formatTimeMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun updateSeekBarProgress() {
        val player = exoPlayer ?: return
        if (isScrubbingSeekBar) return

        val currentPos = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.coerceAtLeast(0)

        playerCurrentTimeText.text = formatTimeMs(currentPos)
        playerDurationText.text = formatTimeMs(duration)

        if (duration > 0) {
            val progress = ((currentPos * 1000) / duration).toInt()
            playerSeekBar.progress = progress.coerceIn(0, 1000)
        } else {
            playerSeekBar.progress = 0
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing
        val newPlayer = ExoPlayer.Builder(this).build()
        playerView.player = newPlayer
        exoPlayer = newPlayer
        return newPlayer
    }

    private fun savePlaybackPosition() {
        val file = currentlyPlayingFile ?: return
        val player = exoPlayer ?: return
        val currentPos = player.currentPosition
        val duration = player.duration
        
        val prefs = getSharedPreferences("video_playback_prefs", Context.MODE_PRIVATE)
        if (duration > 0 && currentPos >= duration - 5000) {
            // Video finished or within 5 seconds of the end: clear saved position
            prefs.edit().remove(file.absolutePath).apply()
        } else if (currentPos > 3000) {
            // Save position if played past 3 seconds
            prefs.edit().putInt(file.absolutePath, currentPos.toInt()).apply()
        }
    }

    private fun getSavedPlaybackPosition(file: File): Int {
        val prefs = getSharedPreferences("video_playback_prefs", Context.MODE_PRIVATE)
        return prefs.getInt(file.absolutePath, 0)
    }

    private fun showPlayerOverlay() {
        if (isInPictureInPictureMode) {
            playerControlsOverlay.isVisible = false
            return
        }
        overlayHideHandler.removeCallbacks(overlayHideRunnable)
        
        playerControlsOverlay.animate().cancel()
        playerControlsOverlay.alpha = 1f
        playerControlsOverlay.isVisible = true

        val player = exoPlayer
        if (player?.isPlaying == true) {
            imgCenterPlayPause.setImageResource(R.drawable.ic_pause)
            overlayHideHandler.postDelayed(overlayHideRunnable, 4000)
        } else {
            imgCenterPlayPause.setImageResource(R.drawable.ic_play_overlay)
        }
    }

    private fun hidePlayerOverlay() {
        overlayHideHandler.removeCallbacks(overlayHideRunnable)
        
        playerControlsOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                playerControlsOverlay.isVisible = false
            }
            .start()
    }

    private fun toggleCenterPlayPause() {
        if (currentlyPlayingFile != null && exoPlayer != null) {
            val player = exoPlayer!!
            if (player.isPlaying) {
                player.pause()
                imgCenterPlayPause.setImageResource(R.drawable.ic_play_overlay)
                overlayHideHandler.removeCallbacks(overlayHideRunnable)
                showPlayerOverlay()
            } else {
                player.play()
                imgCenterPlayPause.setImageResource(R.drawable.ic_pause)
                showPlayerOverlay()
            }
        } else {
            val activeWebView = when {
                customView != null -> {
                    listOf(webviewJellyfin, webviewYoutube, webviewSeerr, webviewQbittorrent, webviewRadarr, webviewSonarr, webviewProwlarr).firstOrNull { it.isVisible }
                }
                webviewJellyfin.isVisible -> webviewJellyfin
                webviewYoutube.isVisible -> webviewYoutube
                webviewSeerr.isVisible -> webviewSeerr
                webviewRadarr.isVisible -> webviewRadarr
                webviewSonarr.isVisible -> webviewSonarr
                webviewProwlarr.isVisible -> webviewProwlarr
                else -> null
            }
            if (activeWebView != null) {
                isWebVideoPlaying = !isWebVideoPlaying
                val js = """
                    (function() {
                        var vs = document.querySelectorAll('video');
                        vs.forEach(function(v) {
                            if (v.paused) {
                                v.play();
                            } else {
                                v.pause();
                            }
                        });
                    })();
                """.trimIndent()
                activeWebView.evaluateJavascript(js, null)
            }
        }
        updatePipParams()
    }

    private fun showSeekFeedback(feedbackView: View) {
        val runnable = if (feedbackView == seekFeedbackLeft) seekFeedbackLeftRunnable else seekFeedbackRightRunnable
        overlayHideHandler.removeCallbacks(runnable)

        feedbackView.animate().cancel()
        feedbackView.alpha = 1f
        feedbackView.scaleX = 1f
        feedbackView.scaleY = 1f
        feedbackView.isVisible = true

        feedbackView.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(150)
            .withEndAction {
                feedbackView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            .start()

        overlayHideHandler.postDelayed(runnable, 900)
    }

    private fun hideSeekFeedback(feedbackView: View) {
        feedbackView.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                feedbackView.isVisible = false
            }
            .start()
    }

    private data class SubtitleTrackInfo(
        val group: Tracks.Group,
        val trackIndex: Int,
        val displayName: String
    )

    private fun getTextTrackInfo(player: ExoPlayer): List<SubtitleTrackInfo> {
        val result = mutableListOf<SubtitleTrackInfo>()
        var trackNumber = 1
        for (group in player.currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label
                    val lang = format.language
                    val name = when {
                        !label.isNullOrEmpty() -> label
                        !lang.isNullOrEmpty() -> java.util.Locale(lang).displayLanguage.ifEmpty { lang }
                        else -> "Subtitle Track $trackNumber"
                    }
                    result.add(SubtitleTrackInfo(group, i, name))
                    trackNumber++
                }
            }
        }
        return result
    }

    private fun updateSubtitleButtonState() {
        val player = exoPlayer ?: return
        val textTrackGroups = getTextTrackInfo(player)
        val params = player.trackSelectionParameters
        val isSubtitlesDisabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        if (textTrackGroups.isEmpty() || isSubtitlesDisabled) {
            btnSubtitles.alpha = 0.4f
        } else {
            btnSubtitles.alpha = 1.0f
        }
    }

    private fun handleSubtitleButtonClick() {
        val player = exoPlayer ?: return
        val textTracks = getTextTrackInfo(player)

        if (textTracks.isEmpty()) {
            Toast.makeText(this, "No subtitles available for this video", Toast.LENGTH_SHORT).show()
            btnSubtitles.alpha = 0.4f
            return
        }

        val isCurrentlyDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        if (textTracks.size == 1) {
            if (isCurrentlyDisabled) {
                val track = textTracks[0]
                val override = TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(override)
                    .build()
                btnSubtitles.alpha = 1.0f
                Toast.makeText(this, "Subtitles On (${track.displayName})", Toast.LENGTH_SHORT).show()
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                btnSubtitles.alpha = 0.4f
                Toast.makeText(this, "Subtitles Off", Toast.LENGTH_SHORT).show()
            }
        } else {
            val options = mutableListOf<String>()
            options.add("Off")
            textTracks.forEach { options.add(it.displayName) }

            var selectedIndex = 0
            if (!isCurrentlyDisabled) {
                for ((idx, track) in textTracks.withIndex()) {
                    if (track.group.isTrackSelected(track.trackIndex)) {
                        selectedIndex = idx + 1
                        break
                    }
                }
            }

            AlertDialog.Builder(this)
                .setTitle("Subtitles")
                .setSingleChoiceItems(options.toTypedArray(), selectedIndex) { dialog, which ->
                    if (which == 0) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        btnSubtitles.alpha = 0.4f
                        Toast.makeText(this, "Subtitles Off", Toast.LENGTH_SHORT).show()
                    } else {
                        val track = textTracks[which - 1]
                        val override = TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(override)
                            .build()
                        btnSubtitles.alpha = 1.0f
                        Toast.makeText(this, "Subtitles: ${track.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun playVideo(file: File) {
        currentlyPlayingFile = file
        val savedPos = getSavedPlaybackPosition(file)
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        
        fullscreenContainer.isVisible = true
        playerView.isVisible = true
        playerTitleText.text = cleanTitle(file.name)

        playerBackButton.setOnClickListener {
            stopInAppPlayback()
        }

        btnCenterPlayPause.setOnClickListener {
            toggleCenterPlayPause()
        }

        val player = getOrCreatePlayer()
        // Disable text tracks (subtitles) by default
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        btnSubtitles.alpha = 0.4f

        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()

        if (savedPos > 3000) {
            player.seekTo(savedPos.toLong())
            Toast.makeText(this, "Resumed from ${formatTimeMs(savedPos.toLong())}", Toast.LENGTH_SHORT).show()
        }

        player.play()
        showPlayerOverlay()
        updatePipParams()

        seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable)
        seekBarUpdateHandler.post(seekBarUpdateRunnable)

        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                updateSubtitleButtonState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePipParams()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isInPictureInPictureMode) {
                    playerControlsOverlay.isVisible = false
                } else if (isPlaying) {
                    imgCenterPlayPause.setImageResource(R.drawable.ic_pause)
                    showPlayerOverlay()
                } else {
                    imgCenterPlayPause.setImageResource(R.drawable.ic_play_overlay)
                    overlayHideHandler.removeCallbacks(overlayHideRunnable)
                    playerControlsOverlay.animate().cancel()
                    playerControlsOverlay.alpha = 1f
                    playerControlsOverlay.isVisible = true
                }
                updatePipParams()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val prefs = getSharedPreferences("video_playback_prefs", Context.MODE_PRIVATE)
                    file.let { prefs.edit().remove(it.absolutePath).apply() }
                    stopInAppPlayback()
                } else {
                    updatePipParams()
                }
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val viewWidth = playerView.width
                val tapX = e.x
                val currentPos = player.currentPosition
                val duration = player.duration

                if (tapX < viewWidth / 2) {
                    player.seekTo((currentPos - 10000).coerceAtLeast(0))
                    showSeekFeedback(seekFeedbackLeft)
                } else {
                    val target = if (duration > 0) (currentPos + 10000).coerceAtMost(duration) else currentPos + 10000
                    player.seekTo(target)
                    showSeekFeedback(seekFeedbackRight)
                }
                showPlayerOverlay()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isInPictureInPictureMode) {
                    playerControlsOverlay.isVisible = false
                    return true
                }
                if (playerControlsOverlay.isVisible) {
                    hidePlayerOverlay()
                } else {
                    showPlayerOverlay()
                }
                return true
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        playerView.setOnTouchListener(touchListener)
        fullscreenContainer.setOnTouchListener(touchListener)

        // Hide system bars for immersive playback
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun stopInAppPlayback() {
        overlayHideHandler.removeCallbacks(overlayHideRunnable)
        seekBarUpdateHandler.removeCallbacks(seekBarUpdateRunnable)
        overlayHideHandler.removeCallbacks(seekFeedbackLeftRunnable)
        overlayHideHandler.removeCallbacks(seekFeedbackRightRunnable)

        savePlaybackPosition()
        exoPlayer?.stop()
        playerView.isVisible = false
        playerControlsOverlay.isVisible = false
        seekFeedbackLeft.isVisible = false
        seekFeedbackRight.isVisible = false
        fullscreenContainer.isVisible = false
        currentlyPlayingFile = null
        activeMediaController = null
        updatePipParams()
        
        // Restore system bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun attemptAutoLogin(view: WebView?) {
        val webView = view ?: return
        when (webView) {
            webviewJellyfin -> {
                if (AppConfig.JELLYFIN_USERNAME.isNotBlank()) {
                    val user = escapeJsString(AppConfig.JELLYFIN_USERNAME)
                    val pass = escapeJsString(AppConfig.JELLYFIN_PASSWORD)
                    val js = """
                        (function() {
                            if (window.__jellyfinAutoLoginAttempted) return;
                            if (window.location.hash.indexOf('home') !== -1) return;
                            if (window.ApiClient && typeof window.ApiClient.isLoggedIn === 'function' && window.ApiClient.isLoggedIn()) return;

                            function fillJellyfin() {
                                if (window.__jellyfinAutoLoginAttempted) return;
                                var u = document.querySelector('input[type="text"]') || document.querySelector('input#txtManualName');
                                var p = document.querySelector('input[type="password"]') || document.querySelector('input#txtManualPassword');
                                var btn = document.querySelector('button[type="submit"]') || document.querySelector('button.btnSubmit');
                                
                                if (u && p && btn) {
                                    window.__jellyfinAutoLoginAttempted = true;
                                    function setReactValue(input, val) {
                                        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                        if (setter) { setter.call(input, val); } else { input.value = val; }
                                        input.dispatchEvent(new Event('input', { bubbles: true }));
                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                    }

                                    setReactValue(u, '$user');
                                    setReactValue(p, '$pass');

                                    setTimeout(function() {
                                        if (btn && !btn.disabled) {
                                            btn.click();
                                        }
                                    }, 300);
                                }
                            }
                            setTimeout(fillJellyfin, 300);
                            setTimeout(fillJellyfin, 1000);
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
            }
            webviewQbittorrent -> {
                if (AppConfig.QBITTORRENT_USERNAME.isNotBlank()) {
                    val user = escapeJsString(AppConfig.QBITTORRENT_USERNAME)
                    val pass = escapeJsString(AppConfig.QBITTORRENT_PASSWORD)
                    val js = """
                        (function() {
                            if (window.__qbittorrentAutoLoginAttempted) return;
                            function fillQbittorrent() {
                                if (window.__qbittorrentAutoLoginAttempted) return;
                                var u = document.getElementById('username') || document.querySelector('input[name="username"]');
                                var p = document.getElementById('password') || document.querySelector('input[name="password"]');
                                var btn = document.getElementById('login') || document.querySelector('input[type="submit"]');
                                
                                if (u && p) {
                                    window.__qbittorrentAutoLoginAttempted = true;
                                    function setVal(input, val) {
                                        var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                        if (setter) { setter.call(input, val); } else { input.value = val; }
                                        input.dispatchEvent(new Event('input', { bubbles: true }));
                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                    }
                                    setVal(u, '$user');
                                    setVal(p, '$pass');
                                    setTimeout(function() {
                                        if (btn) btn.click();
                                    }, 300);
                                }
                            }
                            setTimeout(fillQbittorrent, 300);
                            setTimeout(fillQbittorrent, 1000);
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
            }
            webviewSeerr -> {
                if (AppConfig.SEERR_EMAIL.isNotBlank()) {
                    val email = escapeJsString(AppConfig.SEERR_EMAIL)
                    val pass = escapeJsString(AppConfig.SEERR_PASSWORD)
                    val js = """
                        (function() {
                            if (window.__seerrAutoLoginAttempted) return;
                            function setReactValue(input, val) {
                                if (!input) return;
                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                if (setter) { setter.call(input, val); } else { input.value = val; }
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                input.dispatchEvent(new Event('change', { bubbles: true }));
                                input.dispatchEvent(new Event('blur', { bubbles: true }));
                            }

                            function fillSeerr() {
                                if (window.__seerrAutoLoginAttempted) return;
                                
                                var u = document.querySelector('input[name="username"]') || 
                                        document.querySelector('input[name="email"]') || 
                                        document.querySelector('input#username') ||
                                        document.querySelector('input[type="email"]') ||
                                        document.querySelector('input[type="text"]');
                                        
                                var p = document.querySelector('input[name="password"]') || 
                                        document.querySelector('input#password') ||
                                        document.querySelector('input[type="password"]');

                                if (u && p) {
                                    window.__seerrAutoLoginAttempted = true;

                                    setReactValue(u, '$email');
                                    setReactValue(p, '$pass');

                                    setTimeout(function() {
                                        var btnToClick = document.querySelector('button[type="submit"]');
                                        if (btnToClick && !btnToClick.disabled) {
                                            btnToClick.click();
                                        }
                                    }, 400);
                                }
                            }
                            
                            setTimeout(fillSeerr, 400);
                            setTimeout(fillSeerr, 1200);
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
            }
            webviewRadarr, webviewSonarr, webviewProwlarr -> {
                val username = when (webView) {
                    webviewRadarr -> AppConfig.RADARR_USERNAME
                    webviewSonarr -> AppConfig.SONARR_USERNAME
                    else -> AppConfig.PROWLARR_USERNAME
                }
                val password = when (webView) {
                    webviewRadarr -> AppConfig.RADARR_PASSWORD
                    webviewSonarr -> AppConfig.SONARR_PASSWORD
                    else -> AppConfig.PROWLARR_PASSWORD
                }
                val flagName = when (webView) {
                    webviewRadarr -> "__radarrAutoLoginAttempted"
                    webviewSonarr -> "__sonarrAutoLoginAttempted"
                    else -> "__prowlarrAutoLoginAttempted"
                }

                if (password.isNotBlank()) {
                    val userEscaped = escapeJsString(username)
                    val passEscaped = escapeJsString(password)
                    val js = """
                        (function() {
                            if (window.$flagName) return;
                            function setInputValue(input, val) {
                                if (!input) return;
                                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                                if (setter) { setter.call(input, val); } else { input.value = val; }
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                input.dispatchEvent(new Event('change', { bubbles: true }));
                                input.dispatchEvent(new Event('blur', { bubbles: true }));
                            }

                            function fillForm() {
                                if (window.$flagName) return;
                                
                                var u = document.querySelector('input[name="username"]') || 
                                        document.querySelector('input#username') ||
                                        document.querySelector('input[type="text"]');
                                        
                                var p = document.querySelector('input[name="password"]') || 
                                        document.querySelector('input#password') ||
                                        document.querySelector('input[type="password"]');

                                if (u && p) {
                                    window.$flagName = true;
                                    setInputValue(u, '$userEscaped');
                                    setInputValue(p, '$passEscaped');

                                    setTimeout(function() {
                                        var btn = document.querySelector('button[type="submit"]') ||
                                                  document.querySelector('button.btn-primary') ||
                                                  document.querySelector('input[type="submit"]') ||
                                                  document.querySelector('button');
                                        if (btn && !btn.disabled) {
                                            btn.click();
                                        }
                                    }, 400);
                                }
                            }
                            
                            setTimeout(fillForm, 300);
                            setTimeout(fillForm, 1000);
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun escapeJsString(str: String): String {
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
    }

    private fun handleUrlIntercept(url: String): Boolean {
        if (url.contains("jellyfin:8096") || url.contains("://jellyfin")) {
            val rewrittenUrl = url.replace("jellyfin:8096", "100.112.127.74:8096")
                                  .replace("://jellyfin", "://100.112.127.74:8096")
            webviewJellyfin.loadUrl(rewrittenUrl)
            for (i in 0 until tabLayout.tabCount) {
                val tab = tabLayout.getTabAt(i)
                if (tab?.tag == "watch") {
                    tabLayout.post { tab.select() }
                    break
                }
            }
            return true
        }
        return false
    }

    private fun injectPipVideoObserver(webView: WebView?) {
        val target = webView ?: return
        val js = """
            (function() {
                function attachListeners() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (!v.__pip_listener_attached) {
                            v.__pip_listener_attached = true;
                            v.addEventListener('play', function() {
                                if (window.AndroidPip) window.AndroidPip.onVideoStateChanged(true);
                            });
                            v.addEventListener('pause', function() {
                                if (window.AndroidPip) window.AndroidPip.onVideoStateChanged(false);
                            });
                            v.addEventListener('ended', function() {
                                if (window.AndroidPip) window.AndroidPip.onVideoStateChanged(false);
                            });
                            if (!v.paused && v.ended === false) {
                                if (window.AndroidPip) window.AndroidPip.onVideoStateChanged(true);
                            }
                        }
                    }
                }
                attachListeners();
                if (!window.__pip_observer_attached) {
                    window.__pip_observer_attached = true;
                    var observer = new MutationObserver(function() {
                        attachListeners();
                    });
                    if (document.body || document.documentElement) {
                        observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                    }
                }
            })();
        """.trimIndent()
        target.evaluateJavascript(js, null)
    }

    private fun setupWebView(webView: WebView, url: String) {
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.addJavascriptInterface(WebVideoInterface(), "AndroidPip")
        
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
                injectPipVideoObserver(webView)
                updatePipParams()
                
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
                updatePipParams()
                
                // Restore system bars
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            private var lastLoadFailed = false

            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val urlStr = request?.url?.toString() ?: return false
                if (handleUrlIntercept(urlStr)) {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && handleUrlIntercept(url)) {
                    return true
                }
                @Suppress("DEPRECATION")
                return super.shouldOverrideUrlLoading(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && handleUrlIntercept(url)) {
                    return
                }
                super.onPageStarted(view, url, favicon)
                lastLoadFailed = false
            }

            private fun shouldRetry(errorCode: Int): Boolean {
                return errorCode == ERROR_CONNECT ||
                       errorCode == ERROR_TIMEOUT ||
                       errorCode == ERROR_HOST_LOOKUP ||
                       errorCode == ERROR_IO ||
                       errorCode == -11 || // ERR_FAILED
                       errorCode == -21 // ERR_NETWORK_CHANGED
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                lastLoadFailed = true
                // Immediately hide the view to prevent showing the native error page
                view?.isVisible = false

                when (view) {
                    webviewSeerr -> isSeerrError = true
                    webviewJellyfin -> isJellyfinError = true
                    webviewQbittorrent -> isQbittorrentError = true
                    webviewRadarr -> isRadarrError = true
                    webviewSonarr -> isSonarrError = true
                    webviewProwlarr -> isProwlarrError = true
                    webviewYoutube -> isYoutubeError = true
                }
                
                updateUIState()

                if (shouldRetry(errorCode)) {
                    view?.postDelayed({ view.reload() }, 3000)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Modern version for newer Android levels
                if (request?.isForMainFrame == true) {
                    lastLoadFailed = true
                    // Immediately hide the view to prevent showing the native error page
                    view?.isVisible = false

                    when (view) {
                        webviewSeerr -> isSeerrError = true
                        webviewJellyfin -> isJellyfinError = true
                        webviewQbittorrent -> isQbittorrentError = true
                        webviewRadarr -> isRadarrError = true
                        webviewSonarr -> isSonarrError = true
                        webviewYoutube -> isYoutubeError = true
                    }
                    
                    updateUIState()

                    if (shouldRetry(error?.errorCode ?: 0)) {
                        view?.postDelayed({ view.reload() }, 3000)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!lastLoadFailed) {
                    when (view) {
                        webviewSeerr -> isSeerrError = false
                        webviewJellyfin -> isJellyfinError = false
                        webviewQbittorrent -> isQbittorrentError = false
                        webviewRadarr -> isRadarrError = false
                        webviewSonarr -> isSonarrError = false
                        webviewProwlarr -> isProwlarrError = false
                        webviewYoutube -> isYoutubeError = false
                    }
                    updateUIState()
                    attemptAutoLogin(view)
                    injectPipVideoObserver(view)
                }
                
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

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            val cookie = CookieManager.getInstance().getCookie(url)
            
            // Capture the current WebView title to help with naming
            val tab = tabLayout.getTabAt(tabLayout.selectedTabPosition)
            val currentWebView = when (tab?.tag) {
                "discover" -> webviewSeerr
                "watch" -> webviewJellyfin
                "qbittorrent" -> webviewQbittorrent
                "radarr" -> webviewRadarr
                "sonarr" -> webviewSonarr
                "prowlarr" -> webviewProwlarr
                "youtube" -> webviewYoutube
                else -> null
            }
            val pageTitle = currentWebView?.title
            
            val fileName = getPrettyFileName(url, contentDisposition, mimetype, pageTitle)
            
            if (!moviesFolder.exists()) {
                moviesFolder.mkdirs()
            }

            val destFile = File(moviesFolder, fileName)

            val download = DownloadRepository.enqueueDownload(
                url = url,
                title = cleanTitle(fileName),
                fileName = fileName,
                destinationFile = destFile,
                cookie = cookie,
                userAgent = userAgent
            )

            MovieDownloadService.startDownload(this, download.id)
            Toast.makeText(applicationContext, "Starting download for $fileName...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for URL: $url", e)
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
        
        val currentTab = tabLayout.getTabAt(tabLayout.selectedTabPosition)
        val currentTabTag = currentTab?.tag?.toString() ?: ""
        
        when {
            // Case 1: Tailscale is missing
            !tailscaleInstalled -> {
                isServerConfirmed = false
                serviceStatusText.text = getString(R.string.status_missing)
                serviceProgressBar.isVisible = false
                if (isInitialStartup) {
                    isInitialStartup = false
                    tabLayout.getTabAt(3)?.select()
                }
            }
            
            // Case 2: Completely Offline (No WiFi/Data)
            !networkAvailable -> {
                isServerConfirmed = false
                isSeerrError = false
                isJellyfinError = false
                isQbittorrentError = false
                isYoutubeError = false // Reset error state when totally offline
                serviceStatusText.text = getString(R.string.status_not_connected)
                serviceProgressBar.isVisible = false
                if (isInitialStartup) {
                    isInitialStartup = false
                    tabLayout.getTabAt(3)?.select()
                }
            }
            
            // Case 2.5: Current tab specifically has an error but network is otherwise okay
            (isYoutubeError && currentTabTag == "youtube") || 
            (isSeerrError && currentTabTag == "discover") || 
            (isJellyfinError && currentTabTag == "watch") ||
            (isQbittorrentError && currentTabTag == "qbittorrent") ||
            (isRadarrError && currentTabTag == "radarr") ||
            (isSonarrError && currentTabTag == "sonarr") ||
            (isProwlarrError && currentTabTag == "prowlarr") -> {
                val serviceName = when(currentTabTag) {
                    "discover" -> "Seerr"
                    "watch" -> "Jellyfin"
                    "qbittorrent" -> "qBittorrent"
                    "radarr" -> "Radarr"
                    "sonarr" -> "Sonarr"
                    "prowlarr" -> "Prowlarr"
                    else -> "YouTube"
                }
                serviceStatusText.text = "$serviceName connection issue. Retrying..."
                serviceProgressBar.isVisible = true
            }

            // Case 3: VPN active, check server
            vpnActive -> {
                if (isServerConfirmed) {
                    serviceErrorLayout.isVisible = false
                    isInitialStartup = false
                } else {
                    if (reachabilityFailureCount >= MAX_REACHABILITY_ATTEMPTS) {
                        serviceStatusText.text = getString(R.string.status_not_connected)
                        serviceProgressBar.isVisible = false
                        if (isInitialStartup) {
                            isInitialStartup = false
                            tabLayout.getTabAt(3)?.select()
                        }
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
                isInitialStartup = false
                if (isYoutubeError) {
                    isYoutubeError = false
                    webviewYoutube.reload()
                }
                webviewSeerr.reload()
                webviewJellyfin.reload()
                webviewQbittorrent.reload()
                webviewRadarr.reload()
                webviewSonarr.reload()
                webviewProwlarr.reload()
            } else {
                reachabilityFailureCount++
                if (reachabilityFailureCount >= MAX_REACHABILITY_ATTEMPTS) {
                    if (isInitialStartup) {
                        isInitialStartup = false
                        tabLayout.getTabAt(3)?.select()
                    }
                    // Periodic "poke" to Tailscale in case the VPN tunnel is hung
                    if (reachabilityFailureCount % 5 == 0) {
                        TailscaleController.connect(this@MainActivity)
                    }
                    
                    if (reachabilityFailureCount == MAX_REACHABILITY_ATTEMPTS) {
                        Toast.makeText(this@MainActivity, "Server unreachable. Retrying...", Toast.LENGTH_LONG).show()
                    }
                    delay(5000) // Back off slightly when failing
                } else {
                    delay(1500)
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

        val inputData = Data.Builder()
            .putString("videoUrl", url)
            .putString("apiUrl", YOUTUBE_DOWNLOAD_API)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
            .setInputData(inputData)
            .addTag("youtube_download")
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
    }

    private fun resetYoutubeDownloadStatusAfterDelay(success: Boolean) {
        lifecycleScope.launch {
            delay(7000)
            if (success) {
                youtubeDownloadStatus.isVisible = false
                btnDownloadYoutube.isEnabled = true
                isDownloadingYoutube = false
                updateYoutubeDownloadBarVisibility()
            } else {
                btnDownloadYoutube.isEnabled = true
                isDownloadingYoutube = false
                updateYoutubeDownloadBarVisibility()
            }
        }
    }

    private fun updateYoutubeDownloadBarVisibility() {
        val tab = tabLayout.getTabAt(tabLayout.selectedTabPosition)
        val isYoutubeTab = tab?.tag == "youtube"
        val isWatchingVideo = webviewYoutube.url?.contains("watch") == true
        val shouldShow = isYoutubeTab && (isWatchingVideo || isDownloadingYoutube)
        
        if (youtubeDownloadBar.isVisible != shouldShow) {
            youtubeDownloadBar.isVisible = shouldShow
        }
    }

    private fun handleDownloadsTabTap() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDownloadsTabTapTime < 500) {
            downloadsTabTapCount++
        } else {
            downloadsTabTapCount = 1
        }
        lastDownloadsTabTapTime = currentTime

        if (downloadsTabTapCount >= 5) {
            downloadsTabTapCount = 0
            
            // Check if Logs tab already exists
            var existingLogsTab: TabLayout.Tab? = null
            for (i in 0 until tabLayout.tabCount) {
                val tab = tabLayout.getTabAt(i)
                if (tab?.tag == "logs") {
                    existingLogsTab = tab
                    break
                }
            }
            
            if (existingLogsTab != null) {
                tabLayout.removeTab(existingLogsTab)
                if (tabLayout.tabCount <= 4) {
                    tabLayout.tabMode = TabLayout.MODE_FIXED
                    tabLayout.tabGravity = TabLayout.GRAVITY_FILL
                }
            } else {
                tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                val newTab = tabLayout.newTab().setText("Logs")
                newTab.tag = "logs"
                tabLayout.addTab(newTab)
                tabLayout.post {
                    newTab.select()
                }
            }
        }
    }

    private fun handleJellyfinTabTap() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastJellyfinTabTapTime < 500) {
            jellyfinTabTapCount++
        } else {
            jellyfinTabTapCount = 1
        }
        lastJellyfinTabTapTime = currentTime

        if (jellyfinTabTapCount >= 5) {
            jellyfinTabTapCount = 0
            
            val adminTags = setOf("qbittorrent", "radarr", "sonarr", "prowlarr")
            val tabsToRemove = mutableListOf<TabLayout.Tab>()
            var downloadsTabIndex = -1
            
            for (i in 0 until tabLayout.tabCount) {
                val tab = tabLayout.getTabAt(i)
                val tag = tab?.tag?.toString()
                if (tag in adminTags && tab != null) {
                    tabsToRemove.add(tab)
                }
                if (tag == "downloads") {
                    downloadsTabIndex = i
                }
            }
            
            if (tabsToRemove.isNotEmpty()) {
                // Secret tabs are currently visible -> HIDE THEM
                for (tab in tabsToRemove) {
                    tabLayout.removeTab(tab)
                }
                
                // Select Watch tab
                for (i in 0 until tabLayout.tabCount) {
                    val tab = tabLayout.getTabAt(i)
                    if (tab?.tag == "watch") {
                        tabLayout.post { tab.select() }
                        break
                    }
                }

                // If 4 or fewer tabs remain, restore full-width fixed mode
                if (tabLayout.tabCount <= 4) {
                    tabLayout.tabMode = TabLayout.MODE_FIXED
                    tabLayout.tabGravity = TabLayout.GRAVITY_FILL
                }
            } else if (downloadsTabIndex != -1) {
                // Secret tabs are hidden -> SHOW THEM
                tabLayout.tabMode = TabLayout.MODE_SCROLLABLE
                
                val qbittorrentTab = tabLayout.newTab().setIcon(R.drawable.ic_qbittorrent)
                qbittorrentTab.tag = "qbittorrent"
                tabLayout.addTab(qbittorrentTab, downloadsTabIndex + 1)
                
                val radarrTab = tabLayout.newTab().setIcon(R.drawable.ic_radarr)
                radarrTab.tag = "radarr"
                tabLayout.addTab(radarrTab, downloadsTabIndex + 2)
                
                val sonarrTab = tabLayout.newTab().setIcon(R.drawable.ic_sonarr)
                sonarrTab.tag = "sonarr"
                tabLayout.addTab(sonarrTab, downloadsTabIndex + 3)
                
                val prowlarrTab = tabLayout.newTab().setIcon(R.drawable.ic_prowlarr)
                prowlarrTab.tag = "prowlarr"
                tabLayout.addTab(prowlarrTab, downloadsTabIndex + 4)
                
                tabLayout.post {
                    qbittorrentTab.select()
                }
            }
        }
    }

    private fun refreshLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logOutput = StringBuilder()
            try {
                val process = Runtime.getRuntime().exec("logcat -d")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (bufferedReader.readLine().also { line = it } != null) {
                    if (line?.contains(packageName) == true || line?.contains("MainActivity") == true || line?.contains("Download") == true) {
                        logOutput.append(line).append("\n")
                    }
                }
            } catch (e: Exception) {
                logOutput.append("Error reading logs: ${e.message}")
            }
            
            withContext(Dispatchers.Main) {
                logsTextView.text = logOutput.toString()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playerView.isVisible || fullscreenContainer.isVisible) {
            stopInAppPlayback()
            return
        }

        if (customView != null) {
            val chromeClient = when {
                webviewSeerr.isVisible -> webviewSeerr.webChromeClient
                webviewJellyfin.isVisible -> webviewJellyfin.webChromeClient
                webviewQbittorrent.isVisible -> webviewQbittorrent.webChromeClient
                webviewRadarr.isVisible -> webviewRadarr.webChromeClient
                webviewSonarr.isVisible -> webviewSonarr.webChromeClient
                webviewProwlarr.isVisible -> webviewProwlarr.webChromeClient
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
        if (webviewQbittorrent.isVisible && webviewQbittorrent.canGoBack()) {
            webviewQbittorrent.goBack()
            return
        }
        if (webviewRadarr.isVisible && webviewRadarr.canGoBack()) {
            webviewRadarr.goBack()
            return
        }
        if (webviewSonarr.isVisible && webviewSonarr.canGoBack()) {
            webviewSonarr.goBack()
            return
        }
        if (webviewProwlarr.isVisible && webviewProwlarr.canGoBack()) {
            webviewProwlarr.goBack()
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

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
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
            
            holder.fileNameText.isVisible = true
            holder.fileNameText.text = cleanTitle(item.name)
            holder.posterCard.isVisible = true

            val loadTarget = item.artworkFile ?: item.file
            if (loadTarget != null) {
                Glide.with(holder.itemView.context)
                    .load(loadTarget)
                    .centerCrop()
                    .into(holder.posterThumbnail)
            } else {
                holder.posterThumbnail.setImageResource(R.drawable.penguino)
            }

            if (item.movieDownloadId != null) {
                // Active Movie Download
                holder.playIcon.isVisible = false
                holder.progressBar.isVisible = true
                holder.progressBar.progress = item.progress
                holder.statusText.isVisible = true
                holder.statusText.text = item.statusText.ifEmpty { "Downloading... ${item.progress}%" }
                holder.itemView.setOnClickListener(null)
                holder.deleteButton.setOnClickListener { onDelete(item) }
            } else if (item.downloadId != null) {
                // Active DownloadManager Download
                holder.playIcon.isVisible = false
                holder.progressBar.isVisible = true
                holder.progressBar.progress = item.progress
                holder.statusText.isVisible = true
                holder.statusText.text = when (item.status) {
                    DownloadManager.STATUS_PENDING -> "Pending download..."
                    DownloadManager.STATUS_PAUSED -> "Paused (${item.progress}%)"
                    else -> "Downloading... ${item.progress}%"
                }
                holder.itemView.setOnClickListener(null)
                holder.deleteButton.setOnClickListener { onDelete(item) }
            } else {
                // Completed File
                holder.playIcon.isVisible = true
                holder.progressBar.isVisible = false
                holder.statusText.isVisible = true
                val fileSize = item.file?.length() ?: 0L
                holder.statusText.text = if (fileSize > 0) formatFileSize(fileSize) else "Movie File"

                holder.itemView.setOnClickListener { onClick(item) }
                holder.deleteButton.setOnClickListener { onDelete(item) }
            }
        }

        override fun getItemCount() = items.size
    }

    private inner class SimplifiedMediaController(context: Context) : MediaController(context) {
        override fun setAnchorView(view: View?) {
            super.setAnchorView(view)
            
            this.fitsSystemWindows = false

            // Hide unwanted buttons from the standard controller
            val ffwdId = Resources.getSystem().getIdentifier("ffwd", "id", "android")
            val rewId = Resources.getSystem().getIdentifier("rew", "id", "android")
            val nextId = Resources.getSystem().getIdentifier("next", "id", "android")
            val prevId = Resources.getSystem().getIdentifier("prev", "id", "android")
            val pauseId = Resources.getSystem().getIdentifier("pause", "id", "android")

            findViewById<View>(ffwdId)?.isVisible = false
            findViewById<View>(rewId)?.isVisible = false
            findViewById<View>(nextId)?.isVisible = false
            findViewById<View>(prevId)?.isVisible = false
            findViewById<View>(pauseId)?.isVisible = false

            // Set a semi-transparent black background
            this.setBackgroundColor(Color.argb(180, 0, 0, 0))

            updatePaddingForOrientation()
        }

        fun updatePaddingForOrientation() {
            removeInnerBackgrounds(this)

            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val sidePaddingDp = if (isLandscape) 48 else 16
            val verticalPaddingDp = if (isLandscape) 12 else 16
            
            val density = Resources.getSystem().displayMetrics.density
            val sidePadding = (sidePaddingDp * density).toInt()
            val verticalPadding = (verticalPaddingDp * density).toInt()

            this.setPadding(sidePadding, verticalPadding, sidePadding, verticalPadding)
            if (isLandscape) {
                removeAllBottomMargins(this)
            }
        }

        private fun removeInnerBackgrounds(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                child.background = null
                if (child is ViewGroup) {
                    removeInnerBackgrounds(child)
                }
            }
        }

        private fun removeAllBottomMargins(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, 0)
                val lp = child.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.bottomMargin = 0
                    child.layoutParams = lp
                }
                if (child is ViewGroup) {
                    removeAllBottomMargins(child)
                }
            }
        }

        override fun show(timeout: Int) {
            updatePaddingForOrientation()
            super.show(0)
        }

        override fun hide() {
            super.hide()
        }
    }
}