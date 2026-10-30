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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.core.model.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.dungxnd.vbookmt.service.CacheStore
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.service.ChunkTranslator
import vn.dungxnd.vbookmt.service.Ct2Translator
import vn.dungxnd.vbookmt.service.ServiceState
import vn.dungxnd.vbookmt.ui.components.BackgroundOptimizationCard
import vn.dungxnd.vbookmt.ui.components.DebugLogCard
import vn.dungxnd.vbookmt.ui.components.ModelSection
import vn.dungxnd.vbookmt.ui.components.QuickTestCard
import vn.dungxnd.vbookmt.ui.components.NoticesSheetContent
import vn.dungxnd.vbookmt.ui.components.OptimizationSetupDialog
import vn.dungxnd.vbookmt.ui.components.PerformanceCard
import vn.dungxnd.vbookmt.ui.components.StatusHeroCard
import vn.dungxnd.vbookmt.util.BatteryOptimizationHelper
import vn.dungxnd.vbookmt.util.PerformanceTuner

// ============================================================================
// Main Dashboard Screen
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: (String) -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(ModelRegistry.HACHIMI_60_QT) }

    var showNoticesSheet by remember { mutableStateOf(false) }

    // Optimization & Auto-open State
    var isIgnoringBattery by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    var isNotificationGranted by remember { mutableStateOf(BatteryOptimizationHelper.isNotificationPermissionGranted(context)) }
    var isAutoStartOnOpen by remember { mutableStateOf(BatteryOptimizationHelper.isAutoStartServiceOnOpen(context)) }
    var showOptimizationDialog by remember { mutableStateOf(false) }

    // Performance state. Everything device-specific is decided for the user: this card only reports
    // the outcome in plain words and offers one optional "measure my phone" action.
    var tuning by remember { mutableStateOf<PerformanceTuner.Tuning?>(null) }
    var isTuning by remember { mutableStateOf(false) }
    val tuneProgress by PerformanceTuner.progress.collectAsState()
    var cacheStats by remember { mutableStateOf(ChunkTranslator.cacheStats()) }

    // Live Debug Logs State
    val debugLogs by ServiceState.debugLogs.collectAsState()
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
            isRunning = true
        }



        // Pre-initialize CT2 translator in background for instant quick testing, honouring any
        // tuning this phone has already measured.
        withContext(Dispatchers.IO) {
            // Warm the memo from disk first: the quick test and a re-opened chapter then cost nothing,
            // even when the app was killed between reads.
            CacheStore.attach(context)
            if (!Ct2Translator.isReady()) {
                val modelDir = java.io.File(context.filesDir, "models/${selectedModel.id}")
                if (!modelDir.exists()) modelDir.mkdirs()
                PerformanceTuner.applySaved(context, modelDir.absolutePath) ||
                    Ct2Translator.init(modelDir.absolutePath)
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
                                text = "CT2 Offline",
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
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Hero Status Card
            item {
                StatusHeroCard(
                    isRunning = isRunning,
                    port = 8765,
                    onToggle = {
                        if (isRunning) {
                            onStopService()
                            isRunning = false
                        } else {
                            onStartService(selectedModel.id)
                            isRunning = true
                        }
                    },
                    onCopyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VBookMT URL", "http://127.0.0.1:8765/translate"))
                        Toast.makeText(context, "Đã sao chép địa chỉ server!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 2. Background & Battery Optimization Card
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
            // 3. Performance card: one button, zero knobs
            item {
                PerformanceCard(
                    tuning = tuning,
                    isTuning = isTuning,
                    progress = tuneProgress,
                    cacheText = PerformanceTuner.cacheText(cacheStats),
                    onTune = {
                        isTuning = true
                        coroutineScope.launch {
                            val modelDir = java.io.File(context.filesDir, "models/${selectedModel.id}")
                            val result = withContext(Dispatchers.IO) {
                                PerformanceTuner.autoTune(context, modelDir.absolutePath)
                            }
                            tuning = result ?: PerformanceTuner.current(context)
                            isTuning = false
                            cacheStats = ChunkTranslator.cacheStats()
                            Toast.makeText(
                                context,
                                if (result != null) {
                                    "Đã tối ưu: ${result.speedText}"
                                } else {
                                    "Chưa tối ưu được — hãy bật server rồi thử lại"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                )
            }


            // 3. Model chooser: section header + one card per bundled model
            item {
                ModelSection(
                    selectedModel = selectedModel,
                    onSelectModel = { selectedModel = it },
                )
            }

            // 4. Quick Test & Diagnostics Card
            item {
                QuickTestCard(modelId = selectedModel.id)
            }

            // 5. Live Debug Log Console Card
            item {
                DebugLogCard(
                    isEnabled = isDebugLogEnabled,
                    logs = debugLogs,
                    onToggle = { isDebugLogEnabled = it },
                    onClearLogs = { ServiceState.clearLogs() },
                    onCopyLogs = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VBookMT Logs", debugLogs.joinToString("\n")))
                        Toast.makeText(context, "Đã sao chép ${debugLogs.size} dòng log!", Toast.LENGTH_SHORT).show()
                    }
                )
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
