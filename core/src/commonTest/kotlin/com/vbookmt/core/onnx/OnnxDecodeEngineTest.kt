package com.vbookmt.core.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backend that answers with scripted logits, so the decode loop can be tested without a model.
 *
 * The script is per-step: entry `k` says which *trimmed index* wins at step `k`. That is what the
 * loop must map through `keepIds`, so a mapping bug shows up as a wrong decoded string rather than as
 * an assertion about internals.
 */
private class FakeBackend(
    private val keepIds: IntArray,
    private val scriptedIds: List<List<Int>>,
    private val batch: Int,
) : OnnxBackend {

    val released = mutableListOf<TensorHandle>()
    var cacheInputsSeen: List<String> = emptyList()
    var gathered: List<Int> = emptyList()
    var gatheredCount = 0
    private var step = 0

    /**
     * Original input rows still in the batch.
     *
     * The fake mirrors the decoder's own rule for leaving the batch — emit EOS and you are dropped —
     * so it can keep scripting per *original* row while the batch it is handed shrinks underneath it.
     */
    private var liveRows: MutableList<Int> = mutableListOf()

    override fun run(graph: String, inputs: Map<String, TensorHandle>): List<TensorHandle> {
        when (graph) {
            Graphs.ENCODER -> return listOf(Handle("hidden"))

            Graphs.DECODER -> {
                step = 0
                liveRows = (0 until batch).toMutableList()
                return logitsPlusCache()
            }

            Graphs.DECODER_WITH_PAST -> {
                cacheInputsSeen = inputs.keys.filter { it.startsWith("past_key_values") }
                step += 1
                return logitsPlusCache()
            }
        }
        error("unknown graph $graph")
    }

    private fun logitsPlusCache(): List<TensorHandle> {
        // [batch, 1, vocab] floats, one-hot on the scripted winner for each live row.
        val vocab = keepIds.size
        val script = scriptedIds.getOrElse(step) { List(batch) { keepIds.lastIndex } }

        val currentBatch = liveRows.size
        val values = FloatArray(currentBatch * vocab)
        liveRows.forEachIndexed { pos, row ->
            values[pos * vocab + script[row]] = 1f
        }

        // Mirror the decoder: rows that just emitted EOS leave the batch from the next step on.
        liveRows = liveRows.filterTo(mutableListOf()) { row -> keepIds[script[row]].toLong() != 2L }

        return listOf(Handle("logits", values, rows = currentBatch)) + (0 until 8).map { Handle("cache$it", rows = currentBatch) }
    }

    override fun outputNames(graph: String): List<String> = emptyList()

    override fun longs(values: LongArray, shape: LongArray): TensorHandle = Handle("longs")

    override fun readFloats(handle: TensorHandle): FloatArray = (handle as Handle).floats

    /** Mirrors the real fast path: argmax over each row's last `vocab`-wide slice. */
    override fun argmaxLastRow(handle: TensorHandle, rows: Int, vocab: Int): IntArray {
        val floats = (handle as Handle).floats
        if (rows <= 0 || vocab <= 0 || floats.isEmpty()) return IntArray(0)
        val rowWidth = floats.size / rows
        val lastOffset = rowWidth - vocab
        return IntArray(rows) { row ->
            val base = row * rowWidth + lastOffset
            var best = 0
            var bestVal = floats[base]
            for (i in 1 until vocab) {
                val v = floats[base + i]
                if (v > bestVal) {
                    bestVal = v
                    best = i
                }
            }
            best
        }
    }

    override fun gatherRows(source: TensorHandle, keep: IntArray): TensorHandle {
        val handle = source as Handle
        for (idx in keep) {
            require(idx in 0 until handle.rows) {
                "gatherRows: index $idx out of bounds for source tensor '${handle.name}' with ${handle.rows} rows"
            }
        }
        gathered = keep.toList()
        gatheredCount += 1
        return Handle(handle.name, rows = keep.size)
    }

    override fun release(handle: TensorHandle) {
        released.add(handle)
    }

    data class Handle(val name: String, val floats: FloatArray = FloatArray(0), val rows: Int = 0)
}

/** Decodes ids through a reversed lookup so a test can assert the mapping happened. */
private class FakeTokenizer : OnnxTokenizer {
    override fun encode(text: String): LongArray = LongArray(4) { 10L + it }
    override fun decode(ids: LongArray): String = ids.joinToString(",")
}

class OnnxDecodeEngineTest {

    /** Trimmed vocabulary: index -> real token id. Index 0 is deliberately NOT token id 0. */
    private val keepIds = intArrayOf(0, 1, 2, 3, 100, 200, 300)

    @Test
    fun mapsTrimmedIndicesBackThroughKeepIds() {
        // Winners are trimmed indices 4, 5, 6 -> real ids 100, 200, 300, then EOS.
        val backend = FakeBackend(keepIds, listOf(listOf(4), listOf(5), listOf(6), listOf(2)), batch = 1)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "past_key_values.$it" }, emptyList())
        val engine = OnnxDecodeEngine(model)

        val out = engine.translate(listOf("x"), maxSteps = 10)

        // EOS (index 2 -> real id 2) is trimmed, so only the three mapped ids remain.
        assertEquals(listOf("100,200,300"), out)
    }

    @Test
    fun aRawIndexWouldProduceDifferentOutput() {
        // The same script decoded WITHOUT keepIds would be "4,5,6". This pins the difference so a
        // regression that skips the mapping fails loudly instead of looking like worse translation.
        val backend = FakeBackend(keepIds, listOf(listOf(4), listOf(5), listOf(6), listOf(2)), batch = 1)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "past_key_values.$it" }, emptyList())

        val out = OnnxDecodeEngine(model).translate(listOf("x"), maxSteps = 10)

        assertTrue(out[0] != "4,5,6", "output must go through keepIds, got ${out[0]}")
    }

    @Test
    fun batchRowsDecodeIndependentlyAndStopAtTheirOwnEos() {
        // Row 0 finishes after 2 tokens, row 1 needs 4. Both must come back truncated correctly.
        // Each entry is [row0, row1] for that step.
        val scripted = listOf(
            listOf(4, 5),   // row0 -> 100, row1 -> 200
            listOf(5, 6),   // row0 -> 200, row1 -> 300
            listOf(2, 4),   // row0 -> EOS (done), row1 -> 100
            listOf(2, 2),   // row0 idle, row1 -> EOS (done)
        )
        val backend = FakeBackend(keepIds, scripted, batch = 2)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "past_key_values.$it" }, emptyList())

        val out = OnnxDecodeEngine(model).translate(listOf("a", "b"), maxSteps = 10, batchSize = 2)

        assertEquals(2, out.size)
        // Row 0: trimmed 4 -> 100, then 5 -> 200, then EOS trimmed off.
        assertEquals("100,200", out[0])
        // Row 1: 5 -> 200, 6 -> 300, 4 -> 100, then EOS trimmed.
        assertEquals("200,300,100", out[1])
    }

    @Test
    fun feedsTheCacheBackAsCacheInputs() {
        val backend = FakeBackend(keepIds, listOf(listOf(4), listOf(5), listOf(2)), batch = 1)
        val names = listOf("past_key_values.0.self_k", "past_key_values.0.self_v")
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, names, emptyList())

        OnnxDecodeEngine(model).translate(listOf("x"), maxSteps = 5)

        assertEquals(names, backend.cacheInputsSeen, "every cache input must be fed back by name")
    }

    @Test
    fun stopsAtMaxStepsWhenNoEosAppears() {
        // Winner never EOS: the result is truncated at maxSteps, not dropped.
        val backend = FakeBackend(keepIds, List(20) { listOf(4) }, batch = 1)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "p.$it" }, emptyList())

        val out = OnnxDecodeEngine(model).translate(listOf("x"), maxSteps = 3)

        assertEquals("100,100,100", out[0])
    }

    @Test
    fun dropsFinishedRowsFromTheBatchInsteadOfDecodingThemToTheEnd() {
        // Row 0 emits EOS at step 2 of four; row 1 runs to step 4. A loop that keeps dead rows would
        // feed both rows four times. The gathered cache must shrink to the surviving row.
        val scripted = listOf(
            listOf(4, 5),   // row0 -> 100, row1 -> 200
            listOf(2, 6),   // row0 -> EOS (leaves), row1 -> 300
            listOf(2, 4),   // row0 gone; row1 -> 100
            listOf(2, 2),   // row1 -> EOS
        )
        val backend = FakeBackend(keepIds, scripted, batch = 2)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "p.$it" }, emptyList())

        val out = OnnxDecodeEngine(model).translate(listOf("a", "b"), maxSteps = 10, batchSize = 2)

        assertEquals("100", out[0], "row 0 must stop at its own EOS")
        assertEquals("200,300,100", out[1], "row 1 must keep decoding after row 0 leaves")
        assertEquals(listOf(1), backend.gathered, "the cache must be gathered down to the surviving row")
        // One shrink event = one gather per cache tensor (8), not one per step. Gathering at every
        // step would be pure copy cost, which is the thing this test is guarding.
        assertEquals(8, backend.gatheredCount, "gather once per cache tensor, on the single shrink event")
    }

    @Test
    fun emptyInputReturnsEmptyWithoutTouchingTheBackend() {
        val backend = FakeBackend(keepIds, emptyList(), batch = 1)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, emptyList(), emptyList())

        assertTrue(OnnxDecodeEngine(model).translate(emptyList()).isEmpty())
        assertTrue(backend.released.isEmpty(), "no tensors should be created for an empty batch")
    }

    @Test
    fun releasesCacheTensorsSoLongDecodesDoNotLeak() {
        val backend = FakeBackend(keepIds, List(6) { listOf(4) }, batch = 1)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "p.$it" }, emptyList())

        OnnxDecodeEngine(model).translate(listOf("x"), maxSteps = 6)

        // 8 initial cache tensors + 8 per subsequent step must all be released.
        assertTrue(
            backend.released.count { it is FakeBackend.Handle && it.name.startsWith("cache") } >= 8,
            "cache tensors must be released, released=${backend.released.size}",
        )
    }

    @Test
    fun multiStepShrinkingBatchMaintainsValidGatherIndices() {
        // 3 rows finishing sequentially at different steps:
        // row 0 finishes at step 1 -> batch shrinks 3 -> 2
        // row 1 finishes at step 2 -> batch shrinks 2 -> 1 (previously crashed here because keep index was 2 >= 2)
        // row 2 finishes at step 3 -> done
        val scripted = listOf(
            listOf(4, 5, 6),  // step 0: row0 -> 100, row1 -> 200, row2 -> 300
            listOf(2, 6, 4),  // step 1: row0 -> EOS (done), row1 -> 300, row2 -> 100
            listOf(2, 2, 5),  // step 2: row1 -> EOS (done), row2 -> 200
            listOf(2, 2, 2),  // step 3: row2 -> EOS (done)
        )
        val backend = FakeBackend(keepIds, scripted, batch = 3)
        val model = OnnxModel(backend, FakeTokenizer(), keepIds, (0 until 8).map { "p.$it" }, emptyList())

        val out = OnnxDecodeEngine(model).translate(listOf("a", "b", "c"), maxSteps = 10, batchSize = 3)

        assertEquals(3, out.size)
        assertEquals("100", out[0])
        assertEquals("200,300", out[1])
        assertEquals("300,100,200", out[2])
    }
}
