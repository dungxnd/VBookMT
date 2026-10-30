package vn.dungxnd.vbookmt.service

import android.content.Context

/**
 * Holds the application context the translation layer needs to persist settings.
 *
 * Declared once by [TranslationService] on start so that [TranslationSettings] (reachable from the
 * HTTP layer) can save a tuning without holding a `Service` reference or leaking one. Application
 * context only - never an Activity.
 */
internal object ServiceContextHolder {

    @Volatile
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context =
        appContext ?: error("ServiceContextHolder used before TranslationService attached a context")
}
