package vn.dungxnd.vbookmt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vbookmt.core.model.ModelRegistry
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.MainActivity
import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator
import vn.dungxnd.vbookmt.util.EnergyHarness
import vn.dungxnd.vbookmt.util.PerformanceTuner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the lifecycle of the translation server.
 *
 * Deliberately thin: start/stop, the persistent notification, and bringing the native model up.
 * The HTTP surface lives in [TranslationHttpServer], request handling in [TranslateHandler],
 * translation in [ChapterTranslator]/[ChunkTranslator]/[OnnxTranslator], tuning in
 * [TranslationSettings], and shared status in [ServiceState].
 */
class TranslationService : Service() {

    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("VBookMT-Service")
    )

    private val inFlightRequests = AtomicInteger(0)

    @Volatile
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun onCreate() {
        super.onCreate()
        // One application context for the translation layer, so /config can persist settings without
        // holding (or leaking) a Service reference.
        ServiceContextHolder.attach(this)
        // Energy/thermal sampling (S0): attached once per process so the sampler thread outlives a
        // single chapter but still dies with the service.
        EnergyHarness.attach(this)
        // Replay the durable memo off the main thread. Nothing waits for it: a request that arrives
        // first simply starts from a cold memo and fills the journal again.
        serviceScope.launch { CacheStore.attach(applicationContext) }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceContract.ACTION_STOP -> {
                ServiceState.log("Dừng Translation Service...")
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                ServiceState.setRunning(false)
                return START_NOT_STICKY
            }

            else -> {
                // A model named in `files/active-model` wins over the UI selection. Benchmarking has
                // to switch models repeatedly, and driving a Compose radio button over adb is fragile
                // (scroll position, transient overlays, the performance sweep swallowing taps). One
                // file, written with `run-as`, makes a sweep reproducible:
                //   adb shell "run-as <pkg> sh -c 'echo moxhi-30-qt-onnx > files/active-model'"
                val fileModel = runCatching {
                    File(filesDir, "active-model").takeIf { it.isFile }?.readText()?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }

                val requestedModel = fileModel
                    ?: intent?.getStringExtra(ServiceContract.EXTRA_ACTIVE_MODEL)
                    ?: ServiceState.activeModel.value
                val modelSpec = ModelRegistry.getById(requestedModel)
                val previousModel = ServiceState.activeModel.value
                ServiceState.setActiveModel(modelSpec.id)
                if (previousModel != modelSpec.id) {
                    // Cache keys already include the model path, so these entries are unreachable;
                    // dropping them also frees the memory.
                    ChunkTranslator.clearCache()
                    ServiceState.log("Đổi model -> xoá cache bản dịch")
                    serviceScope.launch(Dispatchers.IO) {
                        ensureModelReady()
                    }
                }
                ServiceState.log("Khởi động Service với model: ${modelSpec.name} (${modelSpec.id})")

                startForeground(
                    ServiceContract.NOTIFICATION_ID,
                    buildNotification(
                        "Đang chạy ONNX (${modelSpec.name}) tại cổng ${ServiceContract.PORT}"
                    )
                )
                startKtorServer()
                ServiceState.setRunning(true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        ServiceState.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------------------------
    // Server lifecycle
    // -----------------------------------------------------------------------------------------

    private fun startKtorServer() {
        if (serverEngine != null) return

        serviceScope.launch {
            try {
                val engine = TranslationHttpServer(
                    port = ServiceContract.PORT,
                    inFlight = inFlightRequests,
                    ensureModelReady = { ensureModelReady() },
                ).create()

                serverEngine = engine

                // Model init in parallel with server bind - /health works immediately either way.
                launch { ensureModelReady() }

                engine.start(wait = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceState.log("LỖI server startup: ${e.message}")
                Log.e(TAG, "Server startup error", e)
                ServiceState.setRunning(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        try {
            serverEngine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            serverEngine = null
            ServiceState.log("Máy chủ đã dừng và giải phóng socket an toàn.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Model readiness
    // -----------------------------------------------------------------------------------------

    private suspend fun ensureModelReady(): Boolean = withContext(Dispatchers.IO) {
        val spec = ModelRegistry.getById(ServiceState.activeModel.value)
        val dir = File(filesDir, "models/${ServiceState.activeModel.value}")

        if (OnnxTranslator.isReady() && OnnxTranslator.getActiveModelPath() == dir.absolutePath) {
            return@withContext true
        }

        if (!dir.exists() || !dir.isDirectory) {
            ServiceState.log("Model chưa tải: ${dir.absolutePath}")
            return@withContext false
        }

        // A phone that has been tuned keeps its measured thread count across restarts; otherwise the
        // engine picks the big-core-aware default. ONNX Runtime does not manage its own pool, so
        // getting the thread count right actually matters.
        val appliedSaved = PerformanceTuner.applySaved(this@TranslationService, dir)
        val ok = appliedSaved || OnnxTranslator.init(dir)
        if (ok) {
            val tuning = PerformanceTuner.current(this@TranslationService)
            ServiceState.log("ONNX sẵn sàng: ${tuning.detailText} → ${tuning.speedText}")
        } else {
            ServiceState.log("ONNX init THẤT BẠI: ${dir.absolutePath}")
        }
        ok
    }

    // -----------------------------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ServiceContract.CHANNEL_ID,
                "VBookMT Offline Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ máy chủ dịch ONNX hoạt động cho vBook"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TranslationService::class.java).apply {
            action = ServiceContract.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ServiceContract.CHANNEL_ID)
            .setContentTitle("VBookMT • ONNX Engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dừng Server",
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private companion object {
        const val TAG = "TranslationService"
    }
}
