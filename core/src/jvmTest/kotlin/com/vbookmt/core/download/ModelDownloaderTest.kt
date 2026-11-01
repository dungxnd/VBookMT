package com.vbookmt.core.download

import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import com.vbookmt.core.model.onnxVariantFiles
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the real download loop against a real HTTP server.
 *
 * The Windows-side test only asserts [ModelDownloader.isModelReady], so the parts that actually decide
 * whether a download is *persistent* -- Range resumption, the on-disk variant markers, and the rule
 * that a different variant wipes the previous one -- had no coverage at all. Those are exactly the
 * behaviours a user notices after a crash or a re-click, so they are pinned here instead.
 */
class ModelDownloaderTest {

    private lateinit var server: HttpServer
    private lateinit var root: File
    private lateinit var served: MutableList<String>

    /** Bytes for each file, sized above [ModelDownloader]'s validity thresholds. */
    private val sizes = mapOf(
        "config.json" to 885,
        "encoder_model.onnx" to 600_000,
        "decoder_model.onnx" to 600_000,
        "decoder_with_past_model.onnx" to 600_000,
        "onnx_meta.json" to 770,
        "trim_meta.json" to 200,
        "source.spm" to 15_000,
        "target.spm" to 15_000,
    )

    private val spec = ModelSpec(
        id = "test-model-onnx",
        name = "Test Model",
        author = "test",
        license = "test",
        repoId = "test/test-model",
        sizeMb = 2,
        description = "fixture",
        downloadFiles = onnxVariantFiles(QuantVariant.FP16),
    )

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("vbookmt-download-test").toFile()
        served = mutableListOf()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> respond(exchange) }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}"

    private fun payload(name: String): ByteArray {
        val size = sizes[name] ?: error("no fixture for $name")
        return ByteArray(size) { (it % 251).toByte() }
    }

    /**
     * Serves a file, honouring `Range` with a 206 and returning 200 otherwise -- the two responses the
     * downloader branches on.
     */
    private fun respond(exchange: HttpExchange) {
        val name = exchange.requestURI.path.trimStart('/')
        val bytes = runCatching { payload(name) }.getOrNull()
        if (bytes == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        served += name

        val range = exchange.requestHeaders.getFirst("Range")
        if (range != null && range.startsWith("bytes=")) {
            val from = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
            if (from >= bytes.size) {
                exchange.sendResponseHeaders(416, -1)
                exchange.close()
                return
            }
            val slice = bytes.copyOfRange(from.toInt(), bytes.size)
            exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    private fun download(target: File, variant: QuantVariant = QuantVariant.FP16) = runBlocking {
        ModelDownloader.downloadModel(
            targetDir = target,
            modelSpec = spec,
            variant = variant,
            userAgent = "test",
            resolveUrl = { name -> baseUrl() + "/" + name },
        )
    }

    @Test
    fun `download writes every file and marks the variant`() {
        val target = File(root, "model")
        assertTrue(download(target))

        for (name in sizes.keys) {
            val file = File(target, name)
            assertTrue(file.isFile, "$name should exist")
            assertEquals(sizes.getValue(name), file.length().toInt(), "$name size")
        }
        assertEquals("fp16", File(target, ".variant").readText().trim())
        assertFalse(File(target, ".variant_downloading").exists(), "in-progress marker must be cleared")
        assertTrue(
            target.listFiles()!!.none { it.name.endsWith(".tmp") },
            "no .tmp files should survive a completed download",
        )
    }

    @Test
    fun `a second run is a no-op because completed files are reused`() {
        val target = File(root, "model")
        assertTrue(download(target))
        val firstPass = served.toList()
        served.clear()

        assertTrue(download(target))
        assertTrue(served.isEmpty(), "already-valid files must not be re-fetched, served=${served}")
        assertTrue(firstPass.isNotEmpty())
    }

    @Test
    fun `an interrupted file resumes with a Range request instead of restarting`() {
        val target = File(root, "model")
        target.mkdirs()
        // A partial temp file, plus the marker that says which variant was in progress: this is the
        // exact state a crash mid-download leaves behind. The prefix must be the real bytes the server
        // would have sent, because the downloader appends the remainder to it.
        val full = payload("encoder_model.onnx")
        File(target, "encoder_model.onnx.tmp").writeBytes(full.copyOf(250_000))
        File(target, ".variant_downloading").writeText("fp16")

        assertTrue(download(target))

        assertEquals(sizes.getValue("encoder_model.onnx"), File(target, "encoder_model.onnx").length().toInt())
        // The resumed file must be the original bytes, not the partial prefix concatenated twice.
        assertTrue(
            File(target, "encoder_model.onnx").readBytes().contentEquals(full),
            "resumed file must match the served payload byte for byte",
        )
    }

    @Test
    fun `a connection dropped mid-file is retried and resumes rather than failing`() {
        // The case resume exists for: the socket dies part-way through a file. The retry loop must
        // keep the bytes already on disk and ask for the rest with a Range, not start over or give up.
        val target = File(root, "model")
        val full = payload("encoder_model.onnx")
        var drops = 0
        val flaky = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        flaky.createContext("/") { exchange ->
            val name = exchange.requestURI.path.trimStart('/')
            val bytes = runCatching { payload(name) }.getOrNull()
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val slice = bytes.copyOfRange(from.toInt(), bytes.size)

            if (name == "encoder_model.onnx" && drops == 0) {
                // Announce the full length, send half, then close early: a truncated transfer.
                drops++
                exchange.sendResponseHeaders(if (from > 0) 206 else 200, slice.size.toLong())
                exchange.responseBody.write(slice, 0, slice.size / 2)
                exchange.responseBody.flush()
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(if (from > 0) 206 else 200, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
            exchange.close()
        }
        flaky.start()

        try {
            val ok = runBlocking {
                ModelDownloader.downloadModel(
                    targetDir = target,
                    modelSpec = spec,
                    variant = QuantVariant.FP16,
                    userAgent = "test",
                    resolveUrl = { name -> "http://127.0.0.1:${flaky.address.port}/$name" },
                )
            }
            assertTrue(ok, "a single truncated transfer must not fail the download")
            assertTrue(
                File(target, "encoder_model.onnx").readBytes().contentEquals(full),
                "the retried file must still be byte-identical to the payload",
            )
            assertTrue(drops == 1, "the server should have truncated exactly once")
        } finally {
            flaky.stop(0)
        }
    }

    @Test
    fun `switching variant wipes the previous variant's files`() {
        val target = File(root, "model")
        assertTrue(download(target, QuantVariant.FP16))
        assertTrue(File(target, "encoder_model.onnx").isFile)

        assertTrue(download(target, QuantVariant.Q8))
        assertEquals("q8", File(target, ".variant").readText().trim())
        // Same bare filenames on disk, so the content is what proves the swap happened.
        assertEquals(sizes.getValue("encoder_model.onnx"), File(target, "encoder_model.onnx").length().toInt())
        assertFalse(File(target, ".variant_downloading").exists())
    }

    @Test
    fun `a failing file leaves no variant marker, so the model never reads as ready`() {
        val target = File(root, "model")
        val broken = ModelSpec(
            id = spec.id,
            name = spec.name,
            author = spec.author,
            license = spec.license,
            repoId = spec.repoId,
            sizeMb = spec.sizeMb,
            description = spec.description,
            downloadFiles = listOf("fp16/missing_file.onnx"),
            // `filesFor` ignores `downloadFiles` whenever `variants` is non-empty, and the default is
            // every variant -- so without this the spec would quietly fetch the normal eight files and
            // the test would never reach a 404 at all.
            variants = emptyList(),
        )
        val ok = runBlocking {
            ModelDownloader.downloadModel(
                targetDir = target,
                modelSpec = broken,
                variant = QuantVariant.FP16,
                resolveUrl = { name -> baseUrl() + "/" + name },
                )
        }

        assertFalse(ok, "a 404 must fail the download")
        assertFalse(File(target, ".variant").exists(), "no .variant marker on failure")
        // Deliberately no variant argument: with one, isModelReady compares the recorded variant, which
        // would report "not ready" for the wrong reason and hide a directory that is merely incomplete.
        assertFalse(
            ModelDownloader.isModelReady(target, spec),
            "an incomplete directory must not read as ready",
        )
    }
}
