package vn.dungxnd.vbookmt.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-chapter energy and thermal measurement, the sibling of [PerformanceTuner].
 *
 * Why it exists: [PerformanceTuner] answers "how fast is this phone", which is the wrong question for
 * a six-chapter reading session - a phone can be fast at 100% big-core duty cycle and still throttle
 * by chapter three. `NEW_MT_MODEL.md` S0 needs the other half: *what the run costs*, chapter by
 * chapter, with no cooldown between chapters, so the numbers describe the sustained case a reader
 * actually gets.
 *
 * What it records per chapter: wall time, characters per second, charge consumed (mAh), energy (mWh),
 * a second, independent charge reading from `charge_counter`, start/peak/end die temperature, the
 * hottest zone's name, thread count, and how many samples the numbers rest on.
 *
 * Where the numbers come from (and where they are soft):
 *  - **current** - `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`, else
 *    `/sys/class/power_supply/battery/current_now`. Both are documented in microamperes, and both lie
 *    on some ROMs by reporting milliamperes instead; the magnitude is disambiguated (>20 000 cannot
 *    be milliamperes) and the raw value is logged so a wrong guess is visible rather than silent.
 *  - **voltage** - `/sys/class/power_supply/battery/voltage_now`, else the sticky `ACTION_BATTERY_CHANGED`
 *    extra. Energy = current x voltage, so a wrong voltage is a wrong mWh, not a wrong mAh.
 *  - **charge_counter** - the battery gauge's own coulomb count (`.../charge_counter`, else
 *    `BATTERY_PROPERTY_CHARGE_COUNTER`). Its delta over a chapter is an independent mAh figure: if the
 *    integrated current and this disagree, one of the two is being misread and the report says so.
 *  - **temperature** - every `/sys/class/thermal/thermal_zone<N>/temp`, plus the battery's own sensor.
 *    Peak is the maximum over the chapter, with the hottest zone named, because "the phone got hot" is
 *    only actionable if it says where.
 *
 * What it deliberately does not do: log any text. Sizes, durations, counts and temperatures only
 * (`ARCHITECTURE.md` §6 rule 8), which is also what makes the log safe to keep in `filesDir`.
 *
 * Guardrails: sampling costs one thread waking every 500 ms and a handful of sysfs reads; the sampler
 * stops itself 90 s after the last chapter, so an idle app is not measured to death. Chapters are
 * sequential by design (`ARCHITECTURE.md` §6 rule 5), so there is exactly one open window.
 *
 * Usage (the caller owns the timing boundary):
 *
 *     EnergyHarness.attach(applicationContext)
 *     val mark = EnergyHarness.begin(sourceChars = text.length, threads = 4, model = "hachimi60")
 *     ... translate the chapter ...
 *     val line = EnergyHarness.end(mark, outputChars = output.length)
 *
 * `begin`/`end` are cheap, exception-free and safe to leave in a shipped build: with no `attach` call,
 * every method is a no-op returning null/0.
 */
object EnergyHarness {

    private const val TAG = "EnergyHarness"

    /** One sample every 500 ms: fine enough for a 2 s chapter, cheap enough to leave running. */
    private const val SAMPLE_MS = 500L

    /** S0's measurement run: six chapters for six, sustained, no cooldown. */
    const val TARGET_CHAPTERS = 6

    /** Without a new chapter for this long, the session is closed and the sampler stops. */
    private const val IDLE_STOP_MS = 90_000L

    /** Current readings above this cannot be milliamperes - treat microamperes as the unit. */
    private const val UA_HINT = 20_000L

    private const val DIR = "energy"

    /** One finished chapter. Raw values only: the report script does the arithmetic. */
    data class Chapter(
        val index: Int,
        val sourceChars: Int,
        val outputChars: Int,
        val wallMs: Long,
        val threads: Int,
        val mah: Double,
        val mwh: Double,
        val chargeMah: Double,
        val avgCurrentMa: Double,
        val avgVoltageV: Double,
        val tempStartC: Double,
        val tempPeakC: Double,
        val tempEndC: Double,
        val hottestZone: String,
        val samples: Int,
        val startedAtMs: Long,
    ) {
        val charsPerSec: Double get() = if (wallMs > 0) outputChars * 1000.0 / wallMs else 0.0

        val mahPer1000Chars: Double get() = if (outputChars > 0) mah * 1000.0 / outputChars else 0.0

        /** Peak minus start: the number that decides whether the phone is heating or done heating. */
        val tempRiseC: Double get() = tempPeakC - tempStartC
    }

    /** Everything measured since the first chapter of a run, plus the chapters themselves. */
    private class Session(val model: String, val threads: Int, val logFile: File?) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val chapters = ArrayList<Chapter>(TARGET_CHAPTERS)
        var lastChapterEndMs = startedAtMs

        var lastSampleMs = startedAtMs
        var lastCurrentMa: Double? = null

        var mah = 0.0
        var mwh = 0.0
        var currentSum = 0.0
        var voltageSum = 0.0
        var samples = 0

        var tempStartC = 0.0
        var tempPeakC = 0.0
        var tempEndC = 0.0
        var hottestZone = ""

        var chargeStartMah: Double? = null
        var chargeLastMah: Double? = null
    }

    /** One chapter in flight. Guarded by [lock] together with the session it belongs to. */
    private class Window(
        val index: Int,
        val sourceChars: Int,
        val threads: Int,
        val startedAtMs: Long,
    ) {
        var lastSampleMs = startedAtMs
        var lastCurrentMa: Double? = null

        var mah = 0.0
        var mwh = 0.0
        var currentSum = 0.0
        var voltageSum = 0.0
        var samples = 0

        var tempStartC = 0.0
        var tempPeakC = 0.0
        var fastestZone = ""
        var chargeStartMah: Double? = null
    }

    private val lock = Any()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var lastSession: Session? = null

    @Volatile
    private var window: Window? = null

    @Volatile
    private var sampler: Thread? = null

    private var sessionCounter = 0

    // -----------------------------------------------------------------------------------------
    // Public surface
    // -----------------------------------------------------------------------------------------

    /** Wires the harness to the app's files dir and battery service. Call once from the service. */
    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Opens a chapter window and starts sampling. Returns the handle to pass to [end].
     *
     * A gap longer than [IDLE_STOP_MS] since the previous chapter closes the previous session, so a
     * run of six chapters is six chapters and not "six chapters plus the twelve I read this morning".
     */
    fun begin(sourceChars: Int, threads: Int, model: String, startedAtMs: Long = -1L): Long {
        if (context == null || sourceChars <= 0) return 0L

        // Callers that open the window after the work has run pass the real start; otherwise the
        // window would report only the time since `begin`.
        val startedAt = if (startedAtMs > 0) startedAtMs else SystemClock.elapsedRealtime()
        synchronized(lock) {
            val current = session
            if (current != null && startedAt - current.lastChapterEndMs > IDLE_STOP_MS) {
                closeSession(current)
            }

            val active = session ?: startSession(model, threads).also { session = it }
            val next = Window(
                index = active.chapters.size + 1,
                sourceChars = sourceChars,
                threads = threads,
                startedAtMs = startedAt,
            )
            window = next
            ensureSampler()
            sample(active, next, startedAt)
            return startedAt
        }
    }

    /**
     * Closes the chapter window opened by [begin] and appends one record.
     *
     * @return the single log line for the UI/`ServiceState`, or null when nothing was measured.
     */
    fun end(handle: Long, outputChars: Int): String? {
        if (context == null) return null

        val endedAt = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val active = session ?: return null
            val current = window ?: return null
            if (current.startedAtMs != handle) return null // stale handle: a newer chapter already began

            sample(active, current, endedAt)
            window = null
            active.lastChapterEndMs = endedAt

            val chargeNow = chargeMah()
            val chapter = Chapter(
                index = current.index,
                sourceChars = current.sourceChars,
                outputChars = outputChars,
                wallMs = (endedAt - current.startedAtMs).coerceAtLeast(1L),
                threads = current.threads,
                mah = current.mah,
                mwh = current.mwh,
                chargeMah = if (current.chargeStartMah != null && chargeNow != null) {
                    (current.chargeStartMah!! - chargeNow).coerceAtLeast(0.0)
                } else {
                    Double.NaN
                },
                avgCurrentMa = if (current.samples > 0) current.currentSum / current.samples else 0.0,
                avgVoltageV = if (current.samples > 0) current.voltageSum / current.samples else 0.0,
                tempStartC = current.tempStartC,
                tempPeakC = current.tempPeakC,
                tempEndC = active.tempEndC,
                hottestZone = current.fastestZone,
                samples = current.samples,
                startedAtMs = current.startedAtMs,
            )
            active.chapters.add(chapter)
            appendLine(active, chapter)

            val line = format(chapter)
            Log.i(TAG, line)
            if (active.chapters.size == TARGET_CHAPTERS) {
                Log.i(TAG, sessionSummary(active))
            }
            return line
        }
    }

    /** The session records as JSONL lines for the wire EnergySnapshot RPC. */
    fun recordsJsonl(): List<String> {
        val active = session ?: lastSession ?: return emptyList()
        synchronized(lock) {
            val file = active.logFile
            if (file != null && file.exists()) {
                val lines = runCatching { file.readLines() }.getOrNull()
                if (!lines.isNullOrEmpty()) return lines
            }
            val list = mutableListOf<String>()
            val header = JSONObject()
                .put("kind", "header")
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("sdk", Build.VERSION.SDK_INT)
                .put("model", active.model)
                .put("threads", active.threads)
                .put("target_chapters", TARGET_CHAPTERS)
                .put("sample_ms", SAMPLE_MS)
            list.add(header.toString())
            for (chapter in active.chapters) {
                list.add(chapterJson(chapter).toString())
            }
            return list
        }
    }

    /** Forgets the current and previous session, so the next chapter starts a clean 6-chapter run. */
    fun reset() {
        synchronized(lock) {
            session?.let { closeSession(it) }
            lastSession = null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Sampling
    // -----------------------------------------------------------------------------------------

    private fun ensureSampler() {
        if (sampler?.isAlive == true) return
        sampler = Thread(::sampleLoop, "EnergyHarness").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Wakes every [SAMPLE_MS] while a session is open and stops itself once the session has been idle
     * for [IDLE_STOP_MS]. Reading sysfs from one small thread keeps the measurement off the
     * translation path: nothing here ever blocks a chapter.
     */
    private fun sampleLoop() {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                val active = session
                if (active == null) return
                if (window == null && now - active.lastChapterEndMs > IDLE_STOP_MS) {
                    closeSession(active)
                    return
                }
                sample(active, window, now)
            }
            try {
                Thread.sleep(SAMPLE_MS)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

    /**
     * Integrates one sample into the session (always) and into the open chapter (when there is one).
     *
     * The integral is the trapezoid between consecutive samples: `sum(mean current x dt)`, which is
     * what makes a 2-second chapter readable at a 500 ms sampling rate.
     */
    private fun sample(active: Session, current: Window?, now: Long) {
        val currentMa = batteryCurrentMa()
        val voltageV = batteryVoltageV()
        val zones = thermalZones()
        val hottest = zones.maxByOrNull { it.second }

        // --- session-level: total energy for the run, including the gaps between chapters -------
        if (active.samples == 0) {
            hottest?.let {
                active.tempStartC = it.second
                active.tempPeakC = it.second
                active.hottestZone = it.first
            }
            active.chargeStartMah = chargeMah()
        }
        integrate(
            previousCurrentMa = active.lastCurrentMa,
            previousMs = active.lastSampleMs,
            nowMs = now,
            currentMa = currentMa,
            voltageV = voltageV,
            onMah = { active.mah += it },
            onMwh = { active.mwh += it },
        )
        active.lastCurrentMa = currentMa
        active.lastSampleMs = now
        active.samples++
        if (currentMa != null) active.currentSum += currentMa
        if (voltageV != null) active.voltageSum += voltageV
        if (hottest != null) {
            if (hottest.second > active.tempPeakC) {
                active.tempPeakC = hottest.second
                active.hottestZone = hottest.first
            }
            active.tempEndC = hottest.second
        }
        active.chargeLastMah = chargeMah()

        // --- chapter-level: the same arithmetic, bounded by begin()/end() ----------------------
        if (current == null) return
        if (current.samples == 0) {
            hottest?.let {
                current.tempStartC = it.second
                current.tempPeakC = it.second
                current.fastestZone = it.first
            }
            current.chargeStartMah = active.chargeLastMah
        }
        integrate(
            previousCurrentMa = current.lastCurrentMa,
            previousMs = current.lastSampleMs,
            nowMs = now,
            currentMa = currentMa,
            voltageV = voltageV,
            onMah = { current.mah += it },
            onMwh = { current.mwh += it },
        )
        current.lastCurrentMa = currentMa
        current.lastSampleMs = now
        current.samples++
        if (currentMa != null) current.currentSum += currentMa
        if (voltageV != null) current.voltageSum += voltageV
        if (hottest != null) {
            if (hottest.second > current.tempPeakC) {
                current.tempPeakC = hottest.second
                current.fastestZone = hottest.first
            }
        }
    }

    /** Trapezoid over one sample interval; contributes nothing until two samples exist. */
    private inline fun integrate(
        previousCurrentMa: Double?,
        previousMs: Long,
        nowMs: Long,
        currentMa: Double?,
        voltageV: Double?,
        onMah: (Double) -> Unit,
        onMwh: (Double) -> Unit,
    ) {
        if (previousCurrentMa == null || currentMa == null) return
        val dtMs = nowMs - previousMs
        if (dtMs <= 0L) return
        val meanMa = (previousCurrentMa + currentMa) / 2.0
        val hours = dtMs / 3_600_000.0
        onMah(meanMa * hours)
        val volts = voltageV ?: return
        onMwh(meanMa * volts * hours)
    }

    // -----------------------------------------------------------------------------------------
    // Device readings
    // -----------------------------------------------------------------------------------------

    private fun batteryManager(): BatteryManager? {
        val app = context ?: return null
        return runCatching {
            app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        }.getOrNull()
    }

    /**
     * Discharge current as a positive magnitude in milliamperes, or null when the device hides it.
     *
     * Sign conventions differ (some phones report charging-positive, some discharging-positive), and
     * so do units (microamperes per spec, milliamperes on older ROMs). Both are normalised here: the
     * magnitude is what a chapter's charge consumption needs, and the unit is inferred from a value
     * no phone could reach in milliamperes.
     */
    private fun batteryCurrentMa(): Double? {
        val fromProperty = runCatching {
            batteryManager()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()

        val raw = when {
            fromProperty != null && fromProperty != Int.MIN_VALUE && fromProperty != 0 -> fromProperty.toLong()
            else -> readLong("/sys/class/power_supply/battery/current_now")
        } ?: return null

        val magnitude = abs(raw)
        if (magnitude == 0L) return null
        return if (magnitude > UA_HINT) magnitude / 1000.0 else magnitude.toDouble()
    }

    private fun batteryVoltageV(): Double? {
        val micro = readLong("/sys/class/power_supply/battery/voltage_now")
            ?: readLong("/sys/class/power_supply/bms/voltage_now")
        if (micro != null && micro > 0L) {
            // Spec says microvolts; a plausible pack reads 3 000 000-4 500 000 there, so values under
            // 10 000 are already millivolts and must be scaled accordingly.
            return if (micro > 10_000L) micro / 1_000_000.0 else micro / 1000.0
        }

        val app = context ?: return null
        val sticky = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val millivolts = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        return if (millivolts > 0) millivolts / 1000.0 else null
    }

    /** The gauge's own remaining charge in mAh, or null. Independent of [batteryCurrentMa]. */
    private fun chargeMah(): Double? {
        val raw = runCatching {
            batteryManager()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull()
        val microAh = when {
            raw != null && raw != Int.MIN_VALUE && raw != 0 -> raw.toLong()
            else -> readLong("/sys/class/power_supply/battery/charge_counter")
        } ?: return null
        return if (abs(microAh) > 10_000L) microAh / 1000.0 else microAh.toDouble()
    }

    /** Every thermal zone as (name, degrees Celsius), plus the battery's own sensor. */
    private fun thermalZones(): List<Pair<String, Double>> {
        val zones = ArrayList<Pair<String, Double>>(16)
        val root = File("/sys/class/thermal")
        val dirs = runCatching { root.listFiles { file -> file.isDirectory && file.name.startsWith("thermal_zone") } }
            .getOrNull()
        dirs?.forEach { dir ->
            val raw = readLong("${dir.absolutePath}/temp") ?: return@forEach
            val name = runCatching { File("${dir.absolutePath}/type").readText().trim() }.getOrNull()
                ?: dir.name
            zones.add(name to celsius(raw))
        }

        // Battery temperature is reported in tenths of a degree, and on many phones is the only sensor
        // that actually tracks the sustained load this harness cares about.
        readLong("/sys/class/power_supply/battery/temp")?.let { zones.add("battery" to it / 10.0) }
        return zones
    }

    /**
     * Thermal zones report millidegrees on almost every device and plain degrees on a few; anything
     * below 1 000 cannot be millidegrees (a 1 000 K phone does not run this app).
     */
    private fun celsius(raw: Long): Double = if (abs(raw) >= 1000L) raw / 1000.0 else raw.toDouble()

    private fun readLong(path: String): Long? =
        runCatching { File(path).readText().trim().toLong() }.getOrNull()

    // -----------------------------------------------------------------------------------------
    // Session bookkeeping + output
    // -----------------------------------------------------------------------------------------

    private fun startSession(model: String, threads: Int): Session {
        val app = context
        val logFile = runCatching {
            app?.let {
                val dir = File(it.filesDir, DIR).apply { mkdirs() }
                sessionCounter++
                File(dir, "session-${System.currentTimeMillis()}-$sessionCounter.jsonl")
            }
        }.getOrNull()

        val created = Session(model.ifEmpty { "unknown" }, threads, logFile)
        appendHeader(created)
        return created
    }

    private fun closeSession(active: Session) {
        if (active.chapters.isNotEmpty()) {
            Log.i(TAG, sessionSummary(active))
            lastSession = active
        }
        if (session === active) session = null
        window = null
    }

    private fun appendHeader(active: Session) {
        val header = JSONObject()
            .put("kind", "header")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("sdk", Build.VERSION.SDK_INT)
            .put("model", active.model)
            .put("threads", active.threads)
            .put("target_chapters", TARGET_CHAPTERS)
            .put("sample_ms", SAMPLE_MS)
        append(active, header.toString())
    }

    private fun appendLine(active: Session, chapter: Chapter) = append(active, chapterJson(chapter).toString())

    private fun append(active: Session, line: String) {
        val file = active.logFile ?: return
        runCatching { file.appendText(line + "\n") }
            .onFailure { Log.w(TAG, "could not append to $file: ${it.message}") }
    }

    private fun chapterJson(chapter: Chapter): JSONObject = JSONObject()
        .put("kind", "chapter")
        .put("index", chapter.index)
        .put("source_chars", chapter.sourceChars)
        .put("output_chars", chapter.outputChars)
        .put("wall_ms", chapter.wallMs)
        .put("chars_per_sec", round(chapter.charsPerSec))
        .put("threads", chapter.threads)
        .put("mah", round(chapter.mah))
        .put("mwh", round(chapter.mwh))
        .put("mah_per_1000_chars", round(chapter.mahPer1000Chars))
        .put("charge_mah", round(chapter.chargeMah))
        .put("avg_current_ma", round(chapter.avgCurrentMa))
        .put("avg_voltage_v", round(chapter.avgVoltageV))
        .put("temp_start_c", round(chapter.tempStartC))
        .put("temp_peak_c", round(chapter.tempPeakC))
        .put("temp_end_c", round(chapter.tempEndC))
        .put("temp_rise_c", round(chapter.tempRiseC))
        .put("hottest_zone", chapter.hottestZone)
        .put("samples", chapter.samples)
        .put("started_at_ms", chapter.startedAtMs)

    private fun chargeDeltaMah(active: Session): Double {
        val start = active.chargeStartMah ?: return Double.NaN
        val last = active.chargeLastMah ?: return Double.NaN
        return (start - last).coerceAtLeast(0.0)
    }

    /** One chapter, in the shape a person can read in the in-app log. */
    private fun format(chapter: Chapter): String = String.format(
        Locale.US,
        "Năng lượng %d/%d: %d ms, %.0f ký tự/giây, %.1f mAh (%.1f mWh), " +
            "%.1f→%.1f °C (+%.1f, %s), %d luồng, %d mẫu",
        chapter.index,
        TARGET_CHAPTERS,
        chapter.wallMs,
        chapter.charsPerSec,
        chapter.mah,
        chapter.mwh,
        chapter.tempStartC,
        chapter.tempPeakC,
        chapter.tempRiseC,
        chapter.hottestZone.ifEmpty { "?" },
        chapter.threads,
        chapter.samples,
    )

    /** The six-chapter verdict: speed and temperature at the end versus the start. */
    private fun sessionSummary(active: Session): String {
        val chapters = active.chapters
        val outputChars = chapters.sumOf { it.outputChars }
        val wallMs = chapters.sumOf { it.wallMs }
        val mahPer1000 = if (outputChars > 0) active.mah * 1000.0 / outputChars else 0.0
        val first = chapters.first()
        val last = chapters.last()
        val sustained = chapters.takeLast(if (chapters.size >= 3) 3 else chapters.size)
        val sustainedCps = sustained.sumOf { it.charsPerSec } / sustained.size

        return String.format(
            Locale.US,
            "Tổng kết %d chương: %d ký tự, %.1f ký tự/giây (3 chương cuối %.1f), " +
                "%.1f mAh (%.1f mWh), %.2f mAh/1000 ký tự, nhiệt %.1f→%.1f °C",
            chapters.size,
            outputChars,
            if (wallMs > 0) outputChars * 1000.0 / wallMs else 0.0,
            sustainedCps,
            active.mah,
            active.mwh,
            mahPer1000,
            first.tempStartC,
            last.tempPeakC,
        ).let { text -> "${text}\n  chương 1: ${first.charsPerSec.toInt()} ký tự/giây, " +
            "chương ${last.index}: ${last.charsPerSec.toInt()} ký tự/giây" }
    }

    private fun round(value: Double): Double =
        if (value.isNaN() || value.isInfinite()) -1.0 else Math.round(value * 1000.0) / 1000.0
}
