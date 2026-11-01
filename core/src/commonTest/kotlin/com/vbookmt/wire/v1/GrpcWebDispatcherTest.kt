package com.vbookmt.wire.v1

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every status the contract can produce is decided here rather than in a host, so these tests are the
 * only place the two hosts can be proven to agree. They run without Ktor, a socket or a model.
 */
class GrpcWebDispatcherTest {

    /** Records what the dispatcher asked for, and answers with whatever the test wired in. */
    private class FakeService(
        private val onTranslate: suspend (String) -> TranslateOutcome = {
            TranslateOutcome.Success("Xin chào", 12)
        },
        private val onSetConfig: (TranslateConfig) -> ConfigResult = { ConfigResult("{}") },
    ) : TranslatorService {
        var lastPatch: TranslateConfig? = null
        var translatedFor: String? = null

        override suspend fun translate(text: String): TranslateOutcome {
            translatedFor = text
            return onTranslate(text)
        }

        override suspend fun health(): HealthReport =
            HealthReport(host = "test", port = 8765, ready = true, model = "hachimi-60-qt")

        override suspend fun getConfig(): TranslateConfig = TranslateConfig(threads = 4, computeType = "int8")

        override suspend fun setConfig(patch: TranslateConfig): ConfigResult {
            lastPatch = patch
            return onSetConfig(patch)
        }
    }

    private fun encodeRequest(text: String): String =
        GrpcWebEnvelope.encodeGrpcWebText(ProtoCodec.encodeTranslateRequest(TranslateRequest(text = text)))

    private fun decodeReply(reply: GrpcWebDispatcher.Reply): TranslateResponse =
        ProtoCodec.decodeTranslateResponse(GrpcWebEnvelope.decodeGrpcWebText(reply.payload))

    @Test
    fun unknownMethodIsNotAnRpcErrorItIsAMissingRoute() = runTest {
        assertNull(GrpcWebDispatcher.dispatch(FakeService(), "Nope", ""))
        // Every contract method, by contrast, resolves.
        for (method in GrpcWebDispatcher.METHODS) {
            assertNotNull(GrpcWebDispatcher.dispatch(FakeService(), method, encodeRequest("你好")), method)
        }
    }

    @Test
    fun translateSuccessCarriesTheTextAndStaysOk() = runTest {
        val reply = GrpcWebDispatcher.dispatch(FakeService(), "Translate", encodeRequest("你好"))!!
        assertEquals(GrpcWebEnvelope.GrpcStatus.OK, reply.status)
        assertEquals("0", reply.statusHeader)
        val body = decodeReply(reply)
        assertTrue(body.success)
        assertEquals("Xin chào", body.translatedText)
        assertEquals(12, body.elapsedMs)
    }

    @Test
    fun sourceReachesTheServiceAlreadyDecoded() = runTest {
        val service = FakeService()
        GrpcWebDispatcher.dispatch(service, "Translate", encodeRequest("黑夜给了我黑色的眼睛"))
        assertEquals("黑夜给了我黑色的眼睛", service.translatedFor)
    }

    @Test
    fun everyFailureOutcomeMapsToItsContractedStatus() = runTest {
        // The table in docs/GRPC_WEB_MIGRATION.md §2, asserted so a host cannot quietly pick another.
        val cases = listOf(
            TranslateOutcome.TooLong(60_000, 50_000) to GrpcWebEnvelope.GrpcStatus.INVALID_ARGUMENT,
            TranslateOutcome.ModelUnavailable("chưa nạp") to GrpcWebEnvelope.GrpcStatus.UNAVAILABLE,
            TranslateOutcome.DeadlineExceeded(110_000) to GrpcWebEnvelope.GrpcStatus.DEADLINE_EXCEEDED,
            TranslateOutcome.Failure("bùm") to GrpcWebEnvelope.GrpcStatus.INTERNAL,
        )
        for ((outcome, expected) in cases) {
            val reply = GrpcWebDispatcher.dispatch(FakeService({ outcome }), "Translate", encodeRequest("你好"))!!
            assertEquals(expected, reply.status, outcome::class.simpleName.toString())
            assertEquals(expected.code.toString(), reply.statusHeader)
            // Failure replies carry no body: the contract puts everything in the headers.
            assertEquals("", reply.payload)
            assertTrue(reply.message.isNotBlank(), "a failure must carry a Vietnamese message")
        }
    }

    @Test
    fun aHostBugBecomesInternalRatherThanABrokenConnection() = runTest {
        val crashing = FakeService({ error("kaboom") })
        val reply = GrpcWebDispatcher.dispatch(crashing, "Translate", encodeRequest("你好"))!!
        assertEquals(GrpcWebEnvelope.GrpcStatus.INTERNAL, reply.status)
        assertEquals("kaboom", reply.message)
    }

    @Test
    fun malformedFramingIsInvalidArgumentNotInternal() = runTest {
        val cases = listOf(
            "" to "empty body",
            "not-base64-!!" to "garbage",
            GrpcWebEnvelope.encodeBase64(ByteArray(3)) to "shorter than the 5-byte frame header",
        )
        for ((body, label) in cases) {
            val reply = GrpcWebDispatcher.dispatch(FakeService(), "Translate", body)!!
            assertEquals(GrpcWebEnvelope.GrpcStatus.INVALID_ARGUMENT, reply.status, label)
        }
    }

    @Test
    fun anOversizedBodyIsRejectedBeforeItIsDecoded() = runTest {
        // One byte over the cap, already framed and encoded: the check must fire on the encoded length.
        val oversize = GrpcWebEnvelope.encodeGrpcWebText(ByteArray(GrpcWebEnvelope.MAX_MESSAGE_SIZE + 1))
        val reply = GrpcWebDispatcher.dispatch(FakeService(), "Translate", oversize)!!
        assertEquals(GrpcWebEnvelope.GrpcStatus.RESOURCE_EXHAUSTED, reply.status)
    }

    @Test
    fun healthAndConfigRoundTripThroughTheSameCodecThePluginUses() = runTest {
        val health = GrpcWebDispatcher.dispatch(FakeService(), "Health", "")!!
        val report = ProtoCodec.decodeHealthReport(GrpcWebEnvelope.decodeGrpcWebText(health.payload))
        assertEquals("test", report.host)
        assertTrue(report.ready)
        assertEquals(8765, report.port)

        val config = GrpcWebDispatcher.dispatch(FakeService(), "GetConfig", "")!!
        val knobs = ProtoCodec.decodeTranslateConfig(GrpcWebEnvelope.decodeGrpcWebText(config.payload))
        assertEquals(4, knobs.threads)
        assertEquals("int8", knobs.computeType)
    }

    @Test
    fun setConfigPreservesAbsentFieldsAsLeaveUnchanged() = runTest {
        // proto3 explicit presence is the whole reason this message uses `optional`: a null must stay
        // null through the wire, because 0 means "set it to zero" nowhere and "leave it" everywhere.
        val service = FakeService()
        val patchBytes = ProtoCodec.encodeTranslateConfig(TranslateConfig(threads = 6))
        GrpcWebDispatcher.dispatch(
            service,
            "SetConfig",
            GrpcWebEnvelope.encodeGrpcWebText(patchBytes),
        )
        val patch = service.lastPatch!!
        assertEquals(6, patch.threads)
        assertNull(patch.maxBatch)
        assertNull(patch.computeType)
        assertNull(patch.clearCache)
    }

    @Test
    fun energySurfacesAreReachableOnEveryHost() = runTest {
        for (method in listOf("GetEnergy", "ResetEnergy")) {
            val reply = GrpcWebDispatcher.dispatch(FakeService(), method, "")!!
            assertEquals(GrpcWebEnvelope.GrpcStatus.OK, reply.status, method)
        }
    }
}
