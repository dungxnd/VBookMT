package vn.dungxnd.vbookmt.service

import android.util.Log
import com.vbookmt.core.model.TranslateConfig
import com.vbookmt.core.model.TranslateResponse
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
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * The local HTTP surface: `/`, `/health`, `/config` (tuning) and `/translate`.
 *
 * No translation logic lives here - it maps requests to [TranslateHandler] and [TranslationSettings]
 * and formats JSON, which keeps the routing readable and lets the service worry only about
 * lifecycle.
 */
internal class TranslationHttpServer(
    private val port: Int,
    private val inFlight: AtomicInteger,
    private val translateHandler: TranslateHandler,
) {

    fun create(): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(CIO, port = port, host = ServiceContract.HOST) {
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
                            append("\"port\":$port,")
                            append("\"active_model\":\"${ServiceState.activeModel.value}\",")
                            append("\"ready\":${Ct2Translator.isReady()},")
                            append("\"inflight\":${inFlight.get()}}")
                        },
                        ContentType.Application.Json
                    )
                }

                get("/health") {
                    val batchType = if (Ct2Translator.useTokenBatching) "tokens" else "examples"
                    val cache = ChunkTranslator.cacheStats()
                    call.respondText(
                        buildString {
                            append("{\"status\":\"healthy\",")
                            append("\"model\":\"${ServiceState.activeModel.value}\",")
                            append("\"requests\":${ServiceState.requestCount.value},")
                            append("\"inflight\":${inFlight.get()},")
                            append("\"pss_mb\":${Diagnostics.processPssMb()},")
                            append("\"jvm_heap_mb\":${Diagnostics.jvmHeapMb()},")
                            append("\"threads\":${Ct2Translator.activeThreads},")
                            append("\"max_batch\":${Ct2Translator.maxBatchSize},")
                            append("\"batch_type\":\"$batchType\",")
                            append("\"compute_type\":\"${Ct2Translator.activeComputeType}\",")
                            append("\"chunk_chars\":${ChapterTranslator.chunkChars},")
                            // Hit rate answers "is the cache earning its RAM here?".
                            append("\"cache_enabled\":${ChunkTranslator.cacheEnabled},")
                            append("\"cache_entries\":${cache.entries},")
                            append("\"cache_chars\":${cache.payloadChars},")
                            append("\"cache_hits\":${cache.hits},")
                            append("\"cache_misses\":${cache.misses},")
                            append("\"cache_hit_pct\":${cache.hitRatePercent},")
                            // Durable-tier counters: proof that the journal is being written and replayed.
                            append("\"cache_disk_bytes\":${CacheStore.diskBytes()},")
                            append("\"cache_disk_restored\":${CacheStore.restoredEntries()},")
                            append("\"ready\":${Ct2Translator.isReady()}}")
                        },
                        ContentType.Application.Json
                    )
                }

                // Localhost-only tuning hook: lets the UI (or a curious owner) sweep batch/thread/
                // precision settings on a real device instead of needing one rebuild per config.
                post("/config") {
                    call.respondText(
                        TranslationSettings.apply(call.receive<TranslateConfig>()),
                        ContentType.Application.Json
                    )
                }

                post("/translate") {
                    translateHandler.handle(call)
                }
            }
        }

    private companion object {
        const val TAG = "TranslationService"
    }
}
