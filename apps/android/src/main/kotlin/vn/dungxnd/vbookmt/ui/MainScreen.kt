package vn.dungxnd.vbookmt.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.core.model.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.service.CacheStore
import vn.dungxnd.vbookmt.service.ChunkTranslator
import vn.dungxnd.vbookmt.service.Engines
import vn.dungxnd.vbookmt.service.ServiceContract
import vn.dungxnd.vbookmt.service.ServiceState
import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator
import vn.dungxnd.vbookmt.service.TranslationService
import vn.dungxnd.vbookmt.ui.components.BackgroundOptimizationCard
import vn.dungxnd.vbookmt.ui.components.DebugLogCard
import vn.dungxnd.vbookmt.ui.components.ModelSection
import vn.dungxnd.vbookmt.ui.components.NoticesSheetContent
import vn.dungxnd.vbookmt.ui.components.OptimizationSetupDialog
import vn.dungxnd.vbookmt.ui.components.PerformanceCard
import vn.dungxnd.vbookmt.ui.components.QuickTestCard
import vn.dungxnd.vbookmt.ui.components.StatusHeroCard
import vn.dungxnd.vbookmt.util.BatteryOptimizationHelper
import vn.dungxnd.vbookmt.util.ModelDownloader
import vn.dungxnd.vbookmt.util.PerformanceTuner

/**
 * The three pages of the app, in the order they appear in the bar — the same three the desktop host
 * shows in its nav rail, with the same labels.
 *
 * The screen was a single ~350-line scroll with eight cards, which meant the power toggle, the model
 * picker and the debug log all competed for the same screen and the reader had to scroll past
 * diagnostics to start reading. Splitting by intent — *do the thing*, *choose the model*, *configure* —
 * keeps each page short enough to take in at a glance.
 *
 * Names and icons deliberately match `Destination` in the Windows host so the two apps describe the
 * same three places the same way.
 */
private enum class MainTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Home("Tổng quan", Icons.Outlined.ViewAgenda),
    Models("Mô hình", Icons.Outlined.Memory),
    Settings("Cài đặt", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: (String) -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Home) }

    val isRunning by ServiceState.isRunning.collectAsState()
    val engineState by ServiceState.engineState.collectAsState()
    val activeModelId by ServiceState.activeModel.collectAsState()
    val selectedModel = remember(activeModelId) { ModelRegistry.getById(activeModelId) }

    var showNoticesSheet by remember { mutableStateOf(false) }

    // Optimization & Auto-open State
    var isIgnoringBattery by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    var isNotificationGranted by remember { mutableStateOf(BatteryOptimizationHelper.isNotificationPermissionGranted(context)) }
    var isAutoStartOnOpen by remember { mutableStateOf(BatteryOptimizationHelper.isAutoStartServiceOnOpen(context)) }
    var showOptimizationDialog by remember { mutableStateOf(false) }

    // Performance state. Everything device-specific is decided for the user: the settings page only
    // reports the outcome in plain words and offers one optional "measure my phone" action.
    var tuning by remember { mutableStateOf<PerformanceTuner.Tuning?>(null) }
    var isTuning by remember { mutableStateOf(false) }
    val tuneProgress by PerformanceTuner.progress.collectAsState()
    var cacheStats by remember { mutableStateOf(ChunkTranslator.cacheStats()) }

    var isDebugLogEnabled by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationGranted = isGranted
    }

    // Check first open or unoptimized state on launch
    LaunchedEffect(Unit) {
        val hasPrompted = BatteryOptimizationHelper.hasPrompted(context)
        val isOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        if (!hasPrompted || !isOptimized) {
            showOptimizationDialog = true
        }

        // Auto-open server on app open if user enabled it
        if (isAutoStartOnOpen && !isRunning) {
            onStartService(selectedModel.id)
        }

        // Pre-load the ONNX engine in the background for instant quick testing, honouring any
        // thread count this phone has already measured.
        withContext(Dispatchers.IO) {
            // Warm the memo from disk first: the quick test and a re-opened chapter then cost nothing,
            // even when the app was killed between reads.
            CacheStore.attach(context)
            val modelDir = java.io.File(context.filesDir, "models/${selectedModel.id}")
            if (!modelDir.exists()) modelDir.mkdirs()
            if (!OnnxTranslator.matchesModelDir(modelDir)) {
                // A tuned phone keeps its measured thread count; otherwise init picks the default.
                PerformanceTuner.applySaved(context, modelDir) ||
                    Engines.ensureActiveLoaded(modelDir)
            }
        }
        tuning = PerformanceTuner.current(context)
        cacheStats = ChunkTranslator.cacheStats()
    }

    // Keep the "already remembered" line truthful while the user is looking at it.
    LaunchedEffect(Unit) {
        while (true) {
            cacheStats = ChunkTranslator.cacheStats()
            kotlinx.coroutines.delay(3000)
        }
    }

    /** Pushes a model switch to the service, which is the only place that owns the loaded engine. */
    fun switchModel(spec: com.vbookmt.core.model.ModelSpec) {
        ServiceState.setActiveModel(spec.id)
        if (isRunning) {
            val intent = android.content.Intent(context, TranslationService::class.java).apply {
                action = ServiceContract.ACTION_START
                putExtra(ServiceContract.EXTRA_ACTIVE_MODEL, spec.id)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Toast.makeText(context, "Không đổi được model: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "VBookMT",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "ONNX Offline",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showNoticesSheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Tác quyền & Giấy phép"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, fontSize = 11.sp) },
                    )
                }
            }
        }
    ) { innerPadding ->
        // One LazyColumn per tab: the pages have different content and different scroll lengths, and
        // sharing one scroll state would land the reader mid-settings when they switch back.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                MainTab.Home -> {
                    item {
                        StatusHeroCard(
                            isRunning = isRunning,
                            port = ServiceContract.PORT,
                            engineState = engineState,
                            onToggle = {
                                if (isRunning) {
                                    onStopService()
                                } else {
                                    onStartService(selectedModel.id)
                                }
                            },
                            onCopyUrl = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("VBookMT URL", "http://127.0.0.1:${ServiceContract.PORT}/translate"))
                                Toast.makeText(context, "Đã sao chép địa chỉ server!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    item { QuickTestCard(modelId = selectedModel.id) }
                }

                MainTab.Models -> {
                    item {
                        ModelSection(
                            selectedModel = selectedModel,
                            onSelectModel = { switchModel(it) },
                        )
                    }
                }

                MainTab.Settings -> {
                    item {
                        BackgroundOptimizationCard(
                            isIgnoringBattery = isIgnoringBattery,
                            isAutoStartOnOpen = isAutoStartOnOpen,
                            isNotificationGranted = isNotificationGranted,
                            onToggleAutoStart = { enabled ->
                                isAutoStartOnOpen = enabled
                                BatteryOptimizationHelper.setAutoStartServiceOnOpen(context, enabled)
                            },
                            onRequestIgnoreBattery = {
                                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                                isIgnoringBattery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                            },
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenOemSettings = {
                                BatteryOptimizationHelper.openAutoStartSettings(context)
                            }
                        )
                    }
                    item {
                        PerformanceCard(
                            tuning = tuning,
                            isTuning = isTuning,
                            progress = tuneProgress,
                            cacheText = PerformanceTuner.cacheText(cacheStats),
                            cacheHasEntries = cacheStats.entries > 0,
                            onTune = {
                                isTuning = true
                                coroutineScope.launch {
                                    // Measure the model actually on disk, not merely the one selected in
                                    // the picker: selecting a card does not download it, and a missing
                                    // directory made the sweep bail before it started — which read to the
                                    // reader as "tuning doesn't work".
                                    val installed = ModelDownloader.firstInstalled(context)
                                    val modelDir = installed?.let {
                                        java.io.File(context.filesDir, "models/${it.id}")
                                    }
                                    val result = if (modelDir == null) {
                                        PerformanceTuner.TuneResult.Failure(
                                            "Chưa tải mô hình nào — vào tab Mô hình để tải trước."
                                        )
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            PerformanceTuner.autoTune(context, modelDir)
                                        }
                                    }
                                    tuning = when (result) {
                                        is PerformanceTuner.TuneResult.Success -> result.tuning
                                        is PerformanceTuner.TuneResult.Failure ->
                                            PerformanceTuner.current(context)
                                    }
                                    isTuning = false
                                    cacheStats = ChunkTranslator.cacheStats()
                                    Toast.makeText(
                                        context,
                                        when (result) {
                                            is PerformanceTuner.TuneResult.Success ->
                                                 "Đã tối ưu: ${result.tuning.speedText}"
                                            is PerformanceTuner.TuneResult.Failure -> result.reason
                                        },
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            onClearCache = {
                                coroutineScope.launch {
                                    withContext(Dispatchers.IO) { ChunkTranslator.clearCache() }
                                    cacheStats = ChunkTranslator.cacheStats()
                                    Toast.makeText(
                                        context,
                                        "Đã xoá bộ nhớ đệm (RAM + nhật ký trên đĩa)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        )
                    }
                    item {
                        LiveDebugLogCard(
                            isEnabled = isDebugLogEnabled,
                            onToggle = { isDebugLogEnabled = it },
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Bottom Sheet: Notices & Licenses
        if (showNoticesSheet) {
            ModalBottomSheet(
                onDismissRequest = { showNoticesSheet = false },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                NoticesSheetContent(onClose = { showNoticesSheet = false })
            }
        }

        // First Launch / Battery Optimization Setup Dialog
        if (showOptimizationDialog) {
            OptimizationSetupDialog(
                isIgnoringBattery = isIgnoringBattery,
                isNotificationGranted = isNotificationGranted,
                onRequestIgnoreBattery = {
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
                    isIgnoringBattery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                },
                onRequestNotification = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onOpenOemSettings = {
                    BatteryOptimizationHelper.openAutoStartSettings(context)
                },
                onDismiss = {
                    BatteryOptimizationHelper.setPrompted(context)
                    showOptimizationDialog = false
                }
            )
        }
    }
}

@Composable
private fun LiveDebugLogCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val debugLogs by ServiceState.debugLogs.collectAsState()
    DebugLogCard(
        isEnabled = isEnabled,
        logs = debugLogs,
        onToggle = onToggle,
        onClearLogs = { ServiceState.clearLogs() },
        onCopyLogs = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VBookMT Logs", debugLogs.joinToString("\n")))
            Toast.makeText(context, "Đã sao chép ${debugLogs.size} dòng log!", Toast.LENGTH_SHORT).show()
        }
    )
}
