# ONNX quantization for VBookMT — backlog

Status: **open, unclaimed**. Written 2026-11-01. No code changed yet. Every claim below is backed by a
file:line or a probe run; claims that are reasoned rather than measured say so.

Related: AD-17 in `ARCHITECTURE.md` (chose fp16, recorded "dynamic INT8 measurably cost accuracy").

---

## BL-1 — AD-17's "int8 cost accuracy" verdict doesn't hold up

AD-17 reads as though int8 was tested and rejected for this model. It wasn't — it tested one specific,
badly-matched int8 configuration.

### The confound

The only int8 artifact in the tree is **dynamic** quantization. Probed by loading the graph:

```
exported_models/moxhi-30-qt/int8/onnx/decoder_model_quantized.onnx
    DynamicQuantizeLinear 14,  MatMulInteger 21,  DequantizeLinear 2,  MatMul 8,  Cast 33
    initializer dtypes: FLOAT 56, UINT8 4, INT8 42
```

`MatMulInteger` + `DynamicQuantizeLinear` ⇒ activation scales computed **at run time**. Three separate
reasons that number can't indict int8:

1. **It was measured on the CPU execution provider.** Dynamic quant is a CPU-oriented ORT mode, and the
   desktop JAR is CPU-only (`OnnxProvider.kt:31-35`) — which is what ran.
2. **Dynamic quant is a candidate *cause* of the loss.** Per-tensor scales recomputed for every
   activation across ~448 autoregressive steps mean the same weight matrix is re-quantized against a
   shifting range each step; that error is biased rather than zero-mean and compounds through the
   decode. Static quantization removes the mechanism entirely.
   *(Reasoning from op semantics, not measured — see BL-4.)*
3. **The artifact predates the trim and the KV cache.** It's 42.8 MB against today's 140 MB precisely
   because it re-runs the whole prefix per token and emits a 24,000-wide vocabulary. Comparing quality
   against it measures two independent changes at once.

**So the honest claim is narrower:** *dynamic int8, on CPU, on an untrimmed cache-less graph, produced
worse output.* Static QDQ and weight-only int8 are untested. Fix the wording — see BL-5.

### Current fp16 baselines (sizing context for any replacement)

| build | encoder | decoder | decoder_with_past | total |
| :--- | ---: | ---: | ---: | ---: |
| `build/onnx_trimmed/hachimi-60-qt` | 92.4 MB | 56.6 MB | 53.9 MB | **203 MB** |
| `build/onnx_trimmed/moxhi-30-qt` | 60.9 MB | 40.4 MB | 38.8 MB | **140 MB** |

### There is a third option besides "int8" and "fp16"

QNN HTP `MatMul` accepts `(uint8,uint8)`, `(uint8,uint16)`, `(uint16,uint8)` — and
`get_qnn_qdq_config` exposes `activation_type=QuantType.QUInt16`. So **16-bit activations with 8-bit
weights** is a supported QNN path sitting between the two extremes: roughly half the weight traffic of
fp16 with far less activation error than full int8. It's a one-argument change on top of the QDQ build in
BL-2, so it's worth including in the same sweep rather than as a separate project.

Note `QUInt8`/`QUInt16` — QNN's HTP dtypes are **unsigned**, unlike the `INT8` initializers in the
existing graphs.

---

## BL-2 — Which quantization method to use

The deployable answer is **static QDQ via `get_qnn_qdq_config`**, not the rotation/low-bit family.

### Available locally (probed)

```
onnx 1.23.2 · onnxruntime 1.31.1 · optimum 2.1.0 · torch 2.15.0 · transformers 4.57.6
optimum.quanto ....... MISSING (ModuleNotFoundError)
neural_compressor .... MISSING
nncf ................. MISSING
ORT providers ........ ['AzureExecutionProvider', 'CPUExecutionProvider']
```

`get_qnn_qdq_config` is **not** in `onnxruntime.quantization`'s top-level exports (that import fails);
it lives at `onnxruntime.quantization.execution_providers.qnn.quant_config`. Verified signature:

```python
get_qnn_qdq_config(
    model_input, calibration_data_reader,
    calibrate_method=CalibrationMethod.MinMax,
    activation_type=QuantType.QUInt8,
    weight_type=QuantType.QUInt8,
    per_channel=False,
    init_overrides=None,          # mixed-precision / per-tensor overrides
    add_qtype_converts=True,
    activation_symmetric=False,
    weight_symmetric=None,
    keep_removable_activations=False,
    stride=None,
    calibration_providers=None,
    op_types_to_quantize=None,
    nodes_to_exclude=None,
) -> StaticQuantConfig
```

Also present: `mixed_precision_overrides_utils.TensorTypeRequest` /
`MixedPrecisionTensorQuantOverridesFixer`, which is how individual ops get promoted back to fp16.

### Methods considered, and why they aren't the answer here

| method | deployable to ORT / QNN today? | verdict |
| :--- | :--- | :--- |
| **Dynamic int8** (`MatMulInteger`) | CPU only; `MatMulInteger`/`DynamicQuantizeLinear` appear nowhere in QNN's supported-op table | **What we already have — the thing to replace** |
| **Static QDQ** (uint8 or uint16) | Yes; `get_qnn_qdq_config` is installed; HTP `MatMul` accepts `(uint8,uint8)`, `(uint8,uint16)`, `(uint16,uint8)` | ✅ **Recommended** |
| **Weight-only int8** (`DequantizeLinear`→`MatMul`) | Yes, but QNN scopes "weights uint8, activations float" to the **GPU** backend | Good for the *desktop* story; not HTP |
| **AWQ / GPTQ / HQQ** (W4A16) | Partly, via `MatMulNBits` (ORT lists HQQ/GPTQ/RTN) | Defer. 4-bit on a 60M MT model is high risk for Vietnamese diacritics |
| **SmoothQuant** | Not an ORT op; it's a PyTorch-side smoothing transform, after which you still run `quantize_static`. That path *is* QNN-compatible | Optional refinement, only if plain static falls short |
| **QuaRot / SpinQuant** | No ORT kernel | Out of scope |
| **optimum-quanto** | Not installed; PyTorch-only, needs manual ONNX conversion | Out of scope |

**Recommended config:** `activation_type=QUInt8` and `QUInt16` as two variants, `weight_type=QUInt8`,
`per_channel=True`, `calibrate_method=Percentile` (99.9–99.99), with LayerNorm/softmax/logits promoted
back to fp16 via `init_overrides`.

Two constraints read out of `quant_config.py`, both of which bite here:

- **Weights must stay 8-bit.** `MatMul` weight handling bails out with
  `if self.default_weight_qtype not in Q8_TYPES: return`, so `weight_type` must be `QInt8`/`QUInt8` —
  a 16-bit-activation variant still needs `QUInt8` weights.
- **16-bit activations force contrib ops.** Our graphs are opset 17, and
  `if onnx_opset.version < 21 … extra_options["UseQDQContribOps"] = True` means the `QUInt16` variant
  emits `com.microsoft`-domain Q/DQ nodes rather than standard ones. That is fine for QNN, but it makes
  the `QUInt16` graph *not* loadable by a plain ONNX Runtime build without contrib ops — worth knowing
  before shipping it as a general artifact.

One more known trap in the same file: the quantizer does **not** give `MatMul` or `LayerNorm` initializer
inputs the default weight type — it assigns them the *activation* type instead. Marian is MatMul-heavy,
so check the emitted graph's initializer dtypes rather than assuming `weight_type` was honoured.

### Validate with `disable_cpu_ep_fallback`

`backend_type=htp` is pinned at `OnnxProvider.kt:77`, and HTP rejects anything it can't compile. Every
new artifact must be probed with `disable_cpu_ep_fallback` so an unsupported node **throws** instead of
silently degrading — otherwise the next benchmark again measures CPU while claiming to measure the NPU.

---

## BL-3 — Padding / batching / cache: what exists, what doesn't

### Cache — exists, and is correct

`OnnxDecodeEngine` has a real KV cache: step 1 from `decoder_model`, steps 2..n from
`decoder_with_past`, four per-layer tensors (`self_k`, `self_v`, `cross_k`, `cross_v` — Marian's layout,
not a plain K/V pair). Old cache tensors are released only *after* the run that consumes them
(`OnnxDecodeEngine.kt:123-130`).

### Padding — correct, but wasteful

`OnnxDecodeEngine.kt:59-71` pads each batch to the longest sequence and relies on the encoder attention
mask. Correct (verified against fp32), but a long chunk in a group makes every short chunk pay for it.

**Length bucketing already exists.** `ChunkResolver.batches()` (`core/.../ChunkResolver.kt:117`) does
`misses.sortedBy { tokenCount(it.text) }.chunked(size)` before either engine is called. So batches
already group similar lengths — this is not a gap.

### Three real gaps

1. **The decode loop never dropped finished rows.** *(Fixed 2026-11-01 — see "Done" below.)*
   `OnnxDecodeEngine.kt:21-24` admitted it: *"A finished row keeps producing tokens until the batch
   ends; that is wasted compute."* CT2 can't fix this (library-internal), but this loop is our own
   Kotlin. Because bucketing sorts ascending, the shortest row finishes first and wastes the most.
2. **No token-budget batching on the ONNX path.** CT2 has `useTokenBatching` / `BatchType::Tokens`
   (`Ct2Translator.kt:62-64, 288, 315`); ONNX takes a fixed `batchSize` only (`OnnxTranslator.kt:141`).
3. ~~**`maxSteps` is hardcoded, contradicting its own contract.**~~ **Retracted — not a bug.** The loop
   is `for (step in 1 until maxSteps)` guarded by `if (done.all { it }) break`, so `maxSteps` is a true
   ceiling and no steps are wasted once every row has finished. The docstring's advice to size it from
   the longest input is sound *reasoning* about cache growth, but the fixed 448 costs nothing in
   practice beyond one extra pass. **Do not "fix" this.**

---

## Done — BL-3 item 1: finished rows are now dropped (2026-11-01)

Measured before implementing, because the win was not obvious: dropping rows saves per-step time but
costs a cache copy at every finish event, and 15 exits × ~40 MB of memcpy could easily eat the gain.
Simulated the whole 16-row decode against the shipped trimmed fp16 graph:

| | total |
| :--- | ---: |
| status quo (dead rows decode too) | 2422 ms |
| drop finished rows | **1686 ms** (of which cache copies: 41 ms) |
| **net** | **−737 ms / −30.4 %** |

Marginal row cost measured at ~1.9 ms at a 128-token cache and ~2.7 ms at 448, against ~8 rows dropped
at the median exit — so the copies are worth it by a wide margin.

Implementation: `OnnxBackend.gatherRows(source, keep)` gathers axis 0 preserving element type (the cache
is fp16 and ORT binds feeds by declared type, so a float32 copy would fail the binding rather than work).
The loop tracks `live` — original input row per batch position — and rebuilds the encoder mask only when
a shrink happens, since it is a few hundred int64 per row rather than a 40 MB cache tensor.
Gathering is one event per finish, not per step, and a test pins that: `gatheredCount == 8` (one per
cache tensor) on a single shrink, not 8 × steps.

---

## BL-6 — GPU works, NPU does not (measured 2026-11-01, OnePlus PKG110 / SM8650)

Ran the whole stack on a real Snapdragon 8 Gen 3, HTP v75, Android 15.

### QNN HTP (the NPU): fails, and not fixable here

```
qnn_execution_provider.cc: GetCapability
  Failed to create device. Error: QNN_DEVICE_ERROR_INVALID_CONFIG
```

A **QNN SDK generation mismatch**: `onnxruntime-android-qnn` bundles `qnn-runtime` 2.33.0, while the
phone's DSP firmware in `/odm/lib64` is **2.13.1**. HTP splits across host code and DSP firmware and
both halves must be one SDK. The only "fixes" are shipping the OEM's firmware-matched libs (correct on
exactly one phone) or pinning ORT to that generation (pins the app to one firmware) — neither is a
product. **HTP is not a portable capability.** `vtcm_mb`/`htp_performance_mode`/`soc_model` were tried
or considered and do not bridge a version gap.

### QNN GPU (Adreno): works

Three separate blockers had to be cleared, all now fixed in-tree:

1. **`backend_type=gpu` did not exist in ORT 1.22.** Upgraded Android to
   `onnxruntime-android-qnn:1.29.0`, whose `libonnxruntime.so` carries the `QnnGpu` backend.
2. **`libQnnGpu.so` needs OpenCL and dlopens `/vendor/lib64/libOpenCL.so` by absolute path.** Android
   namespaces block that even though the library is in `public.libraries.txt`. Fixed with
   `<uses-library android:name="libOpenCL.so" android:required="false"/>` in the manifest. Before this,
   the error was `QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED`; after it, the backend initialises.
3. **The QDQ graphs are rejected by the GPU backend.** QNN GPU runs fp32/fp16 directly and does not
   want pre-quantized `DequantizeLinear`/`QuantizeLinear` graphs — every MatMul/Gather/Reshape was
   "unsupported". **Use the fp16 build for GPU**, the q8/q16 QDQ builds are HTP artifacts.

Confirmed working: `ONNX model loaded ... on QNN GPU (Adreno)`, and `/sys/class/kgsl/kgsl-3d0/gpubusy`
shows real activity, so the GPU is genuinely executing, not idling.

### Throughput — CORRECTED. The first numbers were wrong by ~20x.

**The earlier 7800–8188 chars/s figures in this document were a measurement bug and must not be
quoted.** Two errors compounded:

1. The benchmark re-sent **identical text** on every run. The chunk cache is keyed on
   `(model, beams, text)`, so runs 2..n were cache hits, and the script reported `min(times)` — which
   *selects the fastest hit*. It measured the cache, not the engine.
2. It reported `min()` of a few runs, which rewards the luckiest scheduling, on a phone that thermally
   throttles.

Measured properly — cache disabled, unique text, mean of several runs — the real numbers are:

| workload | CT2 (int8 CPU) | ONNX CPU | QNN GPU |
| :--- | ---: | ---: | ---: |
| 1 paragraph ×20 (680 chars) | 168 chars/s | **755** | 358 |
| 8 distinct ×3 (743 chars) | 442 | **1084** | 1057 |
| 8 distinct ×6 (1487 chars) | 820 | **1992** | 1566 |

Two conclusions, both different from what this document previously said:

- **Throughput is dominated by input size, not by provider.** The same engine measures 37 chars/s at
  62 characters and 444 chars/s at 1333 characters. A short request pays a large fixed cost (HTTP,
  proto, tokenise, chunk, engine dispatch) that a longer one amortises. So "the app says ~400 chars/s"
  and "a benchmark says thousands" describe different workloads; **the app's ~400 figure is the honest
  steady-state one for chapter chunks**, and any chars/s claim without its input size is meaningless.
- **ONNX CPU beats CT2 by 2–4.5x at equal length**, which is the opposite of what the earlier
  (cache-polluted) comparison implied.
- **QNN GPU lands between the two** and is *slower* than ONNX CPU at these sizes — consistent with the
  partial-offload finding below, but no longer a 30% claim, and the margin depends on input length.

None of these numbers is a product guarantee: they move with input size, cache state, and thermal
state, and the phone throttles under sustained load.

### The one lever that could have flipped it — tested, it doesn't (per-graph split)

The encoder and the decoders have opposite shapes, so a split was worth trying: encoder on GPU (one
wide forward pass), decoders on CPU (450 tiny dispatch-bound steps). `OnnxProvider.HYBRID` implements
exactly that — the encoder session gets `backend_type=gpu`, the two decoder sessions get CPU.

Same scaling test, cache disabled, one request per row, mean-of-runs:

| input | CPU | Hybrid (encoder GPU / decoder CPU) |
| ---: | ---: | ---: |
| 62 chars | 115 | 110 |
| 185 | 289 | 269 |
| 349 | 505 | 474 |
| 677 | 941 | **1003** |
| 1333 | 1797 | **1872** |

Hybrid edges ahead only at the two largest sizes (+6% and +4%), and is slightly behind below 677
characters — the GPU encoder's transfer cost is not repaid until there is enough encode work to
amortise it. That is within run-to-run noise on a thermally-throttling phone.

**Conclusion: the encoder is not the bottleneck.** Splitting the work along the graph boundary moves
a small share of the time to the GPU and still loses at the sizes the reader actually experiences.
The decoder — hundreds of sequential single-token steps — dominates, and no provider assignment can
parallelise it.

### Why the browser's WebGPU beats this — asked directly

The comparison is not like-for-like, and the difference is structural rather than a tuning problem:

1. **WebGPU in the browser is a whole-graph GPU provider.** Dawn (shipped *inside* onnxruntime-web's
   WASM build) compiles the entire graph down to compute shaders. QNN's GPU backend here **claims only
   a subgraph** — logcat prints the rejected nodes — and every rejected op round-trips through CPU,
   including the memcpy of its inputs and outputs. On a 30M-parameter model that transfer cost exceeds
   the compute it saves.
2. **More importantly: natively, this project cannot use WebGPU at all.** `addWebGPU(...)` *is* present
   in ORT 1.29's Java API, but checked in all three AARs (1.22 plain, 1.29 QNN, and both
   `onnxruntime-android` 1.29/1.31.1) the only WebGPU artifact is **`headers/webgpu_provider_factory.h`**
   — a header, no `.so`. The provider ships as a separate plugin library
   (`onnxruntime_providers_webgpu.so`, registered via `RegisterExecutionProviderLibrary`), is not in
   the Android AAR, and is not published on Maven. Supplying it would mean building Dawn + the WebGPU
   EP for Android arm64 from source and shipping the plugin, which is a separate project.
3. **Autoregressive decode is the wrong shape for any GPU.** One token per step, ~450 steps, each a
   tiny dispatch. Desktop DirectML measured the same thing earlier (0.30x on `decoder_with_past`).

So the browser wins on *its* backend, with denser offload and a WASM/JSEP path that was designed for
that graph. The native app's QNN GPU is a partial-offload path with a different cost profile. Neither
result contradicts the other — but it does mean "WebGPU is faster" is not transferable here without
shipping a WebGPU plugin.

### What this means for the product

`OnnxProvider.defaultForPlatform()` returns **CPU on Android**, and that is the corrected measurement,
not a fallback: CPU beat QNN GPU at every input size tested. QNN GPU earns its place only for a much
larger model or batch, and neither is in scope.

More usefully: **ONNX CPU beats CTranslate2 by 2–4.5x at equal input length** in these runs, which is
the opposite of what the earlier cache-polluted comparison suggested. That is now the interesting
result, and it is worth re-measuring on real chapter text before it is turned into a claim — the
numbers above move with input size, and the phone throttles under sustained load.

Both accelerators stay *available* and both report honestly — `HealthReport.computeType` now says
exactly what initialised, e.g. `on QNN GPU (Adreno): partial offload` or `on CPU (requested QNN: ...)`.
A provider sweep is reproducible without rebuilding:

```
adb shell "run-as vn.dungxnd.vbookmt sh -c 'echo qnn_gpu > files/onnx-provider'"   # or cpu / qnn / xnnpack
```

### Bugs this hunt exposed and fixed

- **The app reported `QNN` while running on CPU.** `applyProvider` only catches a throw at `addQnn`
  time; QNN fails later in `GetCapability` and ORT silently continues on CPU. `probeProvider` now
  detects it. Every earlier "QNN" number in this project was CPU.
- **Model cards did not switch the engine.** `onSelectModel` only updated Compose state; the running
  service kept the old model forever. It now pushes `EXTRA_ACTIVE_MODEL` to the service.
- **`Files.readString` is API 33+, `minSdk` is 26** — ONNX could never load on Android < 13. Now
  `readAllBytes`.
- **`CMakeLists.txt` had one `../` too many**, resolving to the workspace root instead of the repo.
- **`build_android_native.sh` rejected a successfully built `protoc.exe`** (the `find` pattern missed
  the `.exe` suffix) and rebuilt protoc unconditionally, which fails without MSVC on PATH.

### Still open

- Whether a firmware-matched HTP build would beat CPU — untestable without controlling the device image.
- Whether QNN GPU would win on a larger model. The 30-QT is small enough that launch overhead dominates.

Ran the whole stack on a real OnePlus PKG110 (Snapdragon 8 Gen 3, SM8650, HTP v75). **QNN does not
work, and the reason is not fixable in this repository.**

### What happened

```
qnn_execution_provider.cc: GetCapability
  QNN SetupBackend failed
  Failed to create device. Error: QNN_DEVICE_ERROR_INVALID_CONFIG: Invalid config values
```

The cause is a **QNN SDK generation mismatch**:

| | version |
| :--- | :--- |
| `qnn-runtime` bundled by `onnxruntime-android-qnn:1.22.0` | **2.33.0** |
| QNN libs the phone ships in `/odm/lib64` (DSP firmware) | **2.13.1** |

A QNN session has a host half and a device half and they must be the same SDK. The bundled host
library cannot drive the phone's older DSP firmware. Nothing in the provider's option map bridges
that — `vtcm_mb`, `htp_performance_mode`, `soc_model`, `htp_arch` were all tried or considered, and
they change tuning, not compatibility.

The alternative — shipping the *device's* QNN libs instead — is worse: `/odm/lib64` is bootloader- and
firmware-versioned per OEM and per model, so bundling it makes the APK correct on exactly one phone.

### The bug this exposed, which does matter

The app **reported `QNN (NPU/GPU)` on every session while executing on CPU.** `applyProvider` only
catches a throw at `addQnn` time; QNN fails later, in the first `GetCapability`, and ORT keeps going on
CPU. Fixed in `OrtOnnxModel.probeProvider`, which opens a throwaway session with
`session.disable_cpu_ep_fallback` so the downgrade becomes an exception, and reports
`on CPU (requested QNN (NPU/GPU): …)` instead. **Every earlier "QNN" number in this project was CPU**;
that is now impossible to repeat silently.

### Measured on the device, all CPU

59 source chars, best of 3, over the real gRPC-Web endpoint:

| engine | best wall | chars/s |
| :--- | ---: | ---: |
| CT2 hachimi-60 (default, `int8_float32`) | 322 ms | **183** |
| ONNX moxhi-30-q8 | 477 ms | 124 |

So on this hardware the shipping default is **faster than the ONNX path**, and the ONNX path's whole
justification — NPU offload — is unavailable. Note the comparison is imperfect anyway: 60-QT vs 30-QT.

### Conclusion, and what it means for AD-17

**NPU acceleration cannot be a promised capability of this app.** It depends on a bootloader, DSP
firmware and a QNN SDK version the app does not control, varying per OEM and model. Treating it as a
feature means shipping something that silently degrades to CPU on most devices — which is the current
behaviour and was, until now, invisible.

The honest options, in order of value:

1. **Keep CT2 CPU as the product default** (as shipped) and treat ONNX as CPU-only on Android. The ONNX
   engine still earns its place for the trimmed-vocabulary, fp16, KV-cached graphs — but not for speed.
2. **Keep the QNN request and the honest reporting.** On a device whose firmware matches the bundled
   SDK it will engage; everywhere else the UI says CPU. This is what the code does now.
3. **Do not chase HTP further** without a way to pin the QNN SDK to the target device fleet — that is a
   supply-chain decision, not an engineering one.

### Still open

- The `QnnHtpV*Stub.so` set shipped by `qnn-runtime` covers V68/V69/V73/V75/V79, but the *host* library
  version is what mismatched. Pinning `onnxruntime-android-qnn` to a release built against QNN 2.13.x
  might work on this specific phone; it was not attempted, and it would pin the app to one firmware
  generation.
- Untested: whether a newer OnePlus firmware would bring its DSP forward to 2.33.0.
- The DirectML desktop numbers in BL-1's sibling investigation are unaffected by this.

---

### What is NOT established → now resolved: the energy harness

**Found and fixed.** Instrumented every stage with `System.nanoTime()` and read it back on a cache-hit
request:

```
scope=chapter  in=27 chunks=1 out=97
  sanitize=0.15  chunker_plan=0.13  translateChunks=0.68  reassemble=0.18  log_done=0.33
  energy_begin=101.18   energy_end=107.10        ← 208 ms of the 210 ms total
```

Every real stage is sub-millisecond. **`EnergyHarness` was 99% of the request.** It samples every
thermal zone in `/sys/class/thermal`, the battery sensor and two `BatteryManager` properties — and it
did that **twice per chapter**, once in `begin` and once in `end`. On a chapter answered entirely from
the memo it measured nothing at all, while costing ~200 ms.

Fix (`ChapterTranslator.kt`, `ChunkTranslator.kt`, `EnergyHarness.kt`):

- `ChunkTranslator` now reports `lastBatchWasFullyCached` when the memo answered every chunk.
- `ChapterTranslator` opens the energy window only when inference actually ran, and passes the real
  start timestamp to `EnergyHarness.begin(..., startedAtMs)` so the window still covers the engine work
  that already happened.
- The harness still records normally for real translations — verified in logcat:
  `Năng lượng 1/6: 220 ms, 441 ký tự/giây ...`.

Measured effect, same device, same input:

| | before | after |
| :--- | ---: | ---: |
| cache-hit request | ~425 ms | **19 – 28 ms** |
| fresh translation (27 chars) | ~476 ms | 28 – 39 ms |

**~15x faster on repeats, ~12x on short fresh requests.** No product behaviour changed: the memo, the
engine choice and the output are identical; only the pointless sensor sampling was removed.

---

## BL-8 — The cache is not the bottleneck, and barely helps (measured 2026-11-01)

Asked whether the translation cache is costing speed. Answer: **it costs ~7% on unique text and saves
~24% on repeats — but both are dwarfed by a fixed ~425 ms per request that has nothing to do with the
cache or the engine.**

### Cache overhead is real but small

Same unique workload, `cacheEnabled` toggled, 7 runs each, cache can never hit:

| | median | chars/s |
| :--- | ---: | ---: |
| cache ON | 637 ms | 1684 |
| cache OFF | 593 ms | 1809 |

**−7.4% for having the cache at all.** Consistent with its cost: per chunk it builds a key that copies
the whole text (`TranslationCache.key` concatenates model + beams + text into a new String), takes a
global monitor, and on the way out appends to a journal file **synchronously under a lock**
(`CacheStore.append` → `File.appendText`). Per-group disk I/O on the decode path.

### Cache benefit is real but small

Same text repeated, cache hits confirmed via `/health` counters (hits climb 42→44→46, misses flat):

| | median |
| :--- | ---: |
| cache OFF (re-translate) | 633 ms |
| cache ON (hit) | 481 ms |

~24% saved on a repeat. Worth having, but not transformative.

### The actual finding: a fixed ~425 ms per request

| operation | median |
| :--- | ---: |
| `Health` RPC (no translation at all) | 89 ms |
| `Translate`, ~12-char input, cache HIT | 425 ms |
| `Translate`, ~380-char input, cache HIT | 504 ms |
| `Translate`, ~12-char input, cache OFF | 476 ms |

A **12-character** cache hit costs 425 ms. The content is doing almost nothing — 425 ms is fixed cost.
Doubling to a 380-char hit adds only ~80 ms. That fixed cost is the thing worth attacking, and the
cache is not it.

`Health` at 89 ms also says the HTTP/loopback path itself is not free, but most of the 425 ms is
inside the request handler, before or around the engine.

### What is NOT established

Where those 425 ms go. Candidates, none measured yet:

- `ChunkTranslator.translateChunks` per-call setup: `ChapterChunker`/`ChunkResolver` planning, the
  Sino-Vietnamese classifier, `tokenCount` per chunk.
- `EnergyHarness.begin` / staging logs.
- `OnnxTranslator` session work that runs even when the memo answers (a warm-up pass, mask building).
- The performance tuner or a state flow re-emitting.

Attacking any of these before measuring which one it is would repeat the mistake this document already
records twice: optimising something that turned out not to be the cost.

### Recommendation

1. **Profile a cache-hit request** to find the 425 ms. That is the only number here big enough to
   matter — it is ~90% of a small request's latency.
2. Then decide on the cache. At −7% overhead it is defensible as-is; if profiling shows the journal
   append is a meaningful share, make the write asynchronous (it is already outside the memo monitor,
   so the ordering constraint is already handled) or batch it.

---

## BL-7 — q8 vs fp16 on CPU: q8 is smaller AND lighter (measured 2026-11-01)

"Does q8 cost more RAM or CPU?" — the intuitive answer (quantization needs dequantize work, so it
should burn more CPU) is **wrong on this evidence**. Same model, same device, same workload
(10 chapters, ~4k source chars each), cache off, repeated:

| | fp16 | q8 | delta |
| :--- | ---: | ---: | :--- |
| disk | 140 MB | **86.6 MB** | −38% |
| TOTAL PSS after load | 374 MB | **321 MB** | −53 MB |
| TOTAL PSS after workload | 395 – 412 MB | **350 – 371 MB** | −40 to −50 MB |
| Native heap after workload | 253 – 254 MB | **200 MB** | −54 MB |
| CPU per source char | 1.71 – 1.75 ms | **1.21 – 1.44 ms** | −20 to −30% |
| cores busy during decode | 2.71 | 2.71 | — |
| throughput | 1581 – 1608 chars/s | 1757 – 2244 chars/s | +10 to +40% |

**q8 wins on every axis except output quality.** That is not a contradiction: both graphs are
converted to fp32 internally by the CPU EP, so the dequantize cost is the same, while the weights
*sit* as int8 — half the bytes to load from RAM, half the memory bandwidth per weight read. Decode is
bandwidth-bound, so reading fewer bytes is a straight win. It is the same reason the trimmed-vocabulary
experiment worked.

The cost is accuracy: q8 measured **chrF 96.00 vs fp16's 100.00** against the fp32 source. So the
real trade is *40 MB of RAM + ~25% CPU + ~25% speed* against *~4 chrF points*.

### What this changes

- The earlier note in this file saying q8-vs-fp16 is "a wash on CPU speed" was measured on a thermally
  noisy run and **understates q8**. Repeated runs put q8 clearly ahead.
- A phone is the case where this matters most: 50 MB less resident memory and 25% less CPU is real
  battery and thermal headroom, on a device that throttles.
- `q16` was not compared here; it is 119.6 MB and matched fp16's output exactly, so it sits between
  the two on both size and speed.

**Caveat:** these are two runs per model on one device, and the phone throttles. The *direction* is
consistent across every repeat, but the exact percentages should be re-measured before being quoted
as specification.

---

## Done — BL-2: static QDQ builds exist and beat fp16 on both axes (2026-11-01)
`scripts/build_qdq.py` produces static QDQ graphs for QNN via `get_qnn_qdq_config`. Measured on
`moxhi-30-qt`, scored against its own **fp32** source (not against fp16 — scoring two builds against
each other says how far apart they drifted, not which is closer to the model):

| build | size | vs fp16 | chrF vs fp32 | CPU chars/s (noisy) |
| :--- | ---: | ---: | ---: | ---: |
| `moxhi-30-qt` (shipped fp16) | 140.0 MB | 100 % | 100.00 | 775 – 1015 |
| `moxhi-30-qt-q8` (uint8 act) | **86.6 MB** | **62 %** | **96.00** | 690 – 733 |
| `moxhi-30-qt-q16` (uint16 act) | 119.6 MB | 85 % | **100.00** | ~430 |
| `moxhi-30-qt-fp32` (reference) | 279.0 MB | — | — | — |

**Treat the throughput column as a range, not a measurement.** Repeat runs of the *same* build varied
775 → 1015 chars/s (5-unit corpus, `CPUExecutionProvider`), so this harness is good for ranking
quality and for a rough speed sanity check, and not good enough to decide a close speed question —
especially not the q8-vs-q16 one below. chrF reproduced exactly across rebuilds (100.00 / 96.00), so
the quality column is solid.

All builds pass `onnx.checker`, and all are genuinely static (`MatMulInteger`/`DynamicQuantizeLinear`
count is 0) — unlike the old `exported_models/*/int8/` artifacts.

### The finding that mattered: quantize-everything is catastrophic

The first build quantized every op and scored **chrF 9.57** — fluent-looking Vietnamese assembled from
the wrong words. The cause is structural: `logits` is `Add(lm_head/MatMul, final_logits_bias)`, so an
8-bit round trip there makes `argmax` over 5,951 entries pick the wrong token. Excluding three classes
recovered it to 96.00:

- **any node writing a graph output** — `last_hidden_state`, `logits`, and the eight `present.*` cache
  tensors. The caches feed straight back in as `past_key_values.*` next step, so 8-bit error there
  compounds across the whole decode;
- **`lm_head`** specifically (it only produces an intermediate, so the output rule misses it);
- **`LayerNormalization` / `Softmax` by op type** — normalization statistics do not survive 8-bit, and
  these are also the rank-1 weights that make ORT warn "Axis 1 is out-of-range" under per-channel.

### Two things that block the obvious approach

- **fp16 graphs cannot be quantized directly.** ORT's static quantizer writes float32 scale
  initializers beside fp16 weights and emits an invalid model (`Type 'tensor(float16)' of input
  parameter (...._scale) of operator (DequantizeLinear) is invalid`). No fp16→fp32 upconverter ships
  with `onnxconverter-common`, so `build_trimmed.py` gained `--keep-fp32` and the QDQ build starts
  from those.
- **Calibration must be real decode traffic.** `decoder_with_past` only ever sees cache tensors, so
  calibrating it against zeros would set activation ranges from a distribution the model never sees.
  The script runs a greedy decode of the corpus on the fp32 graphs and samples caches at steps
  1/3/6/12.

### What this does and does not establish

- **Established:** static QDQ is viable here, and the AD-17 claim that quantized inference "lost
  accuracy" was the dynamic-quantization confound, not QDQ. `q16` reproduces fp16 output *exactly*
  (chrF 100.00) at 85 % of the size.
- **Not established:** nothing here ran on QNN. All numbers are `CPUExecutionProvider`, and QDQ on CPU
  is not the intended win — CPU already has `int8_float32` in CTranslate2. The NPU claim is still
  unmeasured, and must be checked with `disable_cpu_ep_fallback` so an unsupported node throws.
- **Open question for `q16`:** weights are capped at 8-bit (`MatMul` weight handling in
  `quant_config.py` returns early outside `Q8_TYPES`), so `q16` is 16-bit *activations* only. It
  measured slower than `q8` on CPU, but the run-to-run spread above is large enough that the *ordering*
  is the only defensible reading — re-measure on HTP, where it may invert.
- **Not built for moxhi's sibling yet.** Same command with `--name hachimi-60-qt`; skipped to keep the
  sweep to the model with a full fp32 export on hand.

---

## BL-4 — Accuracy harness *(done 2026-11-01)*

VBookMT had **no accuracy measurement at all**. `scripts/verify_onnx_parity.py` compares 6 sentences
for *equality* against fp32 `generate` — good for catching a broken cache or a bad vocabulary trim, but
it cannot resolve a small quality difference, and a dtype change is precisely a small quality
difference. Without a yardstick, "int8 lost accuracy" is unfalsifiable.

`scripts/eval_onnx_quality.py` closes that. **Self-contained in this repository**: the two shipped
builds under `build/onnx_trimmed/` (`hachimi-60-qt`, `moxhi-30-qt`), each artifact's own
`source.spm`/`target.spm`, and only `onnxruntime` + `sentencepiece` — both of which the app already
uses. Nothing outside VBookMT is read or imported.

- **chrF** implemented in-file rather than vendored, so there is no cross-project dependency. Character
  orders 1..6, beta=2, clipped counts, corpus-level aggregation (not the mean of sentence scores).
  Whitespace is preserved and normalised — unlike sacrebleu's default, which removes it. That default
  is right for Chinese but wrong for Vietnamese, where `học sinh` and `họcsinh` are different strings.
- **Corpus** defaults to `PerformanceTuner.SAMPLE` — the same five wuxia paragraphs the app already
  benchmarks on, which is the genre this product exists for.
- **Two modes.** A/B two build directories (no references needed), or `--corpus refs.jsonl` with
  `zh`/`vi_ref` per line to score quality.
- **Decoding mirrors the shipping loop**, including dropping rows at EOS, so numbers describe what the
  app does rather than an idealized decode. Reports the execution provider next to every number.

Verified both directions: two different models score **chrF 90.43, 5/5 units differing**, and the same
model twice scores **chrF 100.00, 0/5 differing** — so a "no drift" result from a future dtype
comparison means something.

Sample run (both shipped fp16 models, CPU):

| model | provider | throughput |
| :--- | :--- | ---: |
| hachimi-60-qt | CPUExecutionProvider | 208 chars/s |
| moxhi-30-qt | CPUExecutionProvider | 506 chars/s |

**A-vs-B chrF is a disagreement score, not a quality score** — it says how far two builds drift, which
is the right signal for "did quantization change the output" and the wrong one for "is this
translation good". The report labels which is which.

---

## BL-5 — Narrow AD-17's wording

AD-17 currently implies int8 was evaluated and rejected. Suggested replacement: *"dynamic int8 (CPU,
untrimmed, cache-less) produced worse output; static QDQ for QNN and weight-only int8 for CPU remain
untested."* Otherwise the next reader takes a settled conclusion where there is an open hypothesis.

---

## Suggested order

1. ~~**BL-3 (1)**~~ — **done**, −30.4% measured. ~~**(3)**~~ — **retracted, not a bug**.
2. ~~**BL-4**~~ — **done**: `scripts/eval_onnx_quality.py`, self-contained, both shipped models.
3. **BL-2** — QDQ build, two variants (`QUInt8` and `QUInt16` activations), validated with
   `disable_cpu_ep_fallback`. Keep fp16 shipping until one beats it on chrF **and** tok/s on real
   hardware. **This is now judgeable** — run the new harness against the candidate directory.
4. **BL-2 (fallback)** — weight-only int8 as the desktop-CPU variant, where fp16 is weakest.
5. **BL-5** — doc correction. *(Not started.)*
6. **BL-3 (2)** — token-budget batching on the ONNX path. Lower value now that rows leave the batch.

---

## Done — `scripts/` kept out of publishing (2026-11-01)

`scripts/` holds the build and release tooling (native builds, ONNX export, packaging). It has to stay
in the repo because every CI workflow drives it — `release.yml`, `ci.yml`, `native.yml` all invoke
`scripts/build_android_native.sh`, `scripts/build_windows_native.ps1`, `scripts/package_extension.py`
and friends. Nothing under it could be deleted.

It was already absent from every actual release artifact (the APK, the Windows zip and both extension
zips only assemble `vbook-extension/`, `apps/`, and app resources). What was still open:

- `.repomixignore` now excludes `scripts/`, so packed/exported output does not carry it either.
- `scripts/verify_extension.py` gained a guard (`_is_tooling_path`) that fails if a shipped archive
  contains a root-level `scripts/` or `.github/`, a `__pycache__`, or any `.py`/`.pyc`/`.sh`/`.ps1`.
  Deliberately only *root-level* directory names are condemned — a nested `src/scripts/loader.js` is
  ordinary extension source, and the first version of this check wrongly flagged exactly that.

## Unverified / open

- No MarianMT-specific int8-vs-fp16 chrF/BLEU benchmark exists publicly. The "0.3–1.5 BLEU" figure in
  circulation is extrapolated from BERT-class W8A8 results — not a number for this model.
- QNN docs self-contradict on whether HTP accepts a pure-fp16 model ("HTP only supports quantized
  models") vs `enable_htp_fp16_precision` defaulting to 1. **Probe it** — this decides whether the
  current fp16 artifact runs on HTP at all.
- No GPU/NPU latency has ever been measured on real hardware here (AD-17 says so). int8-vs-fp16 *speed*
  is unmeasured in both directions.
- BL-1's "biased error compounding across decode steps" is reasoning, not measurement.
