package com.vbookmt.wire.v1

/**
 * The six RPCs the plugin can call, expressed as host behaviour rather than as HTTP.
 *
 * Each host supplies one implementation; [GrpcWebDispatcher] owns everything else — framing, size
 * caps, status codes and the Vietnamese message a reader eventually sees. Keeping the mapping in
 * `core` is the point: when Android and Windows each carried their own copy, Windows silently shipped
 * without the size cap, without `GetConfig`/`SetConfig` and with a different status for a missing
 * model, so the same chapter behaved differently depending on which host was running.
 *
 * Implementations may hold locks, touch native code or suspend; the dispatcher never calls one while
 * holding state of its own.
 */
interface TranslatorService {

    /**
     * Translates one chapter.
     *
     * Must not throw for an expected condition — return a [TranslateOutcome] instead, so the
     * dispatcher (and not the host) decides which status code that condition deserves.
     */
    suspend fun translate(text: String): TranslateOutcome

    /** Liveness and tuning snapshot. Must never throw. */
    suspend fun health(): HealthReport

    /** Current tuning knobs. */
    suspend fun getConfig(): TranslateConfig

    /**
     * Applies a patch where a `null` field means "leave unchanged" (proto3 explicit presence).
     *
     * @return the effective configuration after the patch.
     */
    suspend fun setConfig(patch: TranslateConfig): ConfigResult

    /** S0 energy-harness records, newest session last. */
    suspend fun getEnergy(): EnergySnapshot = EnergySnapshot()

    /** Truncates the energy harness. */
    suspend fun resetEnergy(): ResetResult = ResetResult(reset = true)
}

/**
 * Result of one `Translate` call, classified by *why* it failed rather than by how it failed.
 *
 * The dispatcher maps each case to one gRPC status; the plugin maps each status to one reader-visible
 * behaviour (retry, keep the source, fall back to QuickTranslator). Both mappings live in
 * [GrpcWebDispatcher] and docs/GRPC_WEB_MIGRATION.md §2 so a new failure mode cannot pick an
 * arbitrary code.
 */
sealed class TranslateOutcome {

    /** Translated text to show. Empty input is a success with empty text, never an error. */
    data class Success(val translatedText: String, val elapsedMs: Int = 0) : TranslateOutcome()

    /** Input larger than [GrpcWebDispatcher.MAX_SOURCE_CHARS]; the plugin shows the source unchanged. */
    data class TooLong(val length: Int, val max: Int) : TranslateOutcome()

    /** No model loaded yet — retryable, and the caller falls back only after its own retry. */
    data class ModelUnavailable(val detail: String) : TranslateOutcome()

    /** The host spent its whole work budget; retrying immediately will not help. */
    data class DeadlineExceeded(val timeoutMs: Long) : TranslateOutcome()

    /** Anything else. [message] reaches the reader's error path verbatim. */
    data class Failure(val message: String) : TranslateOutcome()
}

/**
 * Wires [TranslatorService] to the gRPC-Web wire contract: one request body in, one reply out.
 *
 * Transport-free on purpose — it takes the already-read `text/plain` body and returns the headers and
 * body a host should write. That is what lets both Ktor hosts (Android's CIO server and Windows')
 * share it, and what lets `commonTest` exercise every status code without opening a socket.
 */
object GrpcWebDispatcher {

    /**
     * Source cap in characters, matching the plugin's own limit. Vietnamese output runs ~1.5x the
     * source, so 50 000 characters of Chinese stays inside the 256 KiB message cap below.
     */
    const val MAX_SOURCE_CHARS = 50_000

    /** Content type of both the request and the reply, per the wire contract. */
    const val CONTENT_TYPE = "application/grpc-web-text+proto"

    /** Methods the contract defines. A path outside this set is a transport-level 404, not an RPC status. */
    val METHODS = setOf("Translate", "Health", "GetConfig", "SetConfig", "GetEnergy", "ResetEnergy")

    /**
     * One fully-decided reply: what to write, with which status, and — on failure — why.
     *
     * @property payload framed and base64-encoded protobuf, or `""` on failure (the contract sends an
     *   empty body when [status] is not OK).
     */
    class Reply(
        val status: GrpcWebEnvelope.GrpcStatus,
        val payload: String,
        val message: String = "",
    ) {
        val isSuccess: Boolean get() = status == GrpcWebEnvelope.GrpcStatus.OK

        /** `grpc-status` value as it goes on the wire. */
        val statusHeader: String get() = status.code.toString()
    }

    /**
     * Dispatches one unary call.
     *
     * @param method the last path segment, e.g. `Translate`.
     * @param bodyText the request body exactly as received (`application/grpc-web-text+proto`).
     * @return the reply, or null when [method] is not part of the contract (the caller answers 404).
     */
    suspend fun dispatch(
        service: TranslatorService,
        method: String,
        bodyText: String,
    ): Reply? {
        if (method !in METHODS) return null

        return try {
            when (method) {
                "Translate" -> translate(service, bodyText)
                "Health" -> {
                    // `Health` takes an empty message, so a body is optional and never parsed.
                    ok(ProtoCodec.encodeHealthReport(sanitize(service.health())))
                }
                "GetConfig" -> ok(ProtoCodec.encodeTranslateConfig(service.getConfig()))
                "SetConfig" -> setConfig(service, bodyText)
                "GetEnergy" -> ok(ProtoCodec.encodeEnergySnapshot(service.getEnergy()))
                "ResetEnergy" -> ok(ProtoCodec.encodeResetResult(service.resetEnergy()))
                else -> null
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation: the host's own timeout depends on it propagating.
            throw cancellation
        } catch (failure: Throwable) {
            // A host bug must still produce a well-formed RPC error rather than a broken connection.
            error(GrpcWebEnvelope.GrpcStatus.INTERNAL, failure.message ?: "Lỗi máy chủ nội bộ")
        }
    }

    private suspend fun translate(service: TranslatorService, bodyText: String): Reply {
        val request = when (val decoded = decodePayload(bodyText)) {
            is Payload.Ok -> ProtoCodec.decodeTranslateRequest(decoded.bytes)
            is Payload.Rejected -> return decoded.reply
        }

        return when (val outcome = service.translate(request.text)) {
            is TranslateOutcome.Success ->
                ok(
                    ProtoCodec.encodeTranslateResponse(
                        TranslateResponse(
                            success = true,
                            translatedText = outcome.translatedText,
                            error = "",
                            elapsedMs = outcome.elapsedMs.coerceAtLeast(0),
                        )
                    )
                )

            is TranslateOutcome.TooLong -> error(
                GrpcWebEnvelope.GrpcStatus.INVALID_ARGUMENT,
                "Văn bản quá dài (${outcome.length} ký tự). Giới hạn tối đa là ${outcome.max} ký tự.",
            )

            is TranslateOutcome.ModelUnavailable -> error(
                GrpcWebEnvelope.GrpcStatus.UNAVAILABLE,
                outcome.detail.ifBlank {
                    "Mô hình chưa sẵn sàng. Hãy mở app VBookMT để kiểm tra."
                },
            )

            is TranslateOutcome.DeadlineExceeded -> error(
                GrpcWebEnvelope.GrpcStatus.DEADLINE_EXCEEDED,
                "Máy chủ vượt quá thời gian xử lý (${outcome.timeoutMs / 1000}s).",
            )

            is TranslateOutcome.Failure -> error(
                GrpcWebEnvelope.GrpcStatus.INTERNAL,
                outcome.message,
            )
        }
    }

    private suspend fun setConfig(service: TranslatorService, bodyText: String): Reply {
        val patch = when (val decoded = decodePayload(bodyText)) {
            is Payload.Ok -> ProtoCodec.decodeTranslateConfig(decoded.bytes)
            is Payload.Rejected -> return decoded.reply
        }
        return ok(ProtoCodec.encodeConfigResult(service.setConfig(patch)))
    }

    /** Base64 + frame + 256 KiB cap, in the one order that reports the right failure. */
    private sealed class Payload {
        class Ok(val bytes: ByteArray) : Payload()
        class Rejected(val reply: Reply) : Payload()
    }

    private fun decodePayload(bodyText: String): Payload {
        // Base64 is 4 characters per 3 bytes: 5 header bytes + up to MAX_MESSAGE_SIZE payload bytes.
        val maxFramedBytes = 5 + GrpcWebEnvelope.MAX_MESSAGE_SIZE
        val maxEncodedChars = (maxFramedBytes + 2) / 3 * 4
        if (bodyText.length > maxEncodedChars) {
            return Payload.Rejected(
                error(GrpcWebEnvelope.GrpcStatus.RESOURCE_EXHAUSTED, "Yêu cầu vượt quá giới hạn 256 KiB.")
            )
        }
        return try {
            Payload.Ok(GrpcWebEnvelope.decodeGrpcWebText(bodyText))
        } catch (tooLarge: GrpcWebEnvelope.MessageTooLargeException) {
            Payload.Rejected(
                error(GrpcWebEnvelope.GrpcStatus.RESOURCE_EXHAUSTED, tooLarge.message ?: "Yêu cầu vượt quá giới hạn 256 KiB.")
            )
        } catch (failure: IllegalArgumentException) {
            Payload.Rejected(
                error(GrpcWebEnvelope.GrpcStatus.INVALID_ARGUMENT, "Khung gRPC-Web không hợp lệ.")
            )
        }
    }

    /**
     * Clears the fields a host is not required to populate, so an Android-only counter cannot leak
     * into a Windows report as a wrong number (both hosts render the same card from this message).
     */
    private fun sanitize(report: HealthReport): HealthReport =
        report.copy(
            status = report.status.ifBlank { "healthy" },
            engine = report.engine.ifBlank { "ONNX" },
        )

    private fun ok(protoBytes: ByteArray): Reply =
        Reply(GrpcWebEnvelope.GrpcStatus.OK, GrpcWebEnvelope.encodeGrpcWebText(protoBytes))

    private fun error(status: GrpcWebEnvelope.GrpcStatus, message: String): Reply =
        Reply(status, payload = "", message = message)
}
