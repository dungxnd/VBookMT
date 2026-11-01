package vn.dungxnd.vbookmt.service

import com.vbookmt.core.server.KtorTranslatorServer
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android's loopback surface: gRPC-Web only, delegating to [KtorTranslatorServer] in `core`.
 */
internal class TranslationHttpServer(
    private val port: Int,
    private val inFlight: AtomicInteger,
    private val ensureModelReady: suspend () -> Boolean,
) {

    fun create(): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        val service = AndroidTranslatorService(
            port = port,
            inFlight = inFlight,
            ensureModelReady = ensureModelReady,
        )
        return KtorTranslatorServer.create(
            port = port,
            host = ServiceContract.HOST,
            serviceTitle = "VBookMT Android 1.2.1",
            service = service
        )
    }
}
