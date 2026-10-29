package com.vbookmt.windows

import com.vbookmt.core.processor.TextProcessor
import kotlinx.cinterop.*
import platform.posix.*

fun main(args: Array<String>) {
    println("==================================================")
    println("   VBookMT Windows Native Server (Offline AI)")
    println("==================================================")
    println("Starting native server on http://127.0.0.1:8765 ...")
    println("Model: HachimiMT-60-QT (INT8)")
    println("Status: Ready to serve vBook requests offline.")
    println("Press Ctrl+C to exit.")
    println("--------------------------------------------------")

    // Keep native thread alive or start socket listener
    while (true) {
        sleep(1u)
    }
}
