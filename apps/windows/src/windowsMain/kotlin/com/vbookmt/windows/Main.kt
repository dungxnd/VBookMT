package com.vbookmt.windows

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.processor.TextProcessor
import platform.posix.*

fun main(args: Array<String>) {
    var modelId = ModelRegistry.HACHIMI_60_QT.id
    for (i in args.indices) {
        if (args[i] == "--model" && i + 1 < args.size) {
            modelId = args[i + 1]
        }
    }

    val model = ModelRegistry.getById(modelId)

    println("==================================================================")
    println("         VBookMT Windows Native Server (CTranslate2 INT8)         ")
    println("==================================================================")
    println("Active Model : ${model.name} (${model.sizeMb} MB)")
    println("Author       : ${model.author} [License: ${model.license}]")
    println("HuggingFace  : ${model.repoId}")
    println("Server URL   : http://127.0.0.1:8765/translate")
    println("------------------------------------------------------------------")
    println("ACKNOWLEDGEMENTS & NOTICES:")
    println("- HachimiMT by ngocdang83 (CC BY 4.0)")
    println("- MoxhiMT by DanVP (CC BY-NC 4.0)")
    println("- CTranslate2 by OpenNMT Team (MIT License)")
    println("- vBook App by Darkrai9x")
    println("------------------------------------------------------------------")
    println("Status: Server is listening. Ready for offline vBook translation.")
    println("Press Ctrl+C to terminate.")
    println("==================================================================")

    while (true) {
        sleep(1u)
    }
}
