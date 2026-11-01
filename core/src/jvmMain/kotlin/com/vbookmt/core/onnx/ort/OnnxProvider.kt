package com.vbookmt.core.onnx.ort

import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession

/**
 * Which execution provider a session should try.
 *
 * Requesting a provider is not the same as getting one — an ONNX Runtime build only contains the
 * providers it was compiled with, and appending an absent one throws. [ProviderChoice] records the
 * outcome rather than assuming it.
 */
enum class OnnxProvider(val id: String, val label: String) {
    CPU("cpu", "CPU"),
    /**
     * Qualcomm HTP (the NPU). Needs the host QNN SDK to match the device's DSP firmware generation;
     * on a mismatch `QnnDevice_create` fails and this degrades to CPU. See [QNN_GPU].
     */
    QNN("qnn", "QNN HTP (NPU)"),
    /**
     * Qualcomm's Adreno GPU backend. Unlike HTP this runs entirely on the host against the driver, so
     * it does not depend on DSP firmware versions — which is what makes HTP unportable across OEM
     * firmware. Preferred accelerator on Qualcomm hardware for exactly that reason.
     */
    QNN_GPU("qnn_gpu", "QNN GPU (Adreno)"),
    /**
     * ONNX Runtime's WebGPU provider — the same backend `transformers.js` uses in a browser.
     *
     * Present in the Java API (`addWebGPU`) but the Android AAR does not ship the plugin library
     * (`onnxruntime_providers_webgpu.so`), and it is not published on Maven. Kept here so the sweep
     * can report that fact instead of assuming it.
     */
    WEBGPU("webgpu", "WebGPU"),
    /**
     * Split by graph shape: the encoder on the GPU, the decoders on CPU.
     *
     * These two graphs have opposite profiles. The encoder is a single wide forward pass over the
     * source sentence, which is what a GPU is for. The decoders emit one token per step for ~450
     * steps, each a tiny dispatch, which is the worst case for a GPU and also the phase where QNN's
     * partial-offload round-trips hurt most. Assigning a provider per session is free here because
     * the app already keeps three separate ORT sessions.
     */
    HYBRID("hybrid", "Hybrid (encoder GPU, decoder CPU)"),
    NNAPI("nnapi", "NNAPI"),
    XNNPACK("xnnpack", "XNNPACK"),
    DIRECTML("directml", "DirectML (GPU)"),
    ;

    companion object {
        fun parse(value: String): OnnxProvider =
            entries.firstOrNull { it.id.equals(value.trim(), ignoreCase = true) } ?: CPU

        /**
         * Best available default per platform.
         *
         * Android prefers QNN, which is where the GPU/NPU win actually lives — `onnxruntime-android-qnn`
         * ships a build with `QnnHtp`/`QnnCpu` compiled in, verified against the AAR's symbols.
         *
         * Desktop requests CPU on purpose. The stock `onnxruntime` JAR has no GPU provider, and the
         * `onnxruntime4j_jni` shim is CPU-only: its own binary contains the string "This binary was
         * not compiled with DirectML support", so swapping the core DLL does not help. Reaching
         * DirectML or WinML needs a C++/.NET bridge that does not exist here, so desktop stays on
         * CPU and says so instead of pretending.
         */
        /**
         * Best available default per platform.
         *
         * **Android defaults to CPU, and that is a measured decision, not a fallback.** With cache
         * disabled, unique text per run, and mean-of-runs timing on a Snapdragon 8 Gen 3 (SM8650),
         * ONNX CPU measured 755–1992 chars/s against QNN GPU 358–1566 for the same inputs — CPU won at
         * every size. Both beat CTranslate2 (168–820).
         *
         * The reason GPU does not help here is structural: QNN's GPU backend claims only part of these
         * graphs (every op it rejects round-trips through CPU), and autoregressive decode is a long
         * sequence of tiny dispatches — the worst possible shape for a GPU. The same pattern made
         * DirectML lose on desktop.
         *
         * QNN HTP (the NPU) is unavailable outright — the bundled QNN SDK generation does not match the
         * device firmware — so it is not a candidate either.
         *
         * Note throughput scales strongly with input length (37 chars/s at 62 characters versus 444 at
         * 1333 on the same engine), so any figure quoted without its input size is meaningless.
         *
         * GPU offload remains selectable and worth retesting for a much larger model or batch; see
         * [QNN_GPU]. Overriding is one file, no rebuild:
         * `adb shell "run-as <pkg> sh -c 'echo qnn_gpu > files/onnx-provider'"`.
         */
        fun defaultForPlatform(): OnnxProvider = CPU

        private fun isAndroid(): Boolean =
            System.getProperty("java.vm.vendor").orEmpty().contains("Android", ignoreCase = true) ||
                System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)
    }
}

/** The provider a session ended up on, and why, if it is not what was asked for. */
data class ProviderChoice(
    val requested: OnnxProvider,
    val active: OnnxProvider,
    /** Null when [active] == [requested]. */
    val fallbackReason: String? = null,
) {
    val fellBack: Boolean get() = fallbackReason != null

    override fun toString(): String =
        if (fellBack) {
            "${active.label} (requested ${requested.label}: $fallbackReason)"
        } else {
            active.label
        }
}

/**
 * Appends [provider] to [options], reporting what was achieved.
 *
 * The fallback is a `catch`, not a pre-flight check: ONNX Runtime offers no way to ask what it was
 * compiled with, so the exception from appending is the only signal.
 */
fun OrtSession.SessionOptions.applyProvider(provider: OnnxProvider): ProviderChoice {
    if (provider == OnnxProvider.CPU) {
        return ProviderChoice(OnnxProvider.CPU, OnnxProvider.CPU)
    }
    return try {
        when (provider) {
            // **QNN is best-effort, not a support contract.** What was actually measured on a
            // Snapdragon 8 Gen 3 (SM8650, HTP v75): ONNX Runtime's QNN EP fails at session creation
            // with `QNN_DEVICE_ERROR_INVALID_CONFIG`, because the `qnn-runtime-2.33.0` libraries
            // bundled by `onnxruntime-android-qnn:1.22.0` are a different QNN SDK generation from the
            // DSP firmware the phone ships (`/odm/lib64` reports 2.13.1). Host and device in one QNN
            // session must be the same SDK, and no provider option bridges that gap. Tuning knobs like
            // `vtcm_mb` were tried and did not change it — they are left out rather than implying they
            // fix anything.
            //
            // The consequence for the product is that HTP cannot be the plan: it depends on a
            // bootloader and DSP firmware the app does not control. `backend_type=htp` is still
            // requested because it costs nothing and works on devices whose firmware happens to
            // match, but [probeProvider] verifies the claim and reports CPU when it is not real.
            OnnxProvider.QNN -> addQnn(mapOf("backend_type" to "htp"))

            // Adreno GPU. Runs on the host against the graphics driver, so unlike HTP it is not tied
            // to the device's DSP firmware generation — the mismatch that makes HTP fail outright on
            // many OEM builds. This is the portable Qualcomm accelerator.
            OnnxProvider.QNN_GPU -> addQnn(mapOf("backend_type" to "gpu"))
            OnnxProvider.WEBGPU -> addWebGPU(emptyMap())
            // HYBRID resolves per graph in `OrtOnnxModel.load`; as a whole-session request it means
            // "GPU", and the decoder sessions are re-pointed at CPU there.
            OnnxProvider.HYBRID -> addQnn(mapOf("backend_type" to "gpu"))
            OnnxProvider.NNAPI -> addNnapi()
            OnnxProvider.XNNPACK -> addXnnpack(emptyMap())
            OnnxProvider.DIRECTML -> addDirectML(0)
            OnnxProvider.CPU -> {}
        }
        ProviderChoice(provider, provider)
    } catch (e: Throwable) {
        // OrtException when the build lacks the provider; UnsatisfiedLinkError when a plugin
        // library is missing. Both mean "use CPU", and both messages are kept because they are what
        // tells a user whether their machine or the build is the problem.
        ProviderChoice(
            provider,
            OnnxProvider.CPU,
            (e as? OrtException)?.message ?: e.message ?: e.toString(),
        )
    }
}
