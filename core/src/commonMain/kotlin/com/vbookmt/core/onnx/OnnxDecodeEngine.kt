package com.vbookmt.core.onnx

/**
 * Greedy decoding against a trimmed Marian ONNX export.
 *
 * Lives in `commonMain` so batching, cache handling and vocabulary mapping are unit-tested on the JVM
 * against a fake backend. Android and Windows only implement [OnnxBackend] and [OnnxTokenizer].
 *
 * ## The three things this exists to get right
 *
 * **Vocabulary mapping.** The graphs emit 5,951-wide logits, so `argmax` indexes the *trimmed*
 * vocabulary. Every step maps through [OnnxModel.keepIds] before the id is fed back or decoded.
 * Skipping that still yields fluent Vietnamese — from the wrong words — which reads as a model
 * quality problem instead of a bug.
 *
 * **Cache plumbing.** Marian's per-layer cache is four tensors — `self_k`, `self_v`, `cross_k`,
 * `cross_v` — not the usual key/value pair. Step 1 produces the initial cache; every later step takes
 * it as input and returns the extended one. `decoder_with_past` takes `encoder_attention_mask` but
 * **not** `encoder_hidden_states`: the source representations live inside the cross cache.
 *
 * **Batching.** There is no decoder attention mask, so rows decode in lockstep. A row that emits EOS
 * is then **dropped from the batch**, and the cache and encoder mask are gathered down to the rows
 * still decoding — so a finished row costs nothing for the rest of the batch. Measured on the shipped
 * 30-QT graph, that is worth ~30% of a whole 16-row decode; the copies it needs cost ~2% of it.
 * Because rows leave as they finish, batch position and input index diverge, which is why the loop
 * carries a `live` map.
 */
class OnnxDecodeEngine(private val model: OnnxModel) {

    /** Marian's end-of-sequence id in the untrimmed vocabulary. */
    private val eosId = 2L

    /** Start-of-decoding token; Marian uses the padding id 0. */
    private val startId = 0L

    /**
     * Translates [texts], one batched decode per [batchSize] group.
     *
     * @param maxSteps hard ceiling per row. Vietnamese runs ~1.0–1.6x the source token count, so size
     *   this from the longest input rather than passing the model's 512 limit — a generous ceiling
     *   grows the cache on every row for nothing.
     * @return one entry per input, in input order. A row that hits [maxSteps] without emitting EOS is
     *   returned as far as it got rather than dropped, so a caller can still show something.
     */
    fun translate(
        texts: List<String>,
        maxSteps: Int = 256,
        batchSize: Int = 16,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val out = ArrayList<String>(texts.size)
        for (group in texts.chunked(batchSize.coerceAtLeast(1))) {
            out.addAll(translateBatch(group, maxSteps))
        }
        return out
    }

    private fun translateBatch(texts: List<String>, maxSteps: Int): List<String> {
        val n = texts.size

        // Pad to a common width. The encoder ignores masked positions, so a short sentence translates
        // the same alone or beside a long one — verified against the fp32 model.
        val encoded = texts.map { model.encode(it) }
        val width = encoded.maxOfOrNull { it.size } ?: 0
        if (width == 0) return texts.map { "" }
        val ids = LongArray(n * width)
        val mask = LongArray(n * width)
        encoded.forEachIndexed { row, seq ->
            for (col in seq.indices) {
                ids[row * width + col] = seq[col]
                mask[row * width + col] = 1L
            }
        }
        val seqShape = longArrayOf(n.toLong(), width.toLong())

        var encoderInputIds: TensorHandle? = null
        var encoderMask: TensorHandle? = null
        var encoderHidden: TensorHandle? = null
        var firstDecoderInputIds: TensorHandle? = null
        var currentStepIds: TensorHandle? = null
        var logits: TensorHandle? = null
        var cache: List<TensorHandle> = emptyList()

        // Original input row for each position in the current (shrinking) batch.
        var live = IntArray(n) { it }
        val rows = Array(n) { mutableListOf<Long>() }

        // Rebuilt rather than gathered whenever [live] shrinks: it is a few hundred int64 per row,
        // far cheaper than extending [OnnxBackend.gatherRows] to a third element type.
        var liveMask: TensorHandle? = null

        fun maskFor(keep: IntArray): TensorHandle {
            val out = LongArray(keep.size * width)
            keep.forEachIndexed { i, row ->
                for (col in 0 until width) out[i * width + col] = mask[row * width + col]
            }
            return model.backend.longs(out, longArrayOf(keep.size.toLong(), width.toLong()))
        }

        try {
            encoderInputIds = model.backend.longs(ids, seqShape)
            encoderMask = model.backend.longs(mask, seqShape)
            val encoderOut = model.backend.run(
                Graphs.ENCODER,
                mapOf(
                    "input_ids" to encoderInputIds,
                    "attention_mask" to encoderMask,
                )
            )
            model.backend.release(encoderInputIds)
            encoderInputIds = null
            encoderHidden = encoderOut.first()

            // --- step 1: no cache in -------------------------------------------------------------
            val decInputIds = model.backend.longs(
                LongArray(n) { startId },
                longArrayOf(n.toLong(), 1L),
            )
            firstDecoderInputIds = decInputIds
            val firstOut = model.backend.run(
                Graphs.DECODER,
                mapOf(
                    "decoder_input_ids" to decInputIds,
                    "encoder_hidden_states" to encoderHidden,
                    "encoder_attention_mask" to encoderMask,
                )
            )
            model.backend.release(decInputIds)
            firstDecoderInputIds = null
            logits = firstOut.first()
            cache = firstOut.drop(1)
            appendTokens(rows, live, logits)

            // Dynamic ceiling: protect against runaway token generation on short sentences
            // while giving ample headroom for Vietnamese expansion (~1.5x source tokens).
            val dynamicCap = width * 2 + 32
            val effectiveMaxSteps = if (maxSteps <= dynamicCap) maxSteps else maxOf(48, dynamicCap)

            // --- steps 2..n: cache in, cache out -------------------------------------------------
            for (step in 1 until effectiveMaxSteps) {
                if (live.all { rows[it].last() == eosId }) break

                val stepIds = model.backend.longs(
                    LongArray(live.size) { rows[live[it]].last() },
                    longArrayOf(live.size.toLong(), 1L),
                )
                currentStepIds = stepIds
                val feeds = LinkedHashMap<String, TensorHandle>()
                feeds["decoder_input_ids"] = stepIds
                feeds["encoder_attention_mask"] = liveMask ?: encoderMask
                model.cacheInputNames.forEachIndexed { i, name -> feeds[name] = cache[i] }

                val out = model.backend.run(Graphs.DECODER_WITH_PAST, feeds)

                // Release only after the run has consumed them: the old cache is an input to the step
                // that just completed, and the new one now owns those buffers.
                model.backend.release(stepIds)
                currentStepIds = null
                model.backend.releaseAll(cache)
                logits?.let { model.backend.release(it) }

                logits = out.first()
                cache = out.drop(1)

                appendTokens(rows, live, logits)

                // Drop rows that just emitted EOS. A finished row would otherwise be decoded and
                // carried in the cache until the whole batch ends — measured at ~2.7 ms per dead row
                // per step at a 448-token cache, against ~40 MB of copying to remove eight of them.
                val keepIndices = live.indices.filter { pos -> rows[live[pos]].last() != eosId }.toIntArray()
                if (keepIndices.size < live.size) {
                    val newLive = IntArray(keepIndices.size) { live[keepIndices[it]] }
                    if (keepIndices.isNotEmpty()) {
                        val shrunk = cache.map { model.backend.gatherRows(it, keepIndices) }
                        model.backend.releaseAll(cache)
                        cache = shrunk

                        val oldMask = liveMask
                        liveMask = maskFor(newLive)
                        oldMask?.let { model.backend.release(it) }
                    }
                    live = newLive
                }
            }

            return rows.map { row -> model.decode(trimAtEos(row).toLongArray()) }
        } finally {
            currentStepIds?.let { model.backend.release(it) }
            encoderInputIds?.let { model.backend.release(it) }
            firstDecoderInputIds?.let { model.backend.release(it) }
            logits?.let { model.backend.release(it) }
            model.backend.releaseAll(cache)
            liveMask?.let { model.backend.release(it) }
            encoderHidden?.let { model.backend.release(it) }
            encoderMask?.let { model.backend.release(it) }
        }
    }

    /**
     * Appends each live row's next token, mapping the trimmed index back to a real token id.
     *
     * `logits` is `[batch, tgt_len, vocab]`. Only the final position matters, and `tgt_len` is 1 in
     * every step here, so a row's payload occupies `vocab` floats at `row * rowWidth`.
     *
     * [live] holds the original input row for each batch position, and every one of them is by
     * construction unfinished — rows are dropped as soon as they emit EOS.
     */
    private fun appendTokens(
        rows: Array<MutableList<Long>>,
        live: IntArray,
        logits: TensorHandle?,
    ) {
        if (logits == null || live.isEmpty()) return
        val vocab = model.outputVocabSize

        // One call into the backend for the whole batch: it scans each row's last position directly
        // off the tensor's own buffer. Doing this here in `commonMain` instead would force the backend
        // to hand over a converted fp32 copy of the full [batch, 1, vocab] tensor every step.
        val picks = model.backend.argmaxLastRow(logits, live.size, vocab)
        if (picks.size < live.size) return

        for (pos in live.indices) {
            rows[live[pos]].add(model.keepIds[picks[pos]].toLong())
        }
    }

    private fun trimAtEos(seq: List<Long>): List<Long> {
        val end = seq.indexOf(eosId)
        return if (end >= 0) seq.subList(0, end) else seq
    }
}
