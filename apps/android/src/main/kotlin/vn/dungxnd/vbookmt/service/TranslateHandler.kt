package vn.dungxnd.vbookmt.service

import android.util.Log
import com.vbookmt.core.model.TranslateRequest
import com.vbookmt.core.model.TranslateResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `POST /translate` pipeline: validate, wait for the single translation permit, run, respond.
 *
 * Everything transport-shaped is here (status codes, error JSON, timeouts); the translation itself
 * is injected as [translateChapter] so this class never touches the engine or the chunk layout.
 *
 * The permit exists because CTranslate2's `Translator` is serialized anyway - queueing here keeps
 * the JVM heap predictable when vBook fires parallel chapter requests, instead of letting coroutines
 * pile up inside the native lock.
 */
internal class TranslateHandler(
    private val gate: Semaphore = Semaphore(permits = 1),
    private val inFlight: AtomicInteger,
    private val ensureModelReady: suspend () -> Boolean,
    private val translateChapter: suspend (String) -> String,
) {

    suspend fun handle(call: ApplicationCall) {
        ServiceState.noteRequestStarted()
        inFlight.incrementAndGet()
        try {
            val request = call.receive<TranslateRequest>()

            if (request.text.isBlank()) {
                call.respond(
                    HttpStatusCode.OK,
                    TranslateResponse(success = true, translatedText = "", elapsedMs = 0)
                )
                return
            }

            if (request.text.length > ServiceContract.MAX_INPUT_CHARS) {
                ServiceState.log(
                    "CẢNH BÁO: Văn bản quá dài (${request.text.length} > ${ServiceContract.MAX_INPUT_CHARS})"
                )
                call.respond(
                    HttpStatusCode.BadRequest,
                    TranslateResponse(
                        success = false,
                        translatedText = "",
                        error = "Văn bản quá dài (${request.text.length} ký tự). " +
                            "Giới hạn tối đa là ${ServiceContract.MAX_INPUT_CHARS} ký tự."
                    )
                )
                return
            }

            ServiceState.log(
                "Nhận /translate: ${request.text.length} ký tự (model=${ServiceState.activeModel.value})"
            )

            // The model must be live - otherwise 503 so the extension triggers its QT fallback.
            if (!ensureModelReady()) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    TranslateResponse(
                        success = false,
                        translatedText = "",
                        error = "CT2 chưa sẵn sàng (model chưa tải xong hoặc native lib lỗi). " +
                            "Vui lòng mở app VBookMT để tải model."
                    )
                )
                return
            }

            var output = ""
            var translateMs = 0L

            // Queue wait is deliberately outside the timeout budget: when vBook fires parallel
            // chapter requests, the second one used to burn its whole 120s allowance waiting for the
            // gate and then fail with a bogus 504.
            val queuedAt = System.currentTimeMillis()
            gate.withPermit {
                val waitedMs = System.currentTimeMillis() - queuedAt
                if (waitedMs > ServiceContract.QUEUE_WARN_MS) {
                    ServiceState.log("Xếp hàng ${waitedMs}ms trước khi tới lượt dịch")
                }
                val startedAt = System.currentTimeMillis()
                output = withTimeout(ServiceContract.TRANSLATE_TIMEOUT_MS) {
                    translateChapter(request.text)
                }
                translateMs = System.currentTimeMillis() - startedAt
            }

            ServiceState.setLastLatency(translateMs)
            ServiceState.log("Dịch hoàn tất trong ${translateMs}ms (${output.length} ký tự kết quả)")

            call.respond(
                HttpStatusCode.OK,
                TranslateResponse(success = true, translatedText = output, elapsedMs = translateMs)
            )
        } catch (e: TimeoutCancellationException) {
            ServiceState.log("Timeout dịch sau ${ServiceContract.TRANSLATE_TIMEOUT_MS}ms")
            call.respond(
                HttpStatusCode.GatewayTimeout,
                TranslateResponse(
                    success = false,
                    translatedText = "",
                    error = "Timeout xử lý bản dịch (>${ServiceContract.TRANSLATE_TIMEOUT_MS / 1000}s)"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServiceState.log("LỖI /translate: ${e.message}")
            Log.e(TAG, "Error handling /translate", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                TranslateResponse(
                    success = false,
                    translatedText = "",
                    error = "Lỗi xử lý văn bản: ${e.message}"
                )
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private companion object {
        const val TAG = "TranslateHandler"
    }
}
