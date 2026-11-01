package vn.dungxnd.vbookmt.service

import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator

/**
 * Whether the active model's engine is up, and what to call it in `/health` and the UI.
 *
 * This used to branch on the model spec's engine, because CTranslate2 and ONNX were both selectable.
 * CTranslate2 is gone and every shipped model is ONNX, so the branch collapsed: what remains is the
 * one place that answers "is the engine ready" without every caller reaching into [OnnxTranslator].
 */
object Engines {

    /** Human-readable engine name for `/health` and diagnostics. */
    fun activeEngineLabel(): String = "ONNX"

    /** True when the engine has a live model. */
    fun isActiveReady(): Boolean = OnnxTranslator.isReady()

    /** Sequences per decode call. */
    fun activeBatchSize(): Int = OnnxTranslator.maxBatchSize

    /** Detail line for the settings screen, e.g. the execution provider actually in use. */
    fun activeDetail(): String = "ONNX · ${OnnxTranslator.providerLabel}"

    /**
     * Loads the engine from [modelDir].
     *
     * The UI used to call the CTranslate2 loader directly, which pointed an ONNX model at the wrong
     * engine: it failed, and the screen reported "not ready" for a model that had downloaded fine.
     */
    fun ensureActiveLoaded(modelDir: java.io.File): Boolean = OnnxTranslator.init(modelDir)
}
