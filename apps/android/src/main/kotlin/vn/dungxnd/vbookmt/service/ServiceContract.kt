package vn.dungxnd.vbookmt.service

/**
 * Constants shared by the foreground service, its HTTP layer and the UI.
 *
 * Keeping them in one place means the UI never has to import the `Service` class just to build an
 * Intent, and the routes do not reach into service internals for a port number.
 */
object ServiceContract {

    const val ACTION_START = "vn.dungxnd.vbookmt.ACTION_START"
    const val ACTION_STOP = "vn.dungxnd.vbookmt.ACTION_STOP"
    const val EXTRA_ACTIVE_MODEL = "EXTRA_ACTIVE_MODEL"

    const val CHANNEL_ID = "vbookmt_service_channel"
    const val NOTIFICATION_ID = 8765

    const val PORT = 8765

    /** Loopback only: the extension talks to the same device. */
    const val HOST = "127.0.0.1"

    /** Hard cap on one request body (OOM / DoS guard). The client-side limit is the extension's own `timeout_seconds`. */
    const val MAX_INPUT_CHARS = 50_000

    /**
     * Ceiling for actual translation work; queue wait is deliberately excluded.
     *
     * Keep this *below* the extension's own `timeout_seconds` (default 120 s). If the client gives up
     * first it retries a chapter the server is still working on, so the reader gets a duplicate
     * request plus a QuickTranslator fallback, while the answer they wanted arrives seconds later to
     * nobody. Ten seconds of headroom lets the server return a real 504 with an explanation instead.
     */
    const val TRANSLATE_TIMEOUT_MS = 110_000L

    /** Log when a request sat waiting for the translation gate this long. */
    const val QUEUE_WARN_MS = 2_000L
}
