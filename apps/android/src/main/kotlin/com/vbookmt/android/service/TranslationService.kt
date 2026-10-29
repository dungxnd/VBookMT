package com.vbookmt.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vbookmt.android.MainActivity
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
        const val CHANNEL_ID = "vbookmt_service_channel"
        const val NOTIFICATION_ID = 8765
        const val PORT = 8765
        const val HOST = "127.0.0.1"

        const val ACTION_START = "com.vbookmt.android.ACTION_START"
        const val ACTION_STOP = "com.vbookmt.android.ACTION_STOP"
        const val EXTRA_ACTIVE_MODEL = "EXTRA_ACTIVE_MODEL"

        // Reactive state for UI to observe
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _activeModel = MutableStateFlow(ModelRegistry.HACHIMI_60_QT.id)
        val activeModel = _activeModel.asStateFlow()

        private val _lastLatencyMs = MutableStateFlow(0L)
        val lastLatencyMs = _lastLatencyMs.asStateFlow()

        private val _requestCount = MutableStateFlow(0)
        val requestCount = _requestCount.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                _isRunning.value = false
                return START_NOT_STICKY
            }
            else -> {
                val modelId = intent?.getStringExtra(EXTRA_ACTIVE_MODEL) ?: _activeModel.value
                _activeModel.value = modelId
                
                val modelSpec = ModelRegistry.getById(modelId)
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
                // Initialize CT2 model
                val modelDir = File(filesDir, "models/${_activeModel.value}")
                if (modelDir.exists()) {
                    Ct2Translator.init(modelDir.absolutePath, threads = 2)
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

                    routing {
                        get("/") {
                            call.respondText(
                                "{\"status\":\"running\",\"engine\":\"CTranslate2\",\"port\":$PORT,\"active_model\":\"${_activeModel.value}\"}",
                                ContentType.Application.Json
                            )
                        }

                        post("/translate") {
                            val request = call.receive<TranslateRequest>()
                            _requestCount.value += 1

                            var translatedText = ""
                            val elapsedMs = measureTimeMillis {
                                val encoded = TextProcessor.encodeSource(request.text)
                                val translatedRaw = Ct2Translator.translate(encoded, numBeams = 1)
                                translatedText = TextProcessor.decodeTarget(translatedRaw)
                            }
                            _lastLatencyMs.value = elapsedMs

                            call.respond(
                                HttpStatusCode.OK,
                                TranslateResponse(
                                    success = true,
                                    translatedText = translatedText,
                                    elapsedMs = elapsedMs
                                )
                            )
                        }
                    }
                }

                serverEngine?.start(wait = true)
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            try {
                serverEngine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
                serverEngine = null
                serverJob?.cancel()
                serverJob = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
