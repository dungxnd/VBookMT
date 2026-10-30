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
import com.vbookmt.core.model.TranslateRequest
import com.vbookmt.core.model.TranslateResponse
import com.vbookmt.core.processor.TextProcessor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import vn.dungxnd.vbookmt.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("VBookMT-Service")
    )

    @Volatile
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Serializes concurrent /translate requests. CTranslate2's native Translator is already
     * @Synchronized in [Ct2Translator], but this caps coroutine pile-up so the JVM/heap stays
     * predictable even when vBook fires parallel chapter requests.
     */
    private val translationGate = Semaphore(permits = 1)

    private val inFlightRequests = AtomicInteger(0)

    companion object {
        private const val TAG = "TranslationService"
        const val CHANNEL_ID = "vbookmt_service_channel"
        const val NOTIFICATION_ID = 8765
        const val PORT = 8765
        const val HOST = "127.0.0.1"

        /**
         * Hard cap on one request body (OOM / DoS guard). The client-side limit is the extension's
         * own `timeout_seconds`, not this.
         */
        const val MAX_INPUT_CHARS = 50_000

        /**
         * Target chunk length in characters - a quality knob, not a throughput knob.
         *
         * Measured on HachimiMT-60-QT: 100-200 characters per sequence translated every marker of a
         * source passage, ~250-350 started dropping content, and 450+ lost whole sentences. The old
         * comment here claimed 350 chars ~= 350 tokens; the real source.spm costs ~0.62 tokens/char,
         * so 350 chars is ~215 tokens (42% of the cap) while 350 chars of rare glyphs is ~720
         * tokens and overflows it. Throughput comes from batching, not from bigger chunks.
         */
        private const val MAX_CHUNK_CHARS = 280

        /** Don't look for a sentence boundary until at least this much text is buffered. */
        private const val MIN_SOFT_SPLIT_CHARS = 150

        /** Smallest chunk the token guard will produce for pathological (rare-glyph) text. */
        private const val MIN_TOKEN_SPLIT_CHARS = 40

        /** Sentence terminators. '；'/';' are clause separators in Chinese, not sentence ends. */
        private val SENTENCE_ENDERS = charArrayOf('。', '！', '？', '!', '?', '…')

        /** Last-resort cut points inside an over-long run-on sentence. */
        private val CLAUSE_BOUNDARIES = charArrayOf(
            '。', '！', '？', '!', '?', '…', '，', '、', '：', '；', ',', ':', ';'
        )

        /** Ceiling for actual translation work; queue wait is excluded (see handleTranslate). */
        private const val TRANSLATE_TIMEOUT_MS = 120_000L

        /** Log when a request sat waiting for the translation gate this long. */
        private const val QUEUE_WARN_MS = 2_000L

        const val ACTION_START = "vn.dungxnd.vbookmt.ACTION_START"
        const val ACTION_STOP  = "vn.dungxnd.vbookmt.ACTION_STOP"
        const val EXTRA_ACTIVE_MODEL = "EXTRA_ACTIVE_MODEL"

        private val _isRunning     = MutableStateFlow(false)
        val isRunning              = _isRunning.asStateFlow()

        private val _activeModel   = MutableStateFlow(ModelRegistry.HACHIMI_60_QT.id)
        val activeModel            = _activeModel.asStateFlow()

        private val _lastLatencyMs = MutableStateFlow(0L)
        val lastLatencyMs          = _lastLatencyMs.asStateFlow()

        private val _requestCount  = MutableStateFlow(0)
        val requestCount           = _requestCount.asStateFlow()

        private val _debugLogs     = MutableStateFlow(listOf("[Init] VBookMT Service logger initialized."))
        val debugLogs              = _debugLogs.asStateFlow()

        // SimpleDateFormat is not thread-safe, and allocating a new one for every log entry was
        // measurable inside the translation loop. One instance per thread is enough.
        private val timeFormatter = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() =
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        }

        fun log(msg: String) {
            val time = timeFormatter.get()?.format(java.util.Date()).orEmpty()
            val formatted = "[$time] $msg"
            Log.d(TAG, formatted)
            _debugLogs.value = (_debugLogs.value + formatted).takeLast(150)
        }

        fun clearLogs() {
            _debugLogs.value = listOf("[Init] Nhật ký đã được làm mới.")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

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
                val modelSpec  = ModelRegistry.getById(rawModelId)
                _activeModel.value = modelSpec.id
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

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------------------------------------
    // Ktor server
    // -----------------------------------------------------------------------------------------

    private fun startKtorServer() {
        if (serverEngine != null) return

        serviceScope.launch {
            try {
                val engine = embeddedServer(CIO, port = PORT, host = HOST) {
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
                                buildString {
                                    append("{\"status\":\"running\",")
                                    append("\"engine\":\"CTranslate2\",")
                                    append("\"port\":$PORT,")
                                    append("\"active_model\":\"${_activeModel.value}\",")
                                    append("\"ready\":${Ct2Translator.isReady()},")
                                    append("\"inflight\":${inFlightRequests.get()}}")
                                },
                                ContentType.Application.Json
                            )
                        }

                        get("/health") {
                            val runtime   = Runtime.getRuntime()
                            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                            call.respondText(
                                buildString {
                                    append("{\"status\":\"healthy\",")
                                    append("\"model\":\"${_activeModel.value}\",")
                                    append("\"requests\":${_requestCount.value},")
                                    append("\"inflight\":${inFlightRequests.get()},")
                                    append("\"memory_mb\":$usedMemMb,")
                                    append("\"ready\":${Ct2Translator.isReady()}}")
                                },
                                ContentType.Application.Json
                            )
                        }

                        post("/translate") {
                            call.handleTranslate()
                        }
                    }
                }

                serverEngine = engine

                // Model init in parallel with server bind — /health works immediately either way.
                launch { ensureModelReady() }

                engine.start(wait = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("LỖI server startup: ${e.message}")
                Log.e(TAG, "Server startup error", e)
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        try {
            serverEngine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            serverEngine = null
            log("Máy chủ đã dừng và giải phóng socket an toàn.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // /translate handler
    // -----------------------------------------------------------------------------------------

    private suspend fun io.ktor.server.application.ApplicationCall.handleTranslate() {
        _requestCount.value += 1
        inFlightRequests.incrementAndGet()
        try {
            val request = receive<TranslateRequest>()

            // Fast-path empty
            if (request.text.isBlank()) {
                respond(HttpStatusCode.OK, TranslateResponse(success = true, translatedText = "", elapsedMs = 0))
                return
            }

            // OOM / DoS guard
            if (request.text.length > MAX_INPUT_CHARS) {
                log("CẢNH BÁO: Văn bản quá dài (${request.text.length} > $MAX_INPUT_CHARS)")
                respond(
                    HttpStatusCode.BadRequest,
                    TranslateResponse(
                        success = false,
                        translatedText = "",
                        error = "Văn bản quá dài (${request.text.length} ký tự). Giới hạn tối đa là $MAX_INPUT_CHARS ký tự."
                    )
                )
                return
            }

            log("Nhận /translate: ${request.text.length} ký tự (model=${request.model})")

            // Model must be live — otherwise 503 so the extension triggers QT fallback
            if (!ensureModelReady()) {
                respond(
                    HttpStatusCode.ServiceUnavailable,
                    TranslateResponse(
                        success = false,
                        translatedText = "",
                        error = "CT2 chưa sẵn sàng (model chưa tải xong hoặc native lib lỗi). Vui lòng mở app VBookMT để tải model."
                    )
                )
                return
            }

            var output = ""
            var translateMs = 0L

            // Queue wait is deliberately outside the timeout budget: when vBook fires parallel
            // chapter requests, the second one used to burn its whole 120s allowance waiting for
            // the gate and then fail with a bogus 504.
            val queuedAt = System.currentTimeMillis()
            translationGate.withPermit {
                val waitedMs = System.currentTimeMillis() - queuedAt
                if (waitedMs > QUEUE_WARN_MS) {
                    log("Xếp hàng ${waitedMs}ms trước khi tới lượt dịch")
                }
                val startedAt = System.currentTimeMillis()
                output = withTimeout(TRANSLATE_TIMEOUT_MS) { translateChapter(request.text) }
                translateMs = System.currentTimeMillis() - startedAt
            }

            _lastLatencyMs.value = translateMs
            log("Dịch hoàn tất trong ${translateMs}ms (${output.length} ký tự kết quả)")

            respond(
                HttpStatusCode.OK,
                TranslateResponse(success = true, translatedText = output, elapsedMs = translateMs)
            )
        } catch (e: TimeoutCancellationException) {
            log("Timeout dịch sau ${TRANSLATE_TIMEOUT_MS}ms")
            respond(
                HttpStatusCode.GatewayTimeout,
                TranslateResponse(
                    success = false,
                    translatedText = "",
                    error = "Timeout xử lý bản dịch (>${TRANSLATE_TIMEOUT_MS / 1000}s)"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("LỖI /translate: ${e.message}")
            Log.e(TAG, "Error handling /translate", e)
            respond(
                HttpStatusCode.InternalServerError,
                TranslateResponse(
                    success = false,
                    translatedText = "",
                    error = "Lỗi xử lý văn bản: ${e.message}"
                )
            )
        } finally {
            inFlightRequests.decrementAndGet()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Translation core
    // -----------------------------------------------------------------------------------------

    /**
     * Translate a chapter paragraph-by-paragraph, mirroring `tools/model_converter/compare_models.py`.
     *
     * Chunks are collected first and then handed to [Ct2Translator.translateBatch] in batches of
     * [Ct2Translator.MAX_BATCH_SIZE]. The previous implementation called the native engine once per
     * paragraph, so it paid a fixed per-call cost hundreds of times per chapter and never filled a
     * batch: measured 3.0-4.6x slower, for output that is equivalent but not literally
     * byte-identical (batch padding can shift a stray token).
     *
     * This is a `suspend` function and yields between batches so `withTimeout` can actually fire -
     * a fully blocking implementation silently ignores its own timeout.
     */
    private suspend fun translateChapter(rawText: String): String {
        val sanitized = TextProcessor.sanitize(rawText)
        if (sanitized.isBlank()) return ""

        val chunks = ArrayList<Chunk>(64)
        var paragraphCount = 0
        var blankRun = 0
        var isFirst = true

        for (paragraph in sanitized.split('\n')) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                if (!isFirst) blankRun = 1 // preserve at most one blank line
                continue
            }
            paragraphCount++

            for ((index, piece) in splitForTranslation(trimmed).withIndex()) {
                val newlines = when {
                    isFirst -> 0
                    index > 0 -> 0               // continuation of the same source paragraph
                    else -> 1 + blankRun         // paragraph break, plus a blank line if present
                }
                chunks.add(Chunk(piece, newlines))
            }
            isFirst = false
            blankRun = 0
        }

        if (chunks.isEmpty()) return ""

        val out = StringBuilder(sanitized.length + sanitized.length / 2)
        var cursor = 0
        var batchCount = 0

        while (cursor < chunks.size) {
            currentCoroutineContext().ensureActive()

            val end = minOf(cursor + Ct2Translator.MAX_BATCH_SIZE, chunks.size)
            val slice = chunks.subList(cursor, end)
            val translated = Ct2Translator.translateBatch(slice.map { it.text })
            batchCount++

            for (index in slice.indices) {
                val chunk = slice[index]
                if (chunk.newlinesBefore > 0) {
                    repeat(chunk.newlinesBefore) { out.append('\n') }
                } else if (out.isNotEmpty()) {
                    out.append(' ')
                }
                out.append(TextProcessor.decodeTarget(translated[index]))
            }

            cursor = end
        }

        log(
            "Dịch xong: $paragraphCount đoạn, ${chunks.size} chunks, " +
                "$batchCount batch, ${out.length} ký tự đầu ra"
        )
        return out.toString()
    }

    /** One unit of translation: [newlinesBefore] newlines to emit before it in the output. */
    private class Chunk(val text: String, val newlinesBefore: Int)

    /** Character-budget split first (quality), then token-safety split (correctness). */
    private fun splitForTranslation(paragraph: String): List<String> =
        splitByBudget(paragraph, MAX_CHUNK_CHARS).flatMap { ensureTokenBudget(it, 0) }

    /**
     * Rare glyphs cost up to ~2.9 SentencePiece tokens each instead of ~0.62, so a character budget
     * alone can still overflow the model's 512-position table: 250 characters of 龘靐齉... measure
     * 728 tokens, which made CT2 throw and silently degrade the whole chunk to Sino-Vietnamese.
     *
     * For a given text the tokens/char ratio is roughly constant, so rescaling the character budget
     * by the ratio observed here converges in a single level. The 0.8 factor absorbs
     * [splitByBudget]'s 8% hard-limit overshoot, which would otherwise push the rescaled chunk back
     * over budget (measured: a 162-character chunk of rare glyphs still cost 472 tokens).
     */
    private fun ensureTokenBudget(text: String, depth: Int): List<String> {
        val tokens = Ct2Translator.tokenCount(text)
        if (tokens == 0 || tokens <= Ct2Translator.TOKEN_BUDGET) return listOf(text)
        if (depth >= 4 || text.length <= MIN_TOKEN_SPLIT_CHARS) return listOf(text)

        val safeChars = (text.length.toLong() * Ct2Translator.TOKEN_BUDGET / tokens * 8L / 10L)
            .toInt()
            .coerceIn(MIN_TOKEN_SPLIT_CHARS, text.length - 1)

        val pieces = splitByBudget(text, safeChars)
        // No progress means no clause boundary to cut at; stop rather than recurse forever.
        if (pieces.size <= 1 && pieces.firstOrNull() == text) return listOf(text)

        return pieces.flatMap { ensureTokenBudget(it, depth + 1) }
    }

    /**
     * Split one paragraph into chunks of at most ~[budget] characters.
     *
     * Only sentence terminators end a chunk ('；'/';' separate clauses in Chinese and used to cut
     * subject-verb pairs apart), and an over-long run-on backs off to the nearest clause boundary
     * instead of being sliced at an arbitrary character offset.
     */
    private fun splitByBudget(text: String, budget: Int): List<String> {
        if (text.length <= budget) return listOf(text)

        val result = ArrayList<String>(text.length / budget + 1)
        val buffer = StringBuilder(budget + 32)
        val hardLimit = (budget * 1.08).toInt().coerceAtLeast(budget)

        for (ch in text) {
            buffer.append(ch)
            when {
                ch in SENTENCE_ENDERS && buffer.length >= MIN_SOFT_SPLIT_CHARS -> {
                    result.add(buffer.toString())
                    buffer.clear()
                }
                buffer.length >= hardLimit -> {
                    val joined = buffer.toString()
                    val cut = joined.lastIndexOfAny(CLAUSE_BOUNDARIES)
                    if (cut >= MIN_SOFT_SPLIT_CHARS / 2) {
                        result.add(joined.substring(0, cut + 1))
                        buffer.clear()
                        buffer.append(joined, cut + 1, joined.length)
                    } else {
                        result.add(joined)
                        buffer.clear()
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) result.add(buffer.toString())
        return result
    }

    // -----------------------------------------------------------------------------------------
    // Model readiness
    // -----------------------------------------------------------------------------------------

    private suspend fun ensureModelReady(): Boolean = withContext(Dispatchers.IO) {
        if (Ct2Translator.isReady()) return@withContext true

        val dir = File(filesDir, "models/${_activeModel.value}")
        if (!dir.exists() || !dir.isDirectory) {
            log("Model chưa tải: ${dir.absolutePath}")
            return@withContext false
        }

        // Threads resolve inside Ct2Translator: CT2 ignores intra_threads unless it is passed, and
        // its built-in default (4) oversubscribes big.LITTLE phones.
        val ok = Ct2Translator.init(dir.absolutePath)
        if (ok) log("CT2 model sẵn sàng: ${dir.absolutePath}")
        else    log("CT2 init THẤT BẠI: ${dir.absolutePath}")
        ok
    }

    // -----------------------------------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------------------------------

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
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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