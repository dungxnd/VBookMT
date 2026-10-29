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
import vn.dungxnd.vbookmt.MainActivity
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.TranslateRequest
import com.vbookmt.core.model.TranslateResponse
import com.vbookmt.core.processor.TextProcessor
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.measureTimeMillis

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    companion object {
        private const val TAG = "TranslationService"
        const val CHANNEL_ID = "vbookmt_service_channel"
        const val NOTIFICATION_ID = 8765
        const val PORT = 8765
        const val HOST = "127.0.0.1"
        const val MAX_INPUT_CHARS = 50_000 // DoS & OOM protection threshold (approx. 25-50 chapters)

        const val ACTION_START = "vn.dungxnd.vbookmt.ACTION_START"
        const val ACTION_STOP = "vn.dungxnd.vbookmt.ACTION_STOP"
        const val EXTRA_ACTIVE_MODEL = "EXTRA_ACTIVE_MODEL"

        // Reactive state for UI
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _activeModel = MutableStateFlow(ModelRegistry.HACHIMI_60_QT.id)
        val activeModel = _activeModel.asStateFlow()

        private val _lastLatencyMs = MutableStateFlow(0L)
        val lastLatencyMs = _lastLatencyMs.asStateFlow()

        private val _requestCount = MutableStateFlow(0)
        val requestCount = _requestCount.asStateFlow()

        // Debug log buffer for live in-app inspection
        private val _debugLogs = MutableStateFlow<List<String>>(
            listOf("[Init] VBookMT Service logger initialized.")
        )
        val debugLogs = _debugLogs.asStateFlow()

        fun log(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val formatted = "[$time] $msg"
            Log.d(TAG, formatted)
            _debugLogs.value = (_debugLogs.value + formatted).takeLast(150)
        }

        fun clearLogs() {
            _debugLogs.value = listOf("[Init] Nhật ký đã được làm mới.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                log("Dừng Translation Service...")
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                _isRunning.value = false
                return START_NOT_STICKY
            }
            else -> {
                val rawModelId = intent?.getStringExtra(EXTRA_ACTIVE_MODEL) ?: _activeModel.value
                val modelSpec = ModelRegistry.getById(rawModelId)
                _activeModel.value = modelSpec.id // Whitelisted ID to strictly prevent path traversal
                log("Khởi động Service với model: ${modelSpec.name} (${modelSpec.id})")
                val notification = buildNotification(
                    "Đang chạy CTranslate2 (${modelSpec.name}) tại cổng $PORT"
                )
                startForeground(NOTIFICATION_ID, notification)
                startKtorServer()
                _isRunning.value = true
            }
        }
        return START_STICKY
    }

    private fun startKtorServer() {
        if (serverEngine != null) return

        serverJob = serviceScope.launch {
            try {
                // Initialize CT2 model if folder exists
                val modelDir = File(filesDir, "models/${_activeModel.value}")
                if (modelDir.exists()) {
                    Ct2Translator.init(modelDir.absolutePath, threads = 2)
                } else {
                    Log.w(TAG, "Model directory not found on disk: ${modelDir.absolutePath}")
                }

                serverEngine = embeddedServer(CIO, port = PORT, host = HOST) {
                    install(CORS) {
                        anyHost()
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Options)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                    }

                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            prettyPrint = false
                            isLenient = true
                        })
                    }

                    install(StatusPages) {
                        exception<Throwable> { call, cause ->
                            Log.e(TAG, "Unhandled server error: ${cause.message}", cause)
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                TranslateResponse(
                                    success = false,
                                    translatedText = "",
                                    error = "Lỗi máy chủ nội bộ: ${cause.message}"
                                )
                            )
                        }
                    }

                    routing {
                        get("/") {
                            call.respondText(
                                "{\"status\":\"running\",\"engine\":\"CTranslate2\",\"port\":$PORT,\"active_model\":\"${_activeModel.value}\",\"ready\":${Ct2Translator.isReady()}}",
                                ContentType.Application.Json
                            )
                        }

                        get("/health") {
                            val runtime = Runtime.getRuntime()
                            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                            call.respondText(
                                "{\"status\":\"healthy\",\"model\":\"${_activeModel.value}\",\"requests\":${_requestCount.value},\"memory_mb\":$usedMemMb,\"ready\":${Ct2Translator.isReady()}}",
                                ContentType.Application.Json
                            )
                        }

                        post("/translate") {
                            try {
                                val request = call.receive<TranslateRequest>()
                                _requestCount.value += 1

                                if (request.text.isBlank()) {
                                    call.respond(
                                        HttpStatusCode.OK,
                                        TranslateResponse(success = true, translatedText = "", elapsedMs = 0)
                                    )
                                    return@post
                                }

                                // OOM / DoS protection: reject excessive text length
                                if (request.text.length > MAX_INPUT_CHARS) {
                                    log("CẢNH BÁO: Văn bản vượt quá giới hạn an toàn (${request.text.length} > $MAX_INPUT_CHARS)")
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        TranslateResponse(
                                            success = false,
                                            translatedText = "",
                                            error = "Văn bản quá dài (${request.text.length} ký tự). Giới hạn tối đa là $MAX_INPUT_CHARS ký tự để tránh tràn bộ nhớ."
                                        )
                                    )
                                    return@post
                                }

                                log("Nhận yêu cầu /translate: ${request.text.length} ký tự (model: ${request.model ?: _activeModel.value})")

                                var finalTranslatedText = ""
                                val elapsedMs = measureTimeMillis {
                                    // 1. Sanitize text
                                    val sanitized = TextProcessor.sanitize(request.text)

                                    // 2. Safe chunking for long chapters (> 1000 characters) to prevent MarianMT 512-token overflow
                                    val chunks = TextProcessor.splitIntoParagraphs(sanitized, maxCharsPerChunk = 900)
                                    log("Chia thành ${chunks.size} đoạn xử lý...")
                                    val translatedChunks = mutableListOf<String>()

                                    for ((index, chunk) in chunks.withIndex()) {
                                        val encoded = TextProcessor.encodeSource(chunk)
                                        val rawOut = Ct2Translator.translate(encoded, numBeams = 1)
                                        val decoded = TextProcessor.decodeTarget(rawOut)
                                        translatedChunks.add(decoded)
                                    }

                                    finalTranslatedText = translatedChunks.joinToString("\n")
                                }
                                _lastLatencyMs.value = elapsedMs
                                log("Dịch hoàn tất trong ${elapsedMs}ms (${finalTranslatedText.length} ký tự kết quả)")

                                call.respond(
                                    HttpStatusCode.OK,
                                    TranslateResponse(
                                        success = true,
                                        translatedText = finalTranslatedText,
                                        elapsedMs = elapsedMs
                                    )
                                )
                            } catch (e: Exception) {
                                log("LỖI /translate: ${e.message}")
                                Log.e(TAG, "Error handling /translate: ${e.message}", e)
                                call.respond(
                                    HttpStatusCode.OK, // return OK with error field so client receives structured JSON
                                    TranslateResponse(
                                        success = false,
                                        translatedText = "",
                                        error = "Lỗi xử lý văn bản: ${e.message}"
                                    )
                                )
                            }
                        }
                    }
                }

                serverEngine?.start(wait = true)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Server startup error: ${e.message}", e)
                }
            }
        }
    }

    private fun stopServer() {
        try {
            // Stop Ktor CIO server synchronously and close network sockets cleanly
            serverEngine?.stop(gracePeriodMillis = 100, timeoutMillis = 300)
            serverEngine = null
            serverJob?.cancel()
            serverJob = null
            log("Máy chủ đã dừng và giải phóng socket an toàn.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VBookMT Offline Translation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ máy chủ dịch CTranslate2 hoạt động cho vBook"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TranslationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VBookMT • CTranslate2 Engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng Server", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
