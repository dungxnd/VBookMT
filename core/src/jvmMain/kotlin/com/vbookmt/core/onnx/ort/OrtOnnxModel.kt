package com.vbookmt.core.onnx.ort

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vbookmt.core.onnx.Graphs
import com.vbookmt.core.onnx.OnnxModel
import com.vbookmt.core.onnx.OnnxModelFiles
import com.vbookmt.core.onnx.OnnxTokenizer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens a trimmed Marian export into something `OnnxDecodeEngine` can drive.
 *
 * Three sessions inside one environment:
 *  - `encoder`             source ids -> hidden states
 *  - `decoder`             first step, no cache in
 *  - `decoder_with_past`   every later step, cache in and out
 *
 * Tensor names come from `onnx_meta.json` rather than being hard-coded: Marian's cache is four
 * tensors per layer (`self_k`, `self_v`, `cross_k`, `cross_v`), not the usual key/value pair, and a
 * client that assumes the common naming feeds the wrong tensor.
 *
 * Lives in `jvmMain` so both hosts share it. The caller supplies the [OnnxTokenizer] because Android
 * and Windows reach SentencePiece through `native/`, which is app-side code.
 */
class OrtOnnxModel private constructor(
    private val backend: OrtOnnxBackend,
    val model: OnnxModel,
    /** The provider the sessions actually run on, and why, if it is not what was requested. */
    val provider: ProviderChoice,
) : AutoCloseable {

    /** Convenience: the decode loop for this model. */
    @Suppress("unused")
    val engine: com.vbookmt.core.onnx.OnnxDecodeEngine
        get() = com.vbookmt.core.onnx.OnnxDecodeEngine(model)

    override fun close() {
        runCatching { backend.close() }
    }

    companion object {
        /**
         * Proves whether the requested provider actually initialised.
         *
         * Creates one throwaway session on the smallest graph with `session.disable_cpu_ep_fallback`
         * set. That flag makes the *absence* of the provider an exception instead of a silent CPU
         * fallback, which is the behaviour this is guarding against.
         *
         * A thrown probe does **not** mean the accelerator is worthless — an EP that claims part of a
         * graph and leaves the rest on CPU is normal and often a real win. So the probe reports which
         * of three things happened:
         *
         *  * session created with no fallback -> the whole graph went to the accelerator;
         *  * session created on CPU -> the provider did not claim anything, and the log says why;
         *  * probe threw -> the provider could not come up at all, and the reason is carried in
         *    [ProviderChoice.fallbackReason].
         */
        private fun probeProvider(
            env: OrtEnvironment,
            modelDir: Path,
            base: OrtSession.SessionOptions,
            requested: ProviderChoice,
        ): ProviderChoice {
            return try {
                OrtSession.SessionOptions().use { probe ->
                    probe.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    probe.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    probe.applyProvider(requested.requested)
                    env.createSession(modelDir.resolve("encoder_model.onnx").toString(), probe).close()
                }
                requested
            } catch (e: Throwable) {
                val message = (e.message ?: e.toString()).lineSequence().first()
                // "assigned to the default CPU EP" is partitioning, not a failed backend: QNN came up
                // and claimed a subgraph. Treat it as up-but-partial rather than as a fallback, because
                // the alternative is to under-report a working GPU to the user.
                if (message.contains("default CPU EP", ignoreCase = true)) {
                    ProviderChoice(
                        requested = requested.requested,
                        active = requested.requested,
                        fallbackReason = "partial offload: QNN claimed a subgraph, remaining nodes run on CPU",
                    )
                } else {
                    ProviderChoice(
                        requested = requested.requested,
                        active = OnnxProvider.CPU,
                        fallbackReason = "provider did not initialise: ${message.take(240)}",
                    )
                }
            }
        }

        /** Files a loadable directory must contain; mirrors `ONNX_CORE_FILES` in the registry. */
        val REQUIRED: List<String> = listOf(
            "encoder_model.onnx",
            "decoder_model.onnx",
            "decoder_with_past_model.onnx",
            "onnx_meta.json",
            "trim_meta.json",
        )

        fun isLoadable(modelDir: Path): Boolean =
            Files.isDirectory(modelDir) && REQUIRED.all { Files.isRegularFile(modelDir.resolve(it)) }

        /**
         * Opens the model, or fails naming the missing files rather than with a generic ONNX Runtime
         * error — a half-downloaded model is the common case and the user needs to know what to
         * re-fetch.
         *
         * @param provider requested execution provider. One the binary lacks falls back to CPU, and
         *   the reason is reported in [provider] rather than thrown: a machine that cannot
         *   accelerate should still translate.
         */
        fun load(
            modelDir: Path,
            tokenizer: OnnxTokenizer,
            threads: Int = 0,
            provider: OnnxProvider = OnnxProvider.defaultForPlatform(),
        ): OrtOnnxModel {
            val missing = REQUIRED.filterNot { Files.isRegularFile(modelDir.resolve(it)) }
            require(missing.isEmpty()) { "ONNX model incomplete in $modelDir, missing: $missing" }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (threads > 0) setIntraOpNumThreads(threads)
            }
            val choice = runCatching { opts.applyProvider(provider) }
                .getOrElse { ProviderChoice(provider, OnnxProvider.CPU, it.message ?: "provider failed") }

            // **Verify, do not assume.** `applyProvider` only catches the exception thrown when the
            // provider is *appended*. QNN can fail later, inside the first `GetCapability`, and ORT
            // then keeps the session on CPU while still reporting the provider as requested — measured
            // on a Snapdragon 8 Gen 3, where every session said "QNN" while logcat showed
            // `QNN_DEVICE_ERROR_INVALID_CONFIG` and execution stayed on CPU. Benchmarking that as "the
            // NPU" is exactly the confound this project has already been burned by twice.
            //
            // ONNX Runtime's Java API 1.22 has no per-session provider query, so this probes with
            // `session.disable_cpu_ep_fallback` and reports whether the provider *initialised*. Note
            // that a probe failure does NOT mean the accelerator is useless: an EP that claims part of
            // a graph and leaves the rest on CPU is normal and often a win. The distinction the probe
            // draws is "QNN came up at all" versus "QNN never came up", which is what the earlier
            // silent-CPU numbers got wrong.
            val effective = if (choice.active == OnnxProvider.CPU) {
                choice
            } else {
                probeProvider(env, modelDir, opts, choice)
            }

            // HYBRID assigns providers per graph. The encoder gets the accelerator; the two decoder
            // graphs get CPU, because they run one token per step and QNN's partial-offload
            // round-trips dominate there. Built as a plain function so a failed GPU encoder throws
            // here (visible) instead of degrading silently mid-decode.
            fun sessionsWith(encoderOpts: OrtSession.SessionOptions, decoderOpts: OrtSession.SessionOptions) = mapOf(
                Graphs.ENCODER to env.createSession(modelDir.resolve("encoder_model.onnx").toString(), encoderOpts),
                Graphs.DECODER to env.createSession(modelDir.resolve("decoder_model.onnx").toString(), decoderOpts),
                Graphs.DECODER_WITH_PAST to
                    env.createSession(modelDir.resolve("decoder_with_past_model.onnx").toString(), decoderOpts),
            )

            val sessions = if (choice.requested == OnnxProvider.HYBRID) {
                OrtSession.SessionOptions().use { decOpts ->
                    decOpts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    if (threads > 0) decOpts.setIntraOpNumThreads(threads)
                    sessionsWith(opts, decOpts)
                }
            } else {
                // One options object reused for all three, which is what ONNX Runtime expects when the
                // provider set is identical.
                sessionsWith(opts, opts)
            }
            val backend = OrtOnnxBackend(env, sessions)

            // `Files.readString` is API 33+ (and absent from Android's desugared library), so on a
            // minSdk 26 build it fails at *link* time with "No static method readString(...)" and the
            // model never loads — the session is created and then the whole init aborts. `readAllBytes`
            // is available on every supported API level and does the same job.
            val metaText = String(Files.readAllBytes(modelDir.resolve("onnx_meta.json")), Charsets.UTF_8)
            val trimText = String(Files.readAllBytes(modelDir.resolve("trim_meta.json")), Charsets.UTF_8)
            val model = OnnxModelFiles.buildModel(metaText, trimText, backend, tokenizer)

            return OrtOnnxModel(backend, model, effective)
        }
    }
}
