package com.vbookmt.core.server

import com.vbookmt.wire.v1.GrpcWebDispatcher
import com.vbookmt.wire.v1.TranslatorService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Shared embedded Ktor server providing loopback gRPC-Web endpoints on both Android and Windows.
 */
object KtorTranslatorServer {

    const val GRPC_PATH = "/vbookmt.v1.Translator"

    fun create(
        port: Int,
        host: String,
        serviceTitle: String,
        service: TranslatorService
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return embeddedServer(CIO, port = port, host = host) {
            configure(service, serviceTitle)
        }
    }

    fun Application.configure(service: TranslatorService, serviceTitle: String) {
        install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Options)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept)
            allowHeader(HttpHeaders.Authorization)
            allowHeader("x-grpc-web")
            allowHeader("x-user-agent")
            exposeHeader("grpc-status")
            exposeHeader("grpc-message")
            exposeHeader("content-type")
            exposeHeader("Access-Control-Allow-Origin")
        }

        routing {
            get("/") {
                call.respondText(
                    """{"service":"vbookmt","title":"$serviceTitle","grpc":"$GRPC_PATH"}""" + "\n",
                    ContentType.Application.Json
                )
            }

            options("$GRPC_PATH/{method}") {
                call.response.header("Access-Control-Allow-Origin", "*")
                call.response.header("Access-Control-Allow-Methods", "POST, OPTIONS")
                call.response.header("Access-Control-Allow-Headers", "content-type, x-grpc-web, x-user-agent")
                call.response.header("Access-Control-Expose-Headers", "grpc-status, grpc-message, content-type")
                call.response.header("Access-Control-Max-Age", "86400")
                call.respondText("", ContentType.Text.Plain, HttpStatusCode.OK)
            }

            post("$GRPC_PATH/{method}") {
                val method = call.parameters["method"].orEmpty()
                call.response.header("Access-Control-Allow-Origin", "*")
                call.response.header("Access-Control-Expose-Headers", "grpc-status, grpc-message, content-type")
                val body = call.receiveText()
                val reply = GrpcWebDispatcher.dispatch(service, method, body)
                if (reply == null) {
                    call.respondText("Unknown gRPC-Web method: $method", status = HttpStatusCode.NotFound)
                    return@post
                }

                call.response.header("grpc-status", reply.statusHeader)
                if (!reply.isSuccess) {
                    val encodedMessage = runCatching {
                        URLEncoder.encode(reply.message, StandardCharsets.UTF_8.name()).replace("+", "%20")
                    }.getOrDefault("")
                    call.response.header("grpc-message", encodedMessage)
                }

                call.respondText(
                    text = reply.payload,
                    contentType = ContentType.parse(GrpcWebDispatcher.CONTENT_TYPE),
                    status = HttpStatusCode.OK
                )
            }
        }
    }
}
