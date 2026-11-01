package vn.dungxnd.vbookmt.service

/**
 * Process-level memory readings for `GET /health`.
 *
 * PSS is the figure that matters on a phone: the JVM heap alone read 16 MB while the real process
 * footprint was ~250 MB (model weights and activations live outside the Java heap).
 */
object Diagnostics {

    /** Whole-process proportional set size in MB, or -1 when the platform refuses to report it. */
    fun processPssMb(): Int = try {
        val info = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(info)
        info.totalPss / 1024
    } catch (_: Throwable) {
        -1
    }

    /** Live JVM heap in MB (the part the Kotlin side can actually cause to grow). */
    fun jvmHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }
}
