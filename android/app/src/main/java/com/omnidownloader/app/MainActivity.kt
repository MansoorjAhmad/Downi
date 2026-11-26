package com.omnidownloader.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaquo.python.Python
import com.omnidownloader.app.data.DownloadJobEntity
import com.omnidownloader.app.ui.DowniViewModel
import com.omnidownloader.app.ui.components.DowniBottomBar
import com.omnidownloader.app.ui.components.DowniNavTab
import com.omnidownloader.app.ui.components.InspectorModalHost
import com.omnidownloader.app.ui.components.StatusPill
import com.omnidownloader.app.ui.components.VaultPlayerSheet
import com.omnidownloader.app.ui.screens.GrabScreen
import com.omnidownloader.app.ui.screens.HistoryScreen
import com.omnidownloader.app.ui.screens.QueueScreen
import com.omnidownloader.app.ui.screens.SettingsScreen
import com.omnidownloader.app.ui.theme.DowniPalette
import com.omnidownloader.app.ui.theme.DowniSpacing
import com.omnidownloader.app.ui.theme.DowniTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: DowniViewModel by viewModels()
    private val openQueueSignal = MutableStateFlow(0)

    @Volatile
    private var engineWarm = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            try {
                provider.remove()
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed(::warmUpEngine, 400L)
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(DowniPalette.ObsidianHex),
            navigationBarStyle = SystemBarStyle.dark(DowniPalette.ObsidianHex)
        )
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed(::warmUpEngine, 2000L)

        val startInQueue = intent?.getBooleanExtra("openQueue", false) == true

        setContent {
            DowniTheme {
                DowniAppShell(
                    viewModel = viewModel,
                    initialTab = if (startInQueue) 1 else 0,
                    openQueueSignal = openQueueSignal
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            viewModel.refreshClipboard()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("openQueue", false)) {
            openQueueSignal.value += 1
        }
    }

    private fun warmUpEngine() {
        synchronized(this) {
            if (engineWarm) return
            engineWarm = true
        }
        Thread({
            try {
                DowniNativeUtils.ensurePythonStarted(applicationContext)
                Python.getInstance().getModule("downloader").callAttr("engine_info")
            } catch (_: Exception) {
            }
        }, "downi-engine-warmup").start()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DowniAppShell(
    viewModel: DowniViewModel,
    initialTab: Int,
    openQueueSignal: MutableStateFlow<Int>
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val hazeState = remember { HazeState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
    var playerItem by remember { mutableStateOf<DownloadJobEntity?>(null) }
    val queueTrigger by openQueueSignal.collectAsStateWithLifecycle()

    LaunchedEffect(queueTrigger) {
        if (queueTrigger > 0) selectedTab = 1
    }

    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val detectedUrls by viewModel.detectedUrls.collectAsStateWithLifecycle()
    val clipboardUrl by viewModel.clipboardUrl.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val inspectState by viewModel.inspectState.collectAsStateWithLifecycle()
    val loadingMorePlaylist by viewModel.loadingMorePlaylist.collectAsStateWithLifecycle()
    val vaultActionBusy by viewModel.vaultActionBusy.collectAsStateWithLifecycle()
    val engineInfo by viewModel.engineInfo.collectAsStateWithLifecycle()
    val diagChecks by viewModel.diagChecks.collectAsStateWithLifecycle()
    val diagRunning by viewModel.diagRunning.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val activeCount = jobs.count { it.status.isActive }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setSaveTreeUri(uri)
            scope.launch { snackbarHostState.showSnackbar("Save folder updated") }
        }
    }

    fun showFeedback(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshClipboard()
    }

    Scaffold(
        containerColor = DowniPalette.Obsidian,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DowniPalette.SurfaceHighlight,
                    contentColor = DowniPalette.TextPrimary,
                    actionColor = DowniPalette.Lime,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DowniPalette.Obsidian,
                    titleContentColor = DowniPalette.TextPrimary
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = DowniPalette.SurfaceElevated,
                            border = BorderStroke(1.dp, DowniPalette.BorderBright),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.downi_logo_foreground),
                                    contentDescription = "DOWNI",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .graphicsLayer {
                                            scaleX = 1.75f
                                            scaleY = 1.75f
                                        }
                                )
                            }
                        }
                        Spacer(Modifier.width(DowniSpacing.md))
                        Text("DOWNI", fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.width(DowniSpacing.md))
                        StatusPill(
                            text = if (engineInfo != null) "ENGINE READY" else "WARMING",
                            color = if (engineInfo != null) DowniPalette.Success else DowniPalette.Amber,
                            background = if (engineInfo != null) DowniPalette.SuccessDim else DowniPalette.AmberDim,
                            showDot = true
                        )
                    }
                },
                actions = {
                    StatusPill(
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = DowniPalette.Cyan,
                        background = DowniPalette.CyanDim,
                        modifier = Modifier.padding(end = DowniSpacing.lg)
                    )
                }
            )
        },
        bottomBar = {
            DowniBottomBar(
                tabs = listOf(
                    DowniNavTab("Grab", Icons.Rounded.Bolt),
                    DowniNavTab("Queue", Icons.Rounded.Download, badgeCount = activeCount),
                    DowniNavTab("Vault", Icons.Rounded.History),
                    DowniNavTab("Settings", Icons.Rounded.Settings)
                ),
                selectedIndex = selectedTab,
                hazeState = hazeState,
                onSelect = { idx ->
                    if (idx != selectedTab) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selectedTab = idx
                    }
                }
            )
        }
    ) { innerPadding ->
        val bottomDockClearance = innerPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .haze(state = hazeState)
        ) {
            when (selectedTab) {
                    0 -> GrabScreen(
                        urlInput = urlInput,
                        clipboardUrl = clipboardUrl,
                        detectedUrls = detectedUrls,
                        recentJobs = jobs,
                        onUrlChange = viewModel::updateUrlInput,
                        onClearUrl = viewModel::clearUrlInput,
                        onPaste = viewModel::pasteFromClipboard,
                        onDismissClipboard = viewModel::dismissClipboard,
                        onInspect = viewModel::inspectUrl,
                        onQuickGrab = { url ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ensureNotificationPermission()
                            viewModel.grabQuick(url)
                            showFeedback("Added to Queue")
                        },
                        onBatchGrab = { urls ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            ensureNotificationPermission()
                            viewModel.grabBatch(urls)
                            showFeedback("Queued ${urls.size} downloads")
                        },
                        onCancelJob = { id ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelJob(id)
                            showFeedback("Download cancelled")
                        },
                        onOpenQueue = { selectedTab = 1 },
                        onOpenMedia = { uri, _ ->
                            playerItem = jobs.firstOrNull { it.mediaUri == uri }
                                ?: run { viewModel.openSavedMedia(uri, "video/mp4"); null }
                        },
                        detectPlatform = viewModel::detectPlatform,
                        bottomContentPadding = bottomDockClearance
                    )

                    1 -> QueueScreen(
                        jobs = jobs,
                        onCancelJob = { id ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelJob(id)
                            showFeedback("Download cancelled")
                        },
                        onCancelAll = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cancelAll()
                            showFeedback("Cancelled all active downloads")
                        },
                        onRetry = { failed ->
                            ensureNotificationPermission()
                            viewModel.deleteHistoryItem(failed.id)
                            viewModel.grabQuick(failed.url)
                            showFeedback("Retrying download")
                        },
                        onDismissJob = viewModel::deleteHistoryItem,
                        bottomContentPadding = bottomDockClearance
                    )

                    2 -> HistoryScreen(
                        jobs = jobs,
                        onOpenMedia = { uri, mime ->
                            playerItem = jobs.firstOrNull { it.mediaUri == uri }
                                ?: run { viewModel.openSavedMedia(uri, mime); null }
                        },
                        onShareMedia = viewModel::shareSavedMedia,
                        onDeleteItem = { id ->
                            viewModel.deleteHistoryItem(id)
                            showFeedback("Removed from Vault")
                        },
                        onClearAll = {
                            viewModel.clearHistory()
                            showFeedback("Vault cleared")
                        },
                        bottomContentPadding = bottomDockClearance
                    )

                    3 -> SettingsScreen(
                        settings = settings,
                        engineInfo = engineInfo,
                        diagChecks = diagChecks,
                        diagRunning = diagRunning,
                        updateState = updateState,
                        onToggleAutoUnique = viewModel::setAutoUnique,
                        onSelectShieldTier = viewModel::setShieldTier,
                        onToggleClipboard = viewModel::setClipboardDetect,
                        onSelectOneTapQuality = viewModel::setOneTapQuality,
                        onChooseFolder = { folderPickerLauncher.launch(null) },
                        onResetFolder = {
                            viewModel.clearSaveFolder()
                            showFeedback("Reset to Gallery")
                        },
                        onClearCache = {
                            viewModel.clearCache()
                            showFeedback("Engine cache cleared")
                        },
                        onRunDiagnostics = viewModel::runDiagnostics,
                        onCopyDiagnostics = { report ->
                            viewModel.copyText("Diagnostics", report)
                            showFeedback("Diagnostics copied")
                        },
                        onCopyVersion = { ver ->
                            viewModel.copyText("Version", ver)
                            showFeedback("Copied $ver")
                        },
                        onCheckUpdates = viewModel::checkUpdates,
                        onDownloadUpdate = viewModel::downloadUpdate,
                        bottomContentPadding = bottomDockClearance
                    )
            }
        }
    }

    InspectorModalHost(
        state = inspectState,
        loadingMorePlaylist = loadingMorePlaylist,
        onDismiss = viewModel::dismissInspector,
        onLoadMorePlaylist = viewModel::loadMorePlaylist,
        onGrabFormat = { info, fmt, subs, lang ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ensureNotificationPermission()
            viewModel.grabFromInspector(info.url, info.title, info.thumbnail, info.platform, fmt, subs, lang)
            showFeedback("Grab started")
        },
        onGrabBatch = { urls, fmt ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ensureNotificationPermission()
            viewModel.grabBatch(urls, fmt)
            showFeedback("Queued ${urls.size} downloads")
        }
    )

    VaultPlayerSheet(
        item = playerItem,
        actionBusy = vaultActionBusy,
        onDismiss = { playerItem = null },
        onShare = viewModel::shareSavedMedia,
        onOpenExternal = viewModel::openSavedMedia,
        onRerunShield = { item, tier ->
            viewModel.rerunVaultShield(item, tier) { msg ->
                showFeedback(msg)
            }
        },
        onExtractAudio = { item ->
            viewModel.extractVaultAudio(item) { msg ->
                showFeedback(msg)
            }
        }
    )
}
