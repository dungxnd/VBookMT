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
import com.vbookmt.core.model.TranslateRequest
import com.vbookmt.core.model.TranslateResponse
import com.vbookmt.core.processor.TextProcessor
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: LocalServer? = null

    companion object {
        const val CHANNEL_ID = "vbookmt_service_channel"
        const val NOTIFICATION_ID = 8765
        const val PORT = 8765
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification("Đang chạy máy chủ dịch offline tại cổng $PORT")
                startForeground(NOTIFICATION_ID, notification)
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (httpServer == null) {
            try {
                httpServer = LocalServer(PORT)
                httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopServer() {
        httpServer?.stop()
        httpServer = null
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
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
                description = "Giữ máy chủ dịch cục bộ hoạt động cho vBook"
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
            .setContentTitle("VBookMT Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt Server", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private inner class LocalServer(port: Int) : NanoHTTPD("127.0.0.1", port) {
        private val json = Json { ignoreUnknownKeys = true }

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.POST && session.uri == "/translate") {
                val map = HashMap<String, String>()
                try {
                    session.parseBody(map)
                    val postData = map["postData"] ?: ""
                    val request = json.decodeFromString<TranslateRequest>(postData)

                    // Preprocess source
                    val encoded = TextProcessor.encodeSource(request.text)

                    // Run inference (placeholder for ONNX Runtime session call)
                    // In real inference: val out = onnxSession.translate(encoded)
                    // Postprocess target: val finalResult = TextProcessor.decodeTarget(out)
                    val dummyResult = "[VBookMT Offline]: " + TextProcessor.decodeTarget(encoded)

                    val resp = TranslateResponse(
                        success = true,
                        translatedText = dummyResult,
                        elapsedMs = 120
                    )
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        json.encodeToString(TranslateResponse.serializer(), resp)
                    )
                } catch (e: Exception) {
                    val errResp = TranslateResponse(
                        success = false,
                        translatedText = "",
                        error = e.message
                    )
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "application/json",
                        json.encodeToString(TranslateResponse.serializer(), errResp)
                    )
                }
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }
}
