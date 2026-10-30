package vn.dungxnd.vbookmt.service

import com.vbookmt.core.model.TranslateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.util.PerformanceTuner

/**
 * Applies a `POST /config` request: batch/thread/precision knobs plus cache housekeeping.
 *
 * Kept out of the HTTP layer so the routes stay a thin mapping from JSON to behaviour, and out of
 * the service so they do not need a `Service` instance to run. Thread count and compute type both
 * require a native model reload, so they are applied in one reload instead of two.
 *
 * These knobs exist so settings can be measured on a real phone instead of guessed on x86; the UI
 * normally hides them behind one button (see [PerformanceTuner]).
 */
object TranslationSettings {

    /** @return the effective configuration as JSON, for the caller to hand straight back to the client. */
    suspend fun apply(request: TranslateConfig): String = withContext(Dispatchers.IO) {
        if (request.maxBatch > 0) Ct2Translator.setBatchSize(request.maxBatch)
        if (request.chunkChars > 0) ChapterTranslator.setChunkChars(request.chunkChars)

        when (request.batchType.lowercase()) {
            "tokens" -> Ct2Translator.setTokenBatching(true)
            "examples" -> Ct2Translator.setTokenBatching(false)
        }

        if (request.clearCache) {
            ChunkTranslator.clearCache()
            ServiceState.log("CẤU HÌNH: đã xoá cache bản dịch")
        }
        ChunkTranslator.cacheEnabled = request.cacheEnabled

        val requestedCompute = request.computeType.trim().lowercase()
        val threadsChanged = request.threads > 0 && request.threads != Ct2Translator.activeThreads
        val computeChanged = requestedCompute.isNotEmpty() &&
            requestedCompute != Ct2Translator.activeComputeType

        if (threadsChanged || computeChanged) {
            val path = Ct2Translator.getActiveModelPath()
            if (path == null) {
                ServiceState.log("CẤU HÌNH: không đổi được threads/compute_type, model chưa nạp")
            } else {
                val threads = if (request.threads > 0) request.threads else Ct2Translator.activeThreads
                val compute = if (requestedCompute.isNotEmpty()) {
                    requestedCompute
                } else {
                    Ct2Translator.activeComputeType
                }
                ServiceState.log(
                    "CẤU HÌNH: nạp lại model (threads=$threads, " +
                        "compute_type=${compute.ifEmpty { "default" }})"
                )
                Ct2Translator.init(path, threads, compute)
                // A different compute type rounds weights differently, so old entries are no longer
                // exactly what this configuration would emit.
                if (computeChanged) ChunkTranslator.clearCache()
            }
        }

        // Persist manual tuning too, so a /config experiment survives an app restart and the
        // performance card keeps describing the truth.
        if (threadsChanged || computeChanged || request.maxBatch > 0) {
            PerformanceTuner.save(
                ServiceContextHolder.require(),
                PerformanceTuner.Tuning(
                    threads = Ct2Translator.activeThreads,
                    computeType = Ct2Translator.activeComputeType,
                    maxBatch = Ct2Translator.maxBatchSize,
                )
            )
        }

        val batchType = if (Ct2Translator.useTokenBatching) "tokens" else "examples"
        val cache = ChunkTranslator.cacheStats()
        ServiceState.log(
            "CẤU HÌNH: threads=${Ct2Translator.activeThreads} " +
                "max_batch=${Ct2Translator.maxBatchSize} chunk_chars=${ChapterTranslator.chunkChars} " +
                "batch_type=$batchType " +
                "compute_type=${Ct2Translator.activeComputeType.ifEmpty { "default" }} " +
                "cache=${cache.entries} entries (hit ${cache.hitRatePercent}%)"
        )
        buildString {
            append("{\"threads\":${Ct2Translator.activeThreads},")
            append("\"max_batch\":${Ct2Translator.maxBatchSize},")
            append("\"batch_type\":\"$batchType\",")
            append("\"compute_type\":\"${Ct2Translator.activeComputeType}\",")
            append("\"chunk_chars\":${ChapterTranslator.chunkChars},")
            append("\"cache_enabled\":${ChunkTranslator.cacheEnabled},")
            append("\"cache_entries\":${cache.entries},")
            append("\"ready\":${Ct2Translator.isReady()}}")
        }
    }
}
